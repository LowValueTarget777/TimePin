package server

import "database/sql"

func ownsCalendar(tx *sql.Tx, actor, target string) (bool, error) {
	var owner bool
	err := tx.QueryRow(`SELECT EXISTS(SELECT 1 FROM calendars c WHERE c.id=? AND
		(c.person=? OR EXISTS(SELECT 1 FROM members m WHERE m.team=c.team AND m.user_id=? AND m.role='admin')))`, target, actor, actor).Scan(&owner)
	return owner, err
}

func deleteCalendar(tx *sql.Tx, actor, target string) (any, error) {
	owner, err := ownsCalendar(tx, actor, target)
	if err != nil {
		return nil, err
	}
	if !owner {
		return nil, denied
	}
	if _, err := tx.Exec("INSERT INTO deleted_calendars(id) VALUES(?)", target); err != nil {
		return nil, err
	}
	for _, table := range []string{"ai_turns", "api_tokens", "shares", "grants", "hidden_calendars", "receipts", "import_history", "import_batches", "events"} {
		if _, err := tx.Exec("DELETE FROM "+table+" WHERE calendar_id=?", target); err != nil {
			return nil, err
		}
	}
	if _, err := tx.Exec("DELETE FROM invites WHERE kind='calendar' AND target=?", target); err != nil {
		return nil, err
	}
	_, err = tx.Exec("DELETE FROM calendars WHERE id=?", target)
	return map[string]bool{"ok": true}, err
}
