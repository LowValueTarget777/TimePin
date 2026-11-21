package server

import "database/sql"

func accessList(tx *sql.Tx, u viewer, op string) (any, error) {
	if op == "model.members" {
		rows, e := tx.Query("SELECT u.id,u.name FROM ai_members a JOIN users u ON u.id=a.user_id ORDER BY u.name")
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		out := []viewer{}
		for rows.Next() {
			var v viewer
			if e = rows.Scan(&v.ID, &v.Name); e != nil {
				return nil, e
			}
			out = append(out, v)
		}
		return out, rows.Err()
	}
	rows, e := tx.Query("SELECT id,label,coalesce(calendar_id,?),role,expires FROM api_tokens WHERE user_id=? ORDER BY label,id", allCalendars, u.ID)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	type token struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Calendar string `json:"calendarId"`
		Role     string `json:"role"`
		Expires  int64  `json:"expires"`
	}
	out := []token{}
	for rows.Next() {
		var t token
		if e = rows.Scan(&t.ID, &t.Name, &t.Calendar, &t.Role, &t.Expires); e != nil {
			return nil, e
		}
		out = append(out, t)
	}
	return out, rows.Err()
}
