package server

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
	"timepin/calendar"
)

//go:embed schema.sql
var schema string

//go:embed v2.sql
var schemaV2 string

//go:embed v3.sql
var schemaV3 string

//go:embed v4.sql
var schemaV4 string

//go:embed v5.sql
var schemaV5 string

//go:embed v6.sql
var schemaV6 string

//go:embed v7.sql
var schemaV7 string

//go:embed v8.sql
var schemaV8 string

//go:embed v9.sql
var schemaV9 string

//go:embed v10.sql
var schemaV10 string

//go:embed v11.sql
var schemaV11 string

func Open(path string) (*sql.DB, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if err = os.MkdirAll(filepath.Dir(abs), 0700); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(abs, os.O_RDWR|os.O_CREATE, 0600)
	if err != nil {
		return nil, err
	}
	if err = f.Close(); err != nil {
		return nil, err
	}
	p := filepath.ToSlash(abs)
	if !strings.HasPrefix(p, "/") {
		p = "/" + p
	}
	uri := url.URL{Scheme: "file", Path: p, RawQuery: "_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)"}
	db, err := sql.Open("sqlite", uri.String())
	if err != nil {
		return nil, err
	}
	// One connection linearizes authorization and writes on this single-instance service.
	db.SetMaxOpenConns(1)
	if _, err = db.Exec("PRAGMA journal_mode=WAL"); err == nil {
		_, err = transaction(context.Background(), db, func(tx *sql.Tx) (any, error) {
			var version int
			if e := tx.QueryRow("PRAGMA user_version").Scan(&version); e != nil {
				return nil, e
			}
			switch version {
			case 0:
				if _, e := tx.Exec(schema); e != nil {
					return nil, e
				}
				fallthrough
			case 1:
				if _, e := tx.Exec(schemaV2); e != nil {
					return nil, e
				}
				fallthrough
			case 2:
				if _, e := tx.Exec(schemaV3); e != nil {
					return nil, e
				}
				fallthrough
			case 3:
				if _, e := tx.Exec(schemaV4); e != nil {
					return nil, e
				}
				fallthrough
			case 4:
				if _, e := tx.Exec(schemaV5); e != nil {
					return nil, e
				}
				fallthrough
			case 5:
				if _, e := tx.Exec(schemaV6); e != nil {
					return nil, e
				}
				fallthrough
			case 6:
				if _, e := tx.Exec(schemaV7); e != nil {
					return nil, e
				}
				fallthrough
			case 7:
				if _, e := tx.Exec(schemaV8); e != nil {
					return nil, e
				}
				fallthrough
			case 8:
				if _, e := tx.Exec(schemaV9); e != nil {
					return nil, e
				}
				fallthrough
			case 9:
				if _, e := tx.Exec(schemaV10); e != nil {
					return nil, e
				}
				fallthrough
			case 10:
				_, e := tx.Exec(schemaV11)
				return nil, e
			case 11:
				return nil, nil
			default:
				return nil, fmt.Errorf("unsupported database version %d", version)
			}
		})
	}
	if err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func transaction(ctx context.Context, db *sql.DB, fn func(*sql.Tx) (any, error)) (any, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	value, err := fn(tx)
	if err != nil {
		return nil, err
	}
	return value, tx.Commit()
}
func secret() string             { return base64.RawURLEncoding.EncodeToString(randBytes(32)) }
func id(prefix string) string    { return prefix + "-" + hex.EncodeToString(randBytes(12)) }
func randBytes(n int) []byte     { b := make([]byte, n); rand.Read(b); return b }
func digest(value string) []byte { hash := sha256.Sum256([]byte(value)); return hash[:] }

type problem struct {
	status  int
	message string
}

func (p problem) Error() string { return p.message }
func invalid(msg string) error  { return problem{400, msg} }

var denied = problem{403, "没有执行此操作的权限"}
var missing = problem{404, "记录不存在或已失效"}
var noSession = problem{401, "请先登录"}

type viewer struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type calendarView struct {
	calendar.Calendar
	Name    string `json:"name"`
	Role    string `json:"role"`
	Visible bool   `json:"visible"`
}
type teamView struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Role string `json:"role"`
}

var roles = []string{"", "reader", "contributor", "editor", "manager"}

func rank(role string) int {
	for i, r := range roles {
		if r == role {
			return i
		}
	}
	return 0
}

const accessible = `SELECT c.id,c.name,c.color,CASE WHEN c.person IS NULL THEN 'group' ELSE 'person' END,coalesce(c.person,c.team),max(a.rank),
 NOT EXISTS(SELECT 1 FROM hidden_calendars h WHERE h.calendar_id=c.id AND h.user_id=a.user_id)
 FROM calendars c JOIN access_paths a ON a.calendar_id=c.id WHERE a.user_id=? `

type scanner interface{ Scan(...any) error }

func readCalendar(s scanner) (calendarView, error) {
	var v calendarView
	var n int
	err := s.Scan(&v.ID, &v.Name, &v.Color, &v.OwnerKind, &v.OwnerID, &n, &v.Visible)
	if err == nil {
		v.Role = roles[n]
	}
	return v, err
}
func allowed(tx *sql.Tx, user, cal string) (calendarView, error) {
	v, e := readCalendar(tx.QueryRow(accessible+" AND c.id=? GROUP BY c.id", user, cal))
	if errors.Is(e, sql.ErrNoRows) {
		return v, denied
	}
	return v, e
}
func listCalendars(tx *sql.Tx, user string) ([]calendarView, error) {
	rows, err := tx.Query(accessible+" GROUP BY c.id ORDER BY c.name,c.id", user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []calendarView{}
	for rows.Next() {
		v, err := readCalendar(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, v)
	}
	return list, rows.Err()
}
func listTeams(tx *sql.Tx, user string) ([]teamView, error) {
	rows, err := tx.Query("SELECT t.id,t.name,m.role FROM teams t JOIN members m ON t.id=m.team WHERE m.user_id=? ORDER BY t.name,t.id", user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []teamView{}
	for rows.Next() {
		var v teamView
		if err = rows.Scan(&v.ID, &v.Name, &v.Role); err != nil {
			return nil, err
		}
		list = append(list, v)
	}
	return list, rows.Err()
}
func teamRole(tx *sql.Tx, user, team string) (string, error) {
	var role string
	e := tx.QueryRow("SELECT role FROM members WHERE team=? AND user_id=?", team, user).Scan(&role)
	if errors.Is(e, sql.ErrNoRows) {
		return "", denied
	}
	return role, e
}
