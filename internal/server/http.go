package server

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/pbkdf2"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"timepin/calendar"
	contract "timepin/contract/v1"
)

//go:embed web console
var assets embed.FS

type App struct {
	consoleLAN       netip.Prefix
	backupLock       sync.Mutex
	DB               *sql.DB
	APIOnly          bool
	OpenRegistration bool
	SecureCookies    bool
	hashSlots        chan struct{}
	Model            ModelConfig
	chatSlots        chan struct{}
}

func NewApp(db *sql.DB, open, secure bool) *App {
	return &App{DB: db, OpenRegistration: open, SecureCookies: secure, hashSlots: make(chan struct{}, 2), chatSlots: make(chan struct{}, 2)}
}
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/instance", a.endpoint(func(_ http.ResponseWriter, r *http.Request) (any, error) {
		info := map[string]string{"service": "timepin/1", "name": "TimePin", "version": contract.Build.Name, "author": contract.Build.Author}
		if challenge := r.URL.Query().Get("challenge"); challenge != "" {
			nonce, err := hex.DecodeString(challenge)
			if err != nil || len(nonce) != 16 {
				return nil, invalid("地址校验码应为 32 位十六进制字符串")
			}
			var key []byte
			if err := a.DB.QueryRowContext(r.Context(), "SELECT secret FROM sync_state WHERE singleton=1").Scan(&key); err != nil {
				return nil, err
			}
			mac := hmac.New(sha256.New, key)
			mac.Write([]byte("timepin-instance-address-v1\x00"))
			mac.Write(nonce)
			info["proof"] = hex.EncodeToString(mac.Sum(nil))
		}
		return info, nil
	}))
	console := http.NewServeMux()
	a.mountConsole(console)
	a.mountReleases(mux, console)
	root := http.NewServeMux()
	admin := a.consoleNetwork(console)
	for _, path := range []string{"/admin", "/admin/", consolePath, consolePath + "/"} {
		root.Handle(path, admin)
	}
	mux.HandleFunc("POST /api/v1/recover", a.endpoint(a.recoverAccount))
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if e := a.DB.PingContext(r.Context()); e != nil {
			http.Error(w, "unavailable", 503)
			return
		}
		w.WriteHeader(200)
		io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("GET /api/v1/changes", a.endpoint(a.webRevision))
	mux.HandleFunc("GET /api/v1/state", a.endpoint(a.state))
	mux.HandleFunc("POST /api/v1/register", a.endpoint(a.register))
	mux.HandleFunc("POST /api/v1/login", a.endpoint(a.login))
	mux.HandleFunc("POST /api/v1/logout", a.endpoint(a.logout))
	mux.HandleFunc("POST /api/v1/password", a.endpoint(a.password))
	mux.HandleFunc("POST /api/v1/manage", a.endpoint(a.management))
	mux.HandleFunc("POST /api/v1/commands", a.endpoint(a.commands))
	mux.HandleFunc("POST /api/v1/sync/pull", a.endpoint(a.syncPull))
	mux.HandleFunc("GET /api/v1/batches", a.endpoint(a.batches))
	mux.HandleFunc("POST /api/v1/ai/query", a.endpoint(a.scopeQuery))
	mux.HandleFunc("GET /api/v1/ai/actions", a.endpoint(a.actionCatalog))
	mux.HandleFunc("POST /api/v1/ai/actions", a.endpoint(a.planAction))
	mux.HandleFunc("POST /api/v1/ai/account/confirm", a.endpoint(a.confirmAccount))
	mux.HandleFunc("POST /api/v1/ai/tools", a.endpoint(a.personalTools))
	mux.HandleFunc("GET /api/v1/ai/tools", a.endpoint(a.personalTools))
	mux.HandleFunc("POST /api/v1/ai/chat", a.endpoint(a.chat))
	mux.HandleFunc("POST /api/v1/ai/confirm", a.endpoint(a.confirm))
	registerContracts(mux)
	mux.HandleFunc("GET /api/v1/public/{token}", a.endpoint(a.public))
	static, _ := fs.Sub(assets, "web")
	if !a.APIOnly {
		mux.Handle("GET /", http.FileServer(http.FS(static)))
	}
	root.Handle("/", mux)
	protected := http.NewCrossOriginProtection().Handler(root)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https: http:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		w.Header().Set("Cache-Control", "no-store")
		protected.ServeHTTP(w, r)
	})
}

