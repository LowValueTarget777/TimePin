package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	contract "timepin/contract/v1"
)

const consoleCookie = "timepin_console"
const consolePath = "/api/v1/admin"
const consoleLifetime = 12 * time.Hour

// SetConsoleAccount is a host-side provisioning operation. Rotating the
// credential revokes existing console sessions in the same transaction.
func (a *App) SetConsoleAccount(ctx context.Context, name, password string) error {
	if !validName(name) || !validPassword(password) {
		return invalid("需要有效的后台账号名以及 10 个字符以上、至多 256 字节的密码")
	}
	salt := randBytes(16)
	hash, err := a.hash(ctx, password, salt)
	if err != nil {
		return err
	}
	_, err = transaction(ctx, a.DB, func(tx *sql.Tx) (any, error) {
		if _, err := tx.Exec("INSERT INTO console_account VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,salt=excluded.salt,password_hash=excluded.password_hash", name, salt, hash); err != nil {
			return nil, err
		}
		_, err := tx.Exec("DELETE FROM console_sessions")
		return nil, err
	})
	return err
}

func (a *App) mountConsole(mux *http.ServeMux) {
	serve := func(file string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) { http.ServeFileFS(w, r, assets, file) }
	}
	mux.HandleFunc("GET /admin", serve("console/index.html"))
	mux.HandleFunc("GET /admin/{$}", serve("console/index.html"))
	for _, name := range []string{"panel.js", "releases.js", "directory.js", "panel.css", "console-ui.js"} {
		mux.HandleFunc("GET /admin/"+name, serve("console/"+name))
	}
	for _, file := range []string{"app.css", "dark.css", "ui.js", "components.js", "mark.svg", "providers.json"} {
		mux.HandleFunc("GET /admin/"+file, serve("web/"+file))
	}
	mux.HandleFunc("POST "+consolePath+"/login", a.endpoint(a.consoleLogin))
	mux.HandleFunc("POST "+consolePath+"/logout", a.endpoint(a.consoleLogout))
	mux.HandleFunc("GET "+consolePath+"/state", a.endpoint(func(_ http.ResponseWriter, r *http.Request) (any, error) {
		return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
			return consoleIdentity(tx, r)
		})
	}))
	mux.HandleFunc("POST "+consolePath+"/manage", a.endpoint(a.consoleManage))
}

func (a *App) setConsoleCookie(w http.ResponseWriter, r *http.Request, value string) {
	age := int(consoleLifetime.Seconds())
	if value == "" {
		age = -1
	}
	http.SetCookie(w, &http.Cookie{Name: consoleCookie, Value: value, Path: consolePath, MaxAge: age, HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: a.SecureCookies || r.TLS != nil})
}

func consoleIdentity(tx *sql.Tx, r *http.Request) (viewer, error) {
	var u viewer
	c, err := r.Cookie(consoleCookie)
	if err != nil || r.Header.Get("Authorization") != "" {
		return u, noSession
	}
	err = tx.QueryRow("SELECT 'console',a.name FROM console_sessions s JOIN console_account a ON a.id=1 WHERE s.digest=? AND s.expires>?", digest(c.Value), time.Now().Unix()).Scan(&u.ID, &u.Name)
	if errors.Is(err, sql.ErrNoRows) {
		return u, noSession
	}
	return u, err
}

func (a *App) consoleLogin(w http.ResponseWriter, r *http.Request) (any, error) {
	var c struct {
		Name     string `json:"name"`
		Password string `json:"password"`
	}
	if e := input(r, &c); e != nil {
		return nil, e
	}
	u, hash, e := a.verifyCredential(r.Context(), "SELECT 'console',name,salt,password_hash FROM console_account WHERE name=?", c.Name, c.Password)
	if e != nil {
		return nil, e
	}
	token := secret()
	_, e = transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		now := time.Now()
		if _, e := tx.Exec("DELETE FROM console_sessions WHERE expires<=?", now.Unix()); e != nil {
			return nil, e
		}
		res, e := tx.Exec("INSERT INTO console_sessions SELECT ?,? FROM console_account WHERE name=? AND password_hash=?", digest(token), now.Add(consoleLifetime).Unix(), c.Name, hash)
		if e != nil {
			return nil, e
		}
		rows, e := res.RowsAffected()
		if e == nil && rows != 1 {
			return nil, badLogin
		}
		return nil, e
	})
	if e == nil {
		a.setConsoleCookie(w, r, token)
	}
	return u, e
}

func (a *App) consoleLogout(w http.ResponseWriter, r *http.Request) (any, error) {
	if c, e := r.Cookie(consoleCookie); e == nil {
		if _, e = a.DB.ExecContext(r.Context(), "DELETE FROM console_sessions WHERE digest=?", digest(c.Value)); e != nil {
			return nil, e
		}
	}
	a.setConsoleCookie(w, r, "")
	return map[string]bool{"ok": true}, nil
}

func consoleOp(op string) bool {
	return strings.HasPrefix(op, "instance.") || op == "model.grant" || op == "model.members"
}

func (a *App) consoleManage(_ http.ResponseWriter, r *http.Request) (any, error) {
	var raw json.RawMessage
	if e := input(r, &raw); e != nil {
		return nil, e
	}
	var m management
	result, err := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		u, e := consoleIdentity(tx, r)
		if e != nil {
			return nil, e
		}
		if json.Unmarshal(raw, &m) != nil {
			return nil, invalid("后台请求格式无效")
		}
		if !consoleOp(m.Op) {
			return nil, denied
		}
		if e := contract.ValidateManagement(raw); e != nil {
			return nil, invalid("后台操作的参数无效")
		}
		if m.Op == "instance.backupCreate" || m.Op == "instance.accountCreate" || m.Op == "instance.accountPassword" {
			return nil, nil
		}
		return a.manage(tx, u, m)
	})
	if err == nil && m.Op == "instance.backupCreate" {
		return a.createBackup(r, m)
	}
	if err == nil && (m.Op == "instance.accountCreate" || m.Op == "instance.accountPassword") {
		return a.consoleCredential(r, m)
	}
	return result, err
}
