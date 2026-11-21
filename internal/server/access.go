package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"timepin/calendar"
)

type access struct {
	user         viewer
	target, role string
}

const allCalendars = "*"

func bearer(r *http.Request) (string, bool) {
	scheme, key, _ := strings.Cut(r.Header.Get("Authorization"), " ")
	return key, strings.EqualFold(scheme, "Bearer")
}
func identity(tx *sql.Tx, r *http.Request) (access, error) {
	var s access
	key, present := bearer(r)
	if !present {
		u, e := who(tx, r)
		s.user = u
		return s, e
	}
	if len(key) != 64 {
		return s, noSession
	}
	err := tx.QueryRow("SELECT u.id,u.name,coalesce(t.calendar_id,?),t.role FROM api_tokens t JOIN users u ON u.id=t.user_id WHERE t.digest=? AND t.expires>?", allCalendars, digest(key), time.Now().Unix()).Scan(&s.user.ID, &s.user.Name, &s.target, &s.role)
	if errors.Is(err, sql.ErrNoRows) {
		return s, noSession
	}
	return s, err
}
func (s access) calendar(tx *sql.Tx, target string) (calendarView, error) {
	if s.target != "" && s.target != allCalendars && s.target != target {
		return calendarView{}, denied
	}
	c, e := allowed(tx, s.user.ID, target)
	return s.limitRole(c), e
}
func (s access) limitRole(c calendarView) calendarView {
	if s.target != "" && rank(s.role) < rank(c.Role) {
		c.Role = s.role
	}
	return c
}
func (s access) tokenCalendars(tx *sql.Tx) ([]calendarView, error) {
	if s.target != allCalendars {
		c, err := s.calendar(tx, s.target)
		return []calendarView{c}, err
	}
	calendars, err := listCalendars(tx, s.user.ID)
	for i, c := range calendars {
		calendars[i] = s.limitRole(c)
	}
	return calendars, err
}
func execute(ctx context.Context, tx *sql.Tx, s access, raw json.RawMessage) (json.RawMessage, error) {
	var key struct {
		CalendarID string `json:"calendarId"`
	}
	if e := json.Unmarshal(raw, &key); e != nil {
		return nil, invalid("需要日历编号")
	}
	v, e := s.calendar(tx, key.CalendarID)
	if e != nil {
		return nil, e
	}
	return calendar.ExecuteSQL(ctx, tx, v.Calendar, calendar.Principal{ID: s.user.ID, Role: v.Role}, raw)
}
func (a *App) scopedManage(tx *sql.Tx, s access, m management) (any, error) {
	if s.target != "" {
		if !strings.HasPrefix(m.Op, "calendar.") || m.Op == "calendar.create" || m.Op == "calendar.publish" {
			return nil, denied
		}
		if m.Op == "calendar.delete" && s.target == allCalendars {
			if s.role != "manager" {
				return nil, denied
			}
			return a.manage(tx, s.user, m)
		}
		c, e := s.calendar(tx, m.Target)
		if e != nil {
			return nil, e
		}
		if m.Op != "calendar.visible" && c.Role != "manager" {
			return nil, denied
		}
	}
	if consoleOp(m.Op) {
		return nil, denied
	}
	return a.manage(tx, s.user, m)
}
func (a *App) batches(w http.ResponseWriter, r *http.Request) (any, error) {
	q := r.URL.Query()
	before := int64(1<<63 - 1)
	if q.Has("before") {
		var e error
		before, e = strconv.ParseInt(q.Get("before"), 10, 64)
		if e != nil || before < 1 {
			return nil, invalid("游标无效")
		}
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		s, e := identity(tx, r)
		if e != nil {
			return nil, e
		}
		return listBatches(tx, s, q.Get("calendarId"), before)
	})
}
func listBatches(tx *sql.Tx, s access, calendarID string, before int64) (any, error) {
	c, e := s.calendar(tx, calendarID)
	if e != nil {
		return nil, e
	}
	if c.Role == "reader" {
		return nil, denied
	}
	rows, e := tx.Query("SELECT seq,id FROM import_batches WHERE calendar_id=? AND seq<? ORDER BY seq DESC LIMIT 50", c.ID, before)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	type item struct {
		Seq int64  `json:"seq"`
		ID  string `json:"id"`
	}
	out := []item{}
	for rows.Next() {
		var b item
		if e = rows.Scan(&b.Seq, &b.ID); e != nil {
			return nil, e
		}
		out = append(out, b)
	}
	return out, rows.Err()
}
func (a *App) aiAllowed(tx *sql.Tx, u viewer) (bool, error) {
	model, err := a.instanceModel(tx)
	if err != nil {
		return false, err
	}
	if !model.Enabled() {
		return false, nil
	}
	var ok bool
	e := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM ai_members WHERE user_id=?)", u.ID).Scan(&ok)
	return ok, e
}