type apiFunc func(http.ResponseWriter, *http.Request) (any, error)

func (a *App) endpoint(fn apiFunc) http.HandlerFunc {
	return a.response(fn, true)
}

func (a *App) response(fn apiFunc, jsonPost bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		if jsonPost && r.Method == "POST" {
			media, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if media != "application/json" {
				w.WriteHeader(415)
				json.NewEncoder(w).Encode(map[string]string{"error": "需要 JSON 请求"})
				return
			}
			r.Body = http.MaxBytesReader(w, r.Body, 16<<20)
		}
		value, e := fn(w, r)
		if _, sent := value.(streamResponse); sent {
			return
		}
		if e != nil {
			status := 500
			msg := "服务暂时无法完成请求"
			var p problem
			if errors.As(e, &p) {
				status, msg = p.status, p.message
			} else {
				log.Printf("request %s failed (%T)", r.URL.Path[:min(len(r.URL.Path), 8)], e)
			}
			w.WriteHeader(status)
			value = map[string]string{"error": msg}
		}
		json.NewEncoder(w).Encode(value)
	}
}
func input(r *http.Request, v any) error {
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if e := d.Decode(v); e != nil {
		return invalid("请求 JSON 格式无效")
	}
	if d.Decode(new(any)) != io.EOF {
		return invalid("需要单个 JSON 对象")
	}
	return nil
}
func who(tx *sql.Tx, r *http.Request) (viewer, error) {
	var v viewer
	if _, present := bearer(r); present {
		return v, noSession
	}
	c, e := r.Cookie("timepin_session")
	if e != nil {
		return v, noSession
	}
	e = tx.QueryRow("SELECT u.id,u.name FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.digest=? AND s.expires>?", digest(c.Value), time.Now().Unix()).Scan(&v.ID, &v.Name)
	if errors.Is(e, sql.ErrNoRows) {
		e = noSession
	}
	return v, e
}
func (a *App) state(w http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, present := bearer(r); present {
			s, e := identity(tx, r)
			if e != nil {
				return nil, e
			}
			calendars, e := s.tokenCalendars(tx)
			if e != nil {
				return nil, e
			}
			return map[string]any{"user": s.user, "calendars": calendars, "groups": []teamView{}, "ai": map[string]bool{"enabled": false, "admin": false}}, nil
		}
		u, e := who(tx, r)
		if errors.Is(e, noSession) {
			var first bool
			e = tx.QueryRow("SELECT NOT EXISTS(SELECT 1 FROM users)").Scan(&first)
			if e != nil {
				return nil, e
			}
			open, e := a.registration(tx)
			return map[string]any{"user": nil, "canRegister": first || open, "firstAccount": first}, e
		}
		if e != nil {
			return nil, e
		}
		c, e := listCalendars(tx, u.ID)
		if e != nil {
			return nil, e
		}
		t, e := listTeams(tx, u.ID)
		if e != nil {
			return nil, e
		}
		model, e := a.instanceModel(tx)
		if e != nil {
			return nil, e
		}
		enabled, e := a.aiAllowed(tx, u)
		if e != nil {
			return nil, e
		}
		profile, e := loadProfile(tx, u, false)
		return map[string]any{"user": u, "profile": profile, "calendars": c, "groups": t, "ai": map[string]any{"enabled": enabled, "admin": false, "model": model.Name}}, e
	})
}

type credentials struct {
	Name     string `json:"name"`
	Password string `json:"password"`
	Invite   string `json:"invite,omitempty"`
}

