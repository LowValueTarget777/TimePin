package server

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// The operator can create local snapshots. No HTTP route serves the database files.
func readOnlyDB(path string) (*sql.DB, error) {
	p := filepath.ToSlash(path)
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	u := url.URL{Scheme: "file", Path: p, RawQuery: "mode=ro&_pragma=busy_timeout(5000)"}
	return sql.Open("sqlite", u.String())
}

func snapshot(ctx context.Context, source, dir, dest string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".snapshot-*")
	if err != nil {
		return err
	}
	temp := f.Name()
	defer os.Remove(temp)
	if err = f.Close(); err != nil {
		return err
	}
	src, err := readOnlyDB(source)
	if err != nil {
		return err
	}
	defer src.Close()
	if _, err = src.ExecContext(ctx, "VACUUM INTO ?", temp); err != nil {
		return err
	}
	copyDB, err := readOnlyDB(temp)
	if err != nil {
		return err
	}
	var integrity string
	err = copyDB.QueryRowContext(ctx, "PRAGMA quick_check").Scan(&integrity)
	closeErr := copyDB.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if integrity != "ok" {
		return errors.New("snapshot integrity check failed")
	}
	return os.Rename(temp, dest)
}

type backupResult struct {
	File  string `json:"file"`
	At    int64  `json:"at"`
	Bytes int64  `json:"bytes"`
}

func (a *App) createBackup(r *http.Request, m management) (any, error) {
	ctx := r.Context()
	if !a.backupLock.TryLock() {
		return nil, problem{409, "另一项备份正在进行"}
	}
	defer a.backupLock.Unlock()
	key := hex.EncodeToString(digest(m.OperationID))
	raw, err := json.Marshal(m)
	if err != nil {
		return nil, err
	}
	fingerprint := digest(string(raw))
	check := func(row *sql.Row) (json.RawMessage, error) {
		var previous, body []byte
		if e := row.Scan(&previous, &body); e != nil {
			return nil, e
		}
		if !bytes.Equal(previous, fingerprint) {
			return nil, problem{409, "操作编号已用于其他请求"}
		}
		return json.RawMessage(body), nil
	}
	const receipt = "SELECT fingerprint,body FROM console_receipts WHERE actor='console' AND operation_id=?"
	body, e := check(a.DB.QueryRowContext(ctx, receipt, m.OperationID))
	if !errors.Is(e, sql.ErrNoRows) {
		return body, e
	}
	var source string
	if e = a.DB.QueryRowContext(ctx, "SELECT file FROM pragma_database_list WHERE name='main'").Scan(&source); e != nil {
		return nil, e
	}
	dir := filepath.Join(filepath.Dir(source), "backups")
	name := "timepin-" + key + ".db"
	dest := filepath.Join(dir, name)
	// If a process stopped between rename and commit, an already verified snapshot can be registered on retry.
	info, e := os.Lstat(dest)
	created := os.IsNotExist(e)
	if created {
		if e = snapshot(ctx, source, dir, dest); e != nil {
			return nil, e
		}
		info, e = os.Lstat(dest)
	}
	if e != nil {
		return nil, e
	}
	if !info.Mode().IsRegular() {
		return nil, invalid("备份路径不可用")
	}
	result := backupResult{name, info.ModTime().Unix(), info.Size()}
	value, err := transaction(ctx, a.DB, func(tx *sql.Tx) (any, error) {
		u, err := consoleIdentity(tx, r)
		if err != nil {
			return nil, err
		}
		if body, e := check(tx.QueryRow(receipt, m.OperationID)); !errors.Is(e, sql.ErrNoRows) {
			return body, e
		}
		body, e := json.Marshal(result)
		if e != nil {
			return nil, e
		}
		if _, err = tx.Exec("INSERT INTO console_receipts VALUES('console',?,?,?)", m.OperationID, fingerprint, body); err != nil {
			return nil, err
		}
		if _, err = tx.Exec("INSERT INTO console_backups VALUES(?,?,?,?)", key, name, result.At, result.Bytes); err != nil {
			return nil, err
		}
		return result, auditChange(tx, u, m)
	})
	if err != nil && created {
		os.Remove(dest)
	}
	return value, err
}
