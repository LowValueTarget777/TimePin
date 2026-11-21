package server

import (
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
)

type pullRequest struct {
	CalendarID string `json:"calendarId"`
	Cursor     string `json:"cursor,omitempty"`
	Limit      *int   `json:"limit,omitempty"`
	Sources    bool   `json:"sources,omitempty"`
}

type pullPage struct {
	Status   string            `json:"status"`
	Calendar *calendarView     `json:"calendar,omitempty"`
	Changes  []json.RawMessage `json:"changes"`
	Cursor   string            `json:"cursor"`
	More     bool              `json:"more"`
	Sources  map[string]int64  `json:"sources,omitempty"`
}

// initial skips tombstones that already existed when an empty replica started.
// Deletions after that point must be delivered even during initial pagination.
type checkpoint struct {
	after, initial int64
	metadata       [16]byte
}

func cursorMAC(secret []byte, s access, cal string, payload []byte) []byte {
	mac := hmac.New(sha256.New, secret)
	scope, _ := json.Marshal([]string{"sync-v1", s.user.ID, s.target, s.role, cal})
	mac.Write(scope)
	mac.Write(payload)
	return mac.Sum(nil)[:16]
}

func (c checkpoint) encode(secret []byte, s access, cal string) string {
	var data [48]byte
	binary.BigEndian.PutUint64(data[:8], uint64(c.after))
	binary.BigEndian.PutUint64(data[8:16], uint64(c.initial))
	copy(data[16:32], c.metadata[:])
	copy(data[32:], cursorMAC(secret, s, cal, data[:32]))
	return base64.RawURLEncoding.EncodeToString(data[:])
}

func decodeCursor(token string, secret []byte, s access, cal string) (checkpoint, bool) {
	var c checkpoint
	if len(token) != 64 {
		return c, false
	}
	b, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(b) != 48 || !hmac.Equal(b[32:], cursorMAC(secret, s, cal, b[:32])) {
		return c, false
	}
	c.after = int64(binary.BigEndian.Uint64(b[:8]))
	c.initial = int64(binary.BigEndian.Uint64(b[8:16]))
	copy(c.metadata[:], b[16:32])
	return c, true
}

const pullChanges = `SELECT sync_seq,event_id,source_version, CASE WHEN live=1 THEN record
 ELSE json_object('id',event_id,'version',json_extract(CAST(record AS TEXT),'$.version'),'deleted',json('true')) END
 FROM events WHERE calendar_id=? AND sync_seq>? AND (live=1 OR sync_seq>?)
 ORDER BY sync_seq LIMIT ?`

func (a *App) syncPull(w http.ResponseWriter, r *http.Request) (any, error) {
	var in pullRequest
	if err := input(r, &in); err != nil {
		return nil, err
	}
	limit := 128
	if in.Limit != nil {
		limit = *in.Limit
	}
	if limit < 1 || limit > 512 || len(in.CalendarID) == 0 || len(in.CalendarID) > 128 {
		return nil, invalid("需要日历编号，分页数量范围 1～512")
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		s, err := identity(tx, r)
		if err != nil {
			return nil, err
		}
		var secret []byte
		if err = tx.QueryRow("SELECT secret FROM sync_state WHERE singleton=1").Scan(&secret); err != nil {
			return nil, err
		}
		c, valid := decodeCursor(in.Cursor, secret, s, in.CalendarID)
		cal, err := s.calendar(tx, in.CalendarID)
		if errors.Is(err, denied) && valid {
			return map[string]string{"status": "revoked"}, nil
		}
		if err != nil {
			return nil, err
		}
		var high int64
		if err = tx.QueryRow("SELECT coalesce(max(sync_seq),0) FROM events WHERE calendar_id=?", cal.ID).Scan(&high); err != nil {
			return nil, err
		}
		if in.Cursor == "" {
			c.initial = high
		} else if !valid || c.after > high || c.initial > high {
			return nil, problem{409, "同步游标失效，请重新建立副本"}
		}
		page := pullPage{Status: "ok", Changes: []json.RawMessage{}}
		meta, err := json.Marshal(cal)
		if err != nil {
			return nil, err
		}
		hash := sha256.Sum256(meta)
		if fresh := [16]byte(hash[:16]); in.Cursor == "" || c.metadata != fresh {
			page.Calendar = &cal
			c.metadata = fresh
		}
		rows, err := tx.QueryContext(r.Context(), pullChanges, cal.ID, c.after, c.initial, limit+1)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		total := 0
		for rows.Next() {
			var seq int64
			var id string
			var source int64
			var raw []byte
			if err = rows.Scan(&seq, &id, &source, &raw); err != nil {
				return nil, err
			}
			if len(page.Changes) == limit || (total+len(raw) > 1<<20 && total > 0) {
				page.More = true
				break
			}
			page.Changes = append(page.Changes, raw)
			if in.Sources && source != 0 {
				if page.Sources == nil {
					page.Sources = make(map[string]int64)
				}
				page.Sources[id] = source
			}
			total += len(raw)
			c.after = seq
		}
		if err = rows.Err(); err != nil {
			return nil, err
		}
		if !page.More {
			c.after, c.initial = high, 0
		}
		page.Cursor = c.encode(secret, s, cal.ID)
		return page, nil
	})
}