func validPassword(p string) bool { return utf8.RuneCountInString(p) >= 10 && len(p) <= 256 }
func (a *App) hash(ctx context.Context, password string, salt []byte) ([]byte, error) {
	select {
	case a.hashSlots <- struct{}{}:
		defer func() { <-a.hashSlots }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return pbkdf2.Key(sha256.New, password, salt, 600000, 32)
}
func session(tx *sql.Tx, uid string) (string, error) {
	token := secret()
	now := time.Now()
	if _, e := tx.Exec("DELETE FROM sessions WHERE expires<=?", now.Unix()); e != nil {
		return "", e
	}
	_, e := tx.Exec("INSERT INTO sessions VALUES(?,?,?)", digest(token), uid, now.Add(30*24*time.Hour).Unix())
	return token, e
}
func (a *App) cookie(w http.ResponseWriter, r *http.Request, token string) {
	age := 30 * 24 * 3600
	if token == "" {
		age = -1
	}
	http.SetCookie(w, &http.Cookie{Name: "timepin_session", Value: token, Path: "/", MaxAge: age, HttpOnly: true, Secure: a.SecureCookies || r.TLS != nil, SameSite: http.SameSiteLaxMode})
}
func (a *App) register(w http.ResponseWriter, r *http.Request) (any, error) {
	var c credentials
	if e := input(r, &c); e != nil {
		return nil, e
	}
	if !validName(c.Name) || !validPassword(c.Password) {
		return nil, invalid("账号名需为 1～80 个字符，密码至少 10 个字符且至多 256 字节")
	}
	salt := randBytes(16)
	pw, e := a.hash(r.Context(), c.Password, salt)
	if e != nil {
		return nil, e
	}
	var token string
	v, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		var anyUser bool
		if e := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM users)").Scan(&anyUser); e != nil {
			return nil, e
		}
		open, e := a.registration(tx)
		if e != nil {
			return nil, e
		}
		if anyUser && !open && c.Invite == "" {
			return nil, problem{403, "此实例需要邀请链接注册"}
		}
		member, e := createMember(tx, c.Name, salt, pw)
		if e != nil {
			return nil, e
		}
		if c.Invite != "" {
			if _, e := accept(tx, member.ID, c.Invite); e != nil {
				return nil, e
			}
		}
		token, e = session(tx, member.ID)
		return member, e
	})
	if e == nil {
		a.cookie(w, r, token)
	}
	return v, e
}

var badLogin = problem{401, "账号或密码不正确"}

func (a *App) verify(ctx context.Context, name, password string) (viewer, []byte, error) {
	return a.verifyCredential(ctx, "SELECT id,name,salt,password_hash FROM users WHERE name=?", name, password)
}

