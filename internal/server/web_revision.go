package server

import (
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"net/http"
)

// The index supplies the latest position without reading event bodies.
func (a *App) webRevision(_ http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		u, e := who(tx, r)
		if e != nil {
			return nil, e
		}
		books, e := listCalendars(tx, u.ID)
		if e != nil {
			return nil, e
		}
		positions := make([]int64, len(books))
		for i, b := range books {
			if !b.Visible {
				continue
			}
			e = tx.QueryRow("SELECT coalesce(max(sync_seq),0) FROM events WHERE calendar_id=?", b.ID).Scan(&positions[i])
			if e != nil {
				return nil, e
			}
		}
		profile, e := loadProfile(tx, u, false)
		if e != nil {
			return nil, e
		}
		raw, e := json.Marshal([]any{u.ID, books, positions, profile.Revision})
		if e != nil {
			return nil, e
		}
		return map[string]string{"revision": hex.EncodeToString(digest(string(raw)))}, nil
	})
}
