package server

import (
	"database/sql"
	"encoding/hex"
	"errors"
	"net/http"
	"time"
)

// Resolve only display names for the audit trail, never serialize the management request.
func auditChange(tx *sql.Tx, u viewer, m management) error {
	target, subject, setting := m.Target, m.Subject, ""
	var lookup string
	switch m.Op {
	case "instance.accountRename", "instance.accountPassword", "instance.account", "instance.signout", "instance.recovery", "instance.cancelRecovery":
		lookup = "SELECT name FROM users WHERE id=?"
	case "instance.unshare", "instance.calendarUpdate", "instance.calendarGrant":
		lookup = "SELECT name FROM calendars WHERE id=?"
	case "instance.groupMember", "instance.groupRename":
		lookup = "SELECT name FROM teams WHERE id=?"
	case "instance.revokeToken":
		lookup = "SELECT label FROM api_tokens WHERE id=?"
	case "instance.uninvite":
		lookup = "SELECT coalesce(c.name,t.name,'实例注册') FROM invites i LEFT JOIN calendars c ON i.kind='calendar' AND c.id=i.target LEFT JOIN teams t ON i.kind='group' AND t.id=i.target WHERE i.id=?"
	case "instance.accountCreate", "instance.groupCreate", "instance.calendarCreate":
		target = m.Name
	}
	if lookup != "" {
		if err := tx.QueryRow(lookup, m.Target).Scan(&target); errors.Is(err, sql.ErrNoRows) {
			return missing
		} else if err != nil {
			return err
		}
	}
	switch m.Op {
	case "instance.account", "model.grant", "instance.registration", "instance.model":
		if m.Enabled {
			setting = "on"
		} else {
			setting = "off"
		}
	case "instance.groupMember", "instance.calendarGrant":
		setting = m.Role
	case "instance.accountRename", "instance.groupRename", "instance.calendarUpdate":
		subject = m.Name
	}
	_, err := tx.Exec("INSERT INTO console_audit(at,actor,action,target,subject,setting) VALUES(unixepoch(),?,?,?,?,?)", u.Name, m.Op, target, subject, setting)
	if err != nil {
		return err
	}
	_, err = tx.Exec("DELETE FROM console_audit WHERE seq <= (SELECT max(seq)-1000 FROM console_audit)")
	return err
}

func checkedDelete(tx *sql.Tx, query string, arg string) (any, error) {
	r, e := tx.Exec(query, arg)
	if e != nil {
		return nil, e
	}
	n, e := r.RowsAffected()
	if e == nil && n == 0 {
		return nil, missing
	}
	return map[string]bool{"ok": true}, e
}

