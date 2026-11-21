package server

import (
	"archive/zip"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const maxAPK = 64 << 20
const releaseChunk = 128 << 10
const apkMedia = "application/vnd.android.package-archive"
const releaseFields = "package,code,name,notes,sha256,bytes,created,published"

type androidRelease struct {
	Package   string `json:"packageName"`
	Code      int    `json:"versionCode"`
	Name      string `json:"versionName"`
	Notes     string `json:"notes"`
	SHA       string `json:"sha256"`
	Bytes     int64  `json:"bytes"`
	Created   int64  `json:"created"`
	Published bool   `json:"published"`
}

func scanRelease(row interface{ Scan(...any) error }) (v androidRelease, err error) {
	err = row.Scan(&v.Package, &v.Code, &v.Name, &v.Notes, &v.SHA, &v.Bytes, &v.Created, &v.Published)
	return
}
func knownPackage(p string) bool {
	return p == "org.timepin.app" || p == "org.timepin.app.community" || p == "org.timepin.app.glass" || p == "org.timepin.app.qa"
}

func (a *App) mountReleases(m, admin *http.ServeMux) {
	admin.HandleFunc("GET "+consolePath+"/releases", a.endpoint(a.releaseHistory))
	admin.HandleFunc("POST "+consolePath+"/releases", a.response(a.publishRelease, false))
	admin.HandleFunc("POST "+consolePath+"/releases/{package}/{code}/withdraw", a.endpoint(a.withdrawRelease))
	m.HandleFunc("GET /api/v1/releases/{package}/latest", a.endpoint(a.latestRelease))
	m.HandleFunc("GET /api/v1/releases/{package}/{code}/apk", a.endpoint(a.downloadRelease))
}

func (a *App) releaseHistory(_ http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, e := consoleIdentity(tx, r); e != nil {
			return nil, e
		}
		offset := 0
		if s := r.URL.Query().Get("offset"); s != "" {
			n, e := strconv.Atoi(s)
			if e != nil || n < 0 {
				return nil, invalid("分页位置无效")
			}
			offset = n
		}
		rows, e := tx.Query("SELECT "+releaseFields+" FROM android_releases ORDER BY created DESC,package,code DESC LIMIT 51 OFFSET ?", offset)
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		list := []androidRelease{}
		for rows.Next() {
			v, e := scanRelease(rows)
			if e != nil {
				return nil, e
			}
			list = append(list, v)
		}
		return map[string]any{"releases": list[:min(50, len(list))], "more": len(list) > 50}, rows.Err()
	})
}

// Spool on the data volume. The scratch container has no system temporary directory.
func (a *App) publishRelease(w http.ResponseWriter, r *http.Request) (any, error) {
	_, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) { return consoleIdentity(tx, r) })
	if e != nil {
		return nil, e
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxAPK+(64<<10))
	reader, e := r.MultipartReader()
	if e != nil {
		return nil, invalid("请选择 APK 文件")
	}
	var dbPath string
	if e = a.DB.QueryRowContext(r.Context(), "SELECT file FROM pragma_database_list WHERE name='main'").Scan(&dbPath); e != nil {
		return nil, e
	}
	f, e := os.CreateTemp(filepath.Dir(dbPath), ".release-*")
	if e != nil {
		return nil, e
	}
	defer os.Remove(f.Name())
	defer f.Close()
	var v androidRelease
	hash := sha256.New()
	seen := map[string]bool{}
	for {
		part, e := reader.NextPart()
		if e == io.EOF {
			break
		}
		if e != nil {
			return nil, invalid("上传不完整或超过大小限制")
		}
		key := part.FormName()
		if seen[key] {
			return nil, invalid("上传字段重复")
		}
		seen[key] = true
		switch key {
		case "apk":
			n, e := io.Copy(io.MultiWriter(f, hash), io.LimitReader(part, maxAPK+1))
			if e != nil {
				return nil, e
			}
			if n < 1 || n > maxAPK {
				return nil, invalid("APK 须在 64 MiB 以内")
			}
			v.Bytes = n
		case "notes":
			b, e := io.ReadAll(io.LimitReader(part, 4097))
			if e != nil {
				return nil, e
			}
			if len(b) > 4096 || !utf8.Valid(b) {
				return nil, invalid("更新说明最多 4096 字节")
			}
			v.Notes = strings.TrimSpace(string(b))
		default:
			return nil, invalid("未知上传字段")
		}
	}
	if e = readAPKIdentity(f, &v); e != nil {
		return nil, e
	}
	v.Created = time.Now().Unix()
	v.SHA = hex.EncodeToString(hash.Sum(nil))
	v.Published = true
	if _, e = f.Seek(0, io.SeekStart); e != nil {
		return nil, e
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		admin, e := consoleIdentity(tx, r)
		if e != nil {
			return nil, e
		}
		old, e := scanRelease(tx.QueryRow("SELECT "+releaseFields+" FROM android_releases WHERE package=? AND code=?", v.Package, v.Code))
		if e == nil {
			if old.SHA == v.SHA && old.Notes == v.Notes {
				return old, nil
			}
			return nil, problem{409, "此版本号已发布，请提高 versionCode 再发布"}
		}
		if !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
		if _, e = tx.Exec("INSERT INTO android_releases VALUES(?,?,?,?,?,?,?,1)", v.Package, v.Code, v.Name, v.Notes, v.SHA, v.Bytes, v.Created); e != nil {
			return nil, e
		}
		buf := make([]byte, releaseChunk)
		for i, remaining := 0, v.Bytes; remaining > 0; i++ {
			b := buf[:min(int64(len(buf)), remaining)]
			if _, e = io.ReadFull(f, b); e != nil {
				return nil, e
			}
			if _, e = tx.Exec("INSERT INTO android_release_chunks VALUES(?,?,?,?)", v.Package, v.Code, i, b); e != nil {
				return nil, e
			}
			remaining -= int64(len(b))
		}
		return v, releaseAudit(tx, admin, "instance.releasePublish", v)
	})
}