func (a *App) verifyCredential(ctx context.Context, query, name, password string) (viewer, []byte, error) {
	if len(password) > 256 || !validName(name) {
		return viewer{}, nil, badLogin
	}
	var u viewer
	var salt, expected []byte
	e := a.DB.QueryRowContext(ctx, query, name).Scan(&u.ID, &u.Name, &salt, &expected)
	absent := errors.Is(e, sql.ErrNoRows)
	if e != nil && !absent {
		return u, nil, e
	}
	if absent {
		salt = make([]byte, 16)
	}
	h, e := a.hash(ctx, password, salt)
	if e != nil {
		return u, nil, e
	}
	if absent || subtle.ConstantTimeCompare(h, expected) != 1 {
		return u, nil, badLogin
	}
	return u, expected, nil
}
func (a *App) login(w http.ResponseWriter, r *http.Request) (any, error) {
	var c credentials
	if e := input(r, &c); e != nil {
		return nil, e
	}
	u, expected, e := a.verify(r.Context(), c.Name, c.Password)
	if e != nil {
		return nil, e
	}
	var profile personalProfile
	v, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		var matches bool
		if e := tx.QueryRow("SELECT password_hash=? AND name=? AND id NOT IN (SELECT user_id FROM disabled_users) FROM users WHERE id=?", expected, c.Name, u.ID).Scan(&matches); e != nil {
			return nil, e
		}
		if !matches {
			return nil, badLogin
		}
		var err error
		profile, err = loadProfile(tx, u, true)
		if err != nil {
			return nil, err
		}
		return session(tx, u.ID)
	})
	if e == nil {
		a.cookie(w, r, v.(string))
	}
	return map[string]any{"id": u.ID, "name": u.Name, "profile": profile}, e
}
func (a *App) logout(w http.ResponseWriter, r *http.Request) (any, error) {
	if c, e := r.Cookie("timepin_session"); e == nil {
		if _, e = a.DB.ExecContext(r.Context(), "DELETE FROM sessions WHERE digest=?", digest(c.Value)); e != nil {
			return nil, e
		}
	}
	a.cookie(w, r, "")
	return map[string]bool{"ok": true}, nil
}
func (a *App) password(w http.ResponseWriter, r *http.Request) (any, error) {
	var m struct {
		Old string `json:"old"`
		New string `json:"new"`
	}
	if e := input(r, &m); e != nil {
		return nil, e
	}
	if !validPassword(m.New) {
		return nil, invalid("新密码至少 10 个字符且至多 256 字节")
	}
	identity, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) { return who(tx, r) })
	if e != nil {
		return nil, e
	}
	u, expected, e := a.verify(r.Context(), identity.(viewer).Name, m.Old)
	if e != nil {
		return nil, e
	}
	if u.ID != identity.(viewer).ID {
		return nil, badLogin
	}
	salt := randBytes(16)
	pw, e := a.hash(r.Context(), m.New, salt)
	if e != nil {
		return nil, e
	}
	v, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, e := who(tx, r); e != nil {
			return nil, e
		}
		res, e := tx.Exec("UPDATE users SET password_hash=?,salt=? WHERE id=? AND password_hash=?", pw, salt, u.ID, expected)
		if e != nil {
			return nil, e
		}
		n, e := res.RowsAffected()
		if e != nil {
			return nil, e
		}
		if n != 1 {
			return nil, badLogin
		}
		if _, e = tx.Exec("DELETE FROM sessions WHERE user_id=?", u.ID); e != nil {
			return nil, e
		}
		if _, e = tx.Exec("DELETE FROM account_recovery WHERE user_id=?", u.ID); e != nil {
			return nil, e
		}
		return session(tx, u.ID)
	})
	if e == nil {
		a.cookie(w, r, v.(string))
	}
	return map[string]bool{"ok": true}, e
}
func (a *App) management(w http.ResponseWriter, r *http.Request) (any, error) {
	var raw json.RawMessage
	if e := input(r, &raw); e != nil {
		return nil, e
	}
	var m management
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if e := d.Decode(&m); e != nil {
		return nil, invalid("管理请求格式无效")
	}
	if strings.HasPrefix(m.Op, "instance.") || strings.HasPrefix(m.Op, "display.") || strings.HasPrefix(m.Op, "profile.") || strings.HasPrefix(m.Op, "model.") || strings.HasPrefix(m.Op, "token.") || m.Op == "calendar.publish" || m.Op == "calendar.delete" {
		if e := contract.ValidateManagement(raw); e != nil {
			return nil, invalid("管理操作的参数无效")
		}
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		s, e := identity(tx, r)
		if e != nil {
			return nil, e
		}
		return a.scopedManage(tx, s, m)
	})
}
func (a *App) commands(w http.ResponseWriter, r *http.Request) (any, error) {
	var raw json.RawMessage
	if e := input(r, &raw); e != nil {
		return nil, e
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		s, e := identity(tx, r)
		if e != nil {
			return nil, e
		}
		return execute(r.Context(), tx, s, raw)
	})
}
func (a *App) public(w http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		var c calendar.Calendar
		var name string
		e := tx.QueryRow("SELECT c.id,c.name,c.color,coalesce(c.person,c.team),CASE WHEN c.team IS NULL THEN 'person' ELSE 'group' END FROM calendars c JOIN shares s ON c.id=s.calendar_id WHERE s.token=?", r.PathValue("token")).Scan(&c.ID, &name, &c.Color, &c.OwnerID, &c.OwnerKind)
		if errors.Is(e, sql.ErrNoRows) {
			return nil, missing
		}
		if e != nil {
			return nil, e
		}
		q := r.URL.Query()
		window := calendar.Window{Start: q.Get("start"), End: q.Get("end"), Zone: q.Get("zone")}
		raw, _ := json.Marshal(map[string]any{"v": 1, "op": "event.query", "calendarId": c.ID, "window": window})
		result, e := calendar.ExecuteSQL(r.Context(), tx, c, calendar.Principal{ID: "public", Role: "reader"}, raw)
		return map[string]any{"name": name, "color": c.Color, "result": json.RawMessage(result)}, e
	})
}
