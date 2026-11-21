package server

import (
	"database/sql"
	"encoding/hex"
	"strings"
)

// The console projects only metadata. Event records and credential values are outside its API.
var directorySQL = map[string]string{
	"calendarOwners": `SELECT u.id,u.name,'' owner,'user' kind,count(*) calendars
 FROM users u JOIN calendars c ON c.person=u.id GROUP BY u.id
 UNION ALL SELECT t.id,t.name,'' owner,'group' kind,count(*) calendars
 FROM teams t JOIN calendars c ON c.team=t.id GROUP BY t.id`,
	"accounts": `SELECT u.id,u.name,'' owner,d.user_id IS NULL enabled,a.user_id IS NOT NULL ai,
 (SELECT count(*) FROM sessions WHERE user_id=u.id AND expires>unixepoch()) sessions,
 EXISTS(SELECT 1 FROM account_recovery WHERE user_id=u.id AND expires>unixepoch()) recovery
 FROM users u LEFT JOIN disabled_users d ON d.user_id=u.id LEFT JOIN ai_members a ON a.user_id=u.id`,
	"calendars": `SELECT c.id,c.name,coalesce(u.name,t.name) owner,c.color,
 CASE WHEN c.team IS NULL THEN 'user' ELSE 'group' END kind,
 EXISTS(SELECT 1 FROM shares WHERE calendar_id=c.id) public
 FROM calendars c LEFT JOIN users u ON c.person=u.id LEFT JOIN teams t ON c.team=t.id`,
	"groups": `SELECT t.id,t.name,'' owner,(SELECT count(*) FROM members WHERE team=t.id) members,
 (SELECT count(*) FROM calendars WHERE team=t.id) calendars FROM teams t`,
	"members": `SELECT u.id,u.name,'' owner,m.role FROM members m JOIN users u ON u.id=m.user_id WHERE m.team=?`,
	"grants": `SELECT g.subject id,coalesce(u.name,t.name) name,'' owner,g.subject_kind kind,
 CASE g.rank WHEN 1 THEN 'reader' WHEN 2 THEN 'contributor' WHEN 3 THEN 'editor' ELSE 'manager' END role
 FROM grants g LEFT JOIN users u ON g.subject_kind='user' AND u.id=g.subject
 LEFT JOIN teams t ON g.subject_kind='group' AND t.id=g.subject WHERE g.calendar_id=?`,
	"shares": `SELECT c.id,c.name,coalesce(u.name,t.name) owner,s.token AS private_value FROM shares s JOIN calendars c ON c.id=s.calendar_id LEFT JOIN users u ON u.id=c.person LEFT JOIN teams t ON t.id=c.team`,
	"invites": `SELECT i.id,coalesce(c.name,t.name,'实例注册') name,'' owner,i.kind,i.role,i.expires FROM invites i
 LEFT JOIN calendars c ON i.kind='calendar' AND c.id=i.target LEFT JOIN teams t ON i.kind='group' AND t.id=i.target WHERE i.expires>unixepoch()`,
	"tokens": `SELECT k.id,k.label name,u.name owner,coalesce(c.name,'所有日历') calendar,k.role,k.expires FROM api_tokens k JOIN users u ON u.id=k.user_id LEFT JOIN calendars c ON c.id=k.calendar_id WHERE k.expires>unixepoch()`,
}

func consoleRows(tx *sql.Tx, query string, args ...any) ([]map[string]any, error) {
	rows, err := tx.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	result := []map[string]any{}
	for rows.Next() {
		values := make([]any, len(cols))
		dest := make([]any, len(cols))
		for i := range values {
			dest[i] = &values[i]
		}
		if err := rows.Scan(dest...); err != nil {
			return nil, err
		}
		item := map[string]any{}
		for i, name := range cols {
			switch name {
			case "private_value":
				item["revision"] = hex.EncodeToString(digest(values[i].(string)))
			case "enabled", "ai", "recovery", "public":
				item[name] = values[i].(int64) != 0
			default:
				item[name] = values[i]
			}
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func consoleDirectory(tx *sql.Tx, m management) (any, error) {
	source, ok := directorySQL[m.Section]
	if !ok {
		return nil, invalid("未知目录")
	}
	args := []any{}
	scoped := m.Section == "members" || m.Section == "grants"
	if m.SubjectKind != "" {
		if m.Section != "calendars" || m.SubjectKind != "user" && m.SubjectKind != "group" {
			return nil, invalid("归属筛选仅适用于日历")
		}
		scoped = true
		if m.SubjectKind == "user" {
			source += " WHERE c.person=?"
		} else {
			source += " WHERE c.team=?"
		}
	}
	if scoped != (m.Target != "") {
		return nil, invalid("请指定对应的团组或日历")
	}
	if scoped {
		args = append(args, m.Target)
	}
	search := strings.TrimSpace(m.Search)
	args = append(args, search, search, m.Offset)
	rows, e := consoleRows(tx, `SELECT * FROM (`+source+`) WHERE instr(lower(name),lower(?)) OR instr(lower(owner),lower(?)) ORDER BY name COLLATE NOCASE,id LIMIT 26 OFFSET ?`, args...)
	if e != nil {
		return nil, e
	}
	more := len(rows) > 25
	if more {
		rows = rows[:25]
	}
	return map[string]any{"items": rows, "more": more}, nil
}

func consoleHistory(tx *sql.Tx, before int64, limit int) (map[string]any, error) {
	query := "SELECT seq,at,actor,action,target,subject,setting FROM console_audit"
	args := []any{}
	if before > 0 {
		query += " WHERE seq<?"
		args = append(args, before)
	}
	rows, e := consoleRows(tx, query+" ORDER BY seq DESC LIMIT ?", append(args, limit+1)...)
	if e != nil {
		return nil, e
	}
	more := len(rows) > limit
	if more {
		rows = rows[:limit]
	}
	return map[string]any{"entries": rows, "more": more}, nil
}
func (a *App) consoleOverview(tx *sql.Tx) (any, error) {
	counts, e := consoleRows(tx, `SELECT (SELECT count(*) FROM users) accounts,(SELECT count(*) FROM disabled_users) disabled,
 (SELECT count(*) FROM calendars) calendars,(SELECT count(*) FROM teams) groups,(SELECT count(*) FROM shares) shares,
 (SELECT count(*) FROM invites WHERE expires>unixepoch()) invites,(SELECT count(*) FROM api_tokens WHERE expires>unixepoch()) tokens,
 (SELECT count(*) FROM sessions WHERE expires>unixepoch()) sessions,(SELECT count(*) FROM ai_members) modelMembers`)
	if e != nil {
		return nil, e
	}
	m, e := a.instanceModel(tx)
	if e != nil {
		return nil, e
	}
	open, e := a.registration(tx)
	if e != nil {
		return nil, e
	}
	var version string
	var schema, bytes int64
	if e := tx.QueryRow("SELECT sqlite_version(),user_version,page_count*page_size FROM pragma_user_version,pragma_page_count,pragma_page_size").Scan(&version, &schema, &bytes); e != nil {
		return nil, e
	}
	hist, e := consoleHistory(tx, 0, 4)
	if e != nil {
		return nil, e
	}
	return map[string]any{"counts": counts[0], "recent": hist["entries"], "registration": open, "model": map[string]any{"enabled": m.Enabled(), "baseUrl": m.BaseURL, "model": m.Name, "hasKey": m.Key != ""}, "storage": map[string]any{"sqlite": version, "schema": schema, "bytes": bytes}}, nil
}