func readAPKIdentity(f *os.File, v *androidRelease) error {
	invalidAPK := invalid("需要由 TimePin 构建的完整 APK（包含版本信息）")
	z, e := zip.NewReader(f, v.Bytes)
	if e != nil {
		return invalidAPK
	}
	if _, e = z.Open("AndroidManifest.xml"); e != nil {
		return invalidAPK
	}
	if _, e = z.Open("classes.dex"); e != nil {
		return invalidAPK
	}
	data, e := z.Open("assets/timepin-release.json")
	if e != nil {
		return invalidAPK
	}
	defer data.Close()
	b, e := io.ReadAll(io.LimitReader(data, 1025))
	if e != nil || len(b) > 1024 {
		return invalidAPK
	}
	var id struct {
		Package string `json:"packageName"`
		Code    int    `json:"versionCode"`
		Name    string `json:"versionName"`
	}
	if json.Unmarshal(b, &id) != nil || !knownPackage(id.Package) || id.Code <= 0 || id.Code > 2100000000 || strings.TrimSpace(id.Name) == "" || len(id.Name) > 80 {
		return invalidAPK
	}
	v.Package, v.Code, v.Name = id.Package, id.Code, id.Name
	return nil
}
func releaseAudit(tx *sql.Tx, u viewer, op string, v androidRelease) error {
	return auditChange(tx, u, management{Op: op, Target: v.Package, Subject: fmt.Sprintf("%s (%d)", v.Name, v.Code)})
}
func (a *App) withdrawRelease(_ http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		admin, e := consoleIdentity(tx, r)
		if e != nil {
			return nil, e
		}
		v, e := scanRelease(tx.QueryRow("SELECT "+releaseFields+" FROM android_releases WHERE package=? AND code=?", r.PathValue("package"), r.PathValue("code")))
		if errors.Is(e, sql.ErrNoRows) {
			return nil, missing
		}
		if e != nil {
			return nil, e
		}
		if v.Published {
			if _, e = tx.Exec("UPDATE android_releases SET published=0 WHERE package=? AND code=?", v.Package, v.Code); e != nil {
				return nil, e
			}
			if e = releaseAudit(tx, admin, "instance.releaseWithdraw", v); e != nil {
				return nil, e
			}
			v.Published = false
		}
		return v, nil
	})
}
func (a *App) latestRelease(_ http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, e := who(tx, r); e != nil {
			return nil, e
		}
		p := r.PathValue("package")
		if !knownPackage(p) {
			return nil, missing
		}
		v, e := scanRelease(tx.QueryRow("SELECT "+releaseFields+" FROM android_releases WHERE package=? AND published=1 ORDER BY code DESC LIMIT 1", p))
		if errors.Is(e, sql.ErrNoRows) {
			return map[string]any{"release": nil}, nil
		}
		return map[string]any{"release": v}, e
	})
}
func (a *App) downloadRelease(w http.ResponseWriter, r *http.Request) (any, error) {
	value, e := transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, e := who(tx, r); e != nil {
			return nil, e
		}
		v, e := scanRelease(tx.QueryRow("SELECT "+releaseFields+" FROM android_releases WHERE package=? AND code=? AND published=1", r.PathValue("package"), r.PathValue("code")))
		if errors.Is(e, sql.ErrNoRows) {
			return nil, missing
		}
		return v, e
	})
	if e != nil {
		return nil, e
	}
	v := value.(androidRelease)
	w.Header().Set("Content-Type", apkMedia)
	prefix := "TimePin"
	if v.Package == "org.timepin.app.community" {
		prefix += "-community"
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s-%d.apk"`, prefix, v.Code))
	w.Header().Set("Content-Length", strconv.FormatInt(v.Bytes, 10))
	if r.Method != http.MethodHead {
		for part, remaining := 0, v.Bytes; remaining > 0; part++ {
			var b []byte
			if e = a.DB.QueryRowContext(r.Context(), "SELECT data FROM android_release_chunks WHERE package=? AND code=? AND part=?", v.Package, v.Code, part).Scan(&b); e != nil || len(b) == 0 {
				panic(http.ErrAbortHandler)
			}
			if _, e = w.Write(b); e != nil {
				break
			}
			remaining -= int64(len(b))
		}
	}
	return streamResponse{}, nil
}