func (a *App) consoleChange(tx *sql.Tx, m management) (any, error) {
	ok := map[string]bool{"ok": true}
	switch m.Op {
	case "instance.accountCreate":
		return createMember(tx, m.Name, m.salt, m.passwordHash)
	case "instance.accountRename":
		if !validName(m.Name) {
			return nil, invalid("请输入有效的账号名")
		}
		var taken bool
		if err := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM users WHERE name=? AND id<>?)", m.Name, m.Target).Scan(&taken); err != nil {
			return nil, err
		}
		if taken {
			return nil, problem{409, "该账号名已被使用"}
		}
		_, err := tx.Exec("UPDATE users SET name=? WHERE id=?", m.Name, m.Target)
		return ok, err
	case "instance.accountPassword":
		if _, err := tx.Exec("UPDATE users SET salt=?,password_hash=? WHERE id=?", m.salt, m.passwordHash, m.Target); err != nil {
			return nil, err
		}
		return ok, revokeMemberCredentials(tx, m.Target)
	case "instance.signout":
		_, e := tx.Exec("DELETE FROM sessions WHERE user_id=?", m.Target)
		return ok, e
	case "instance.uninvite":
		return checkedDelete(tx, "DELETE FROM invites WHERE id=?", m.Target)
	case "instance.revokeToken":
		return checkedDelete(tx, "DELETE FROM api_tokens WHERE id=?", m.Target)
	case "instance.cancelRecovery":
		return checkedDelete(tx, "DELETE FROM account_recovery WHERE user_id=?", m.Target)
	case "instance.unshare":
		var token string
		err := tx.QueryRow("SELECT token FROM shares WHERE calendar_id=?", m.Target).Scan(&token)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, missing
		}
		if err != nil {
			return nil, err
		}
		if m.Revision != hex.EncodeToString(digest(token)) {
			return nil, problem{409, "分享链接已经换新，请刷新后核对"}
		}
		return checkedDelete(tx, "DELETE FROM shares WHERE calendar_id=?", m.Target)
	case "instance.invite", "instance.recovery":
		// The caller generates the code and can retry with the same operation ID. Persist its digest only.
		d := digest(m.Token)
		if m.Op == "instance.invite" {
			expires := time.Now().Add(7 * 24 * time.Hour).Unix()
			key := id("i")
			r, e := tx.Exec("INSERT INTO invites VALUES(?,?,'instance','instance','member',?) ON CONFLICT(digest) DO NOTHING", key, d, expires)
			if e != nil {
				return nil, e
			}
			if n, _ := r.RowsAffected(); n != 1 {
				return nil, problem{409, "邀请码已使用"}
			}
			return map[string]any{"id": key, "expires": expires}, nil
		}
		var enabled bool
		if err := tx.QueryRow("SELECT NOT EXISTS(SELECT 1 FROM disabled_users WHERE user_id=?)", m.Target).Scan(&enabled); err != nil {
			return nil, err
		}
		if !enabled {
			return nil, invalid("请先启用此账号")
		}
		expires := time.Now().Add(30 * time.Minute).Unix()
		_, e := tx.Exec("INSERT INTO account_recovery VALUES(?,?,?) ON CONFLICT(user_id) DO UPDATE SET digest=excluded.digest,expires=excluded.expires", m.Target, d, expires)
		return map[string]any{"id": m.Target, "expires": expires}, e
	case "instance.groupCreate", "instance.calendarCreate":
		owner := viewer{}
		if m.Op == "instance.groupCreate" || m.SubjectKind == "user" {
			key, e := userID(tx, m.Subject)
			if e != nil {
				return nil, e
			}
			owner.ID = key
		} else {
			var key string
			if e := tx.QueryRow("SELECT id FROM teams WHERE id=?", m.Subject).Scan(&key); errors.Is(e, sql.ErrNoRows) {
				return nil, missing
			} else if e != nil {
				return nil, e
			}
			m.Team = key
		}
		if m.Op == "instance.groupCreate" {
			m.Op = "group.create"
		} else {
			m.Op = "calendar.create"
		}
		return a.doManage(tx, owner, m)
	case "instance.groupRename":
		if !validName(m.Name) {
			return nil, invalid("团组名称无效")
		}
		_, e := tx.Exec("UPDATE teams SET name=? WHERE id=?", m.Name, m.Target)
		return ok, e
	case "instance.groupMember":
		m.Op = "group.member"
		return a.doManage(tx, viewer{}, m)
	case "instance.calendarUpdate":
		m.Op = "calendar.update"
		return a.doManage(tx, viewer{}, m)
	case "instance.calendarGrant":
		if m.SubjectKind == "user" {
			m.Op = "calendar.grant"
			return a.doManage(tx, viewer{}, m)
		}
		var team string
		if err := tx.QueryRow("SELECT id FROM teams WHERE id=?", m.Subject).Scan(&team); errors.Is(err, sql.ErrNoRows) {
			return nil, missing
		} else if err != nil {
			return nil, err
		}
		return writeCalendarGrant(tx, m, team)
	}
	return nil, invalid("未知实例操作")
}

func (a *App) recoverAccount(_ http.ResponseWriter, r *http.Request) (any, error) {
	var in struct {
		Name     string `json:"name"`
		Token    string `json:"token"`
		Password string `json:"password"`
	}
	if e := input(r, &in); e != nil {
		return nil, e
	}
	if !validName(in.Name) || !tokenPattern.MatchString(in.Token) || !validPassword(in.Password) {
		return nil, invalid("请检查账号、恢复码和新密码（至少 10 个字符）")
	}
	const lookup = `SELECT u.id FROM account_recovery k JOIN users u ON u.id=k.user_id LEFT JOIN disabled_users d ON d.user_id=u.id
 WHERE d.user_id IS NULL AND u.name=? AND k.digest=? AND k.expires>unixepoch()`
	d := digest(in.Token)
	var uid string
	check := func(e error) error {
		if errors.Is(e, sql.ErrNoRows) {
			return invalid("账号或恢复码无效，或恢复码已过期")
		}
		return e
	}
	if e := check(a.DB.QueryRowContext(r.Context(), lookup, in.Name, d).Scan(&uid)); e != nil {
		return nil, e
	}
	salt := randBytes(16)
	hash, e := a.hash(r.Context(), in.Password, salt)
	if e != nil {
		return nil, e
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if e := check(tx.QueryRow(lookup, in.Name, d).Scan(&uid)); e != nil {
			return nil, e
		}
		if _, e := tx.Exec("UPDATE users SET salt=?,password_hash=? WHERE id=?", salt, hash, uid); e != nil {
			return nil, e
		}
		return map[string]bool{"ok": true}, revokeMemberCredentials(tx, uid)
	})
}
