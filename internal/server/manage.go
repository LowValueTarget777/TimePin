package server

import (
	"bytes"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"timepin/calendar"
	"unicode/utf8"
)

type management struct {
	Password           string `json:"password,omitempty"`
	salt, passwordHash []byte
	DisplayName        *string                   `json:"displayName,omitempty"`
	Avatar             *string                   `json:"avatar,omitempty"`
	Section            string                    `json:"section,omitempty"`
	Search             string                    `json:"search,omitempty"`
	Revision           string                    `json:"revision,omitempty"`
	Offset             int                       `json:"offset,omitempty"`
	Before             int64                     `json:"before,omitempty"`
	BaseURL            string                    `json:"baseUrl,omitempty"`
	ModelName          string                    `json:"model,omitempty"`
	Key                *string                   `json:"key,omitempty"`
	Display            json.RawMessage           `json:"display,omitempty"`
	Op                 string                    `json:"op"`
	OperationID        string                    `json:"operationId,omitempty"`
	Target             string                    `json:"target,omitempty"`
	Name               string                    `json:"name,omitempty"`
	Color              string                    `json:"color,omitempty"`
	Team               string                    `json:"team,omitempty"`
	Role               string                    `json:"role,omitempty"`
	SubjectKind        string                    `json:"subjectKind,omitempty"`
	Subject            string                    `json:"subject,omitempty"`
	Token              string                    `json:"token,omitempty"`
	InviteID           string                    `json:"inviteId,omitempty"`
	Enabled            bool                      `json:"enabled,omitempty"`
	Records            []calendar.PublishedEvent `json:"records,omitempty"`
}

var colorPattern = regexp.MustCompile("^#[0-9A-Fa-f]{6}$")
var tokenPattern = regexp.MustCompile("^[0-9a-f]{64}$")

func validName(s string) bool {
	return strings.TrimSpace(s) == s && s != "" && utf8.RuneCountInString(s) <= 80 && !strings.ContainsAny(s, "\n\r\t")
}
func (a *App) manage(tx *sql.Tx, u viewer, m management) (any, error) {
	// Re-evaluate rights before returning any cached management response.
	switch m.Op {
	case "profile.detail":
		return loadProfile(tx, u, true)
	case "profile.update":
	case "instance.detail":
		return a.instanceDetail(tx)
	case "instance.backups":
		entries, err := consoleRows(tx, "SELECT file,at,bytes FROM console_backups ORDER BY at DESC,file LIMIT 26 OFFSET ?", m.Offset)
		if err != nil {
			return nil, err
		}
		more := len(entries) > 25
		if more {
			entries = entries[:25]
		}
		return map[string]any{"backups": entries, "more": more}, nil
	case "instance.overview":
		return a.consoleOverview(tx)
	case "instance.directory":
		return consoleDirectory(tx, m)
	case "instance.history":
		return consoleHistory(tx, m.Before, 25)
	case "instance.accountCreate", "instance.accountRename", "instance.accountPassword", "instance.account", "instance.registration", "instance.model", "instance.invite", "instance.recovery", "instance.cancelRecovery", "instance.signout", "instance.unshare", "instance.uninvite", "instance.revokeToken", "instance.groupCreate", "instance.groupMember", "instance.groupRename", "instance.calendarCreate", "instance.calendarUpdate", "instance.calendarGrant":

	case "display.detail":
		var value string
		err := tx.QueryRow("SELECT value FROM month_preferences WHERE user_id=?", u.ID).Scan(&value)
		if errors.Is(err, sql.ErrNoRows) {
			return json.RawMessage(`{"weekStart":"sunday","weekNumbers":true,"lunar":false,"lunarFormat":"text","holidayRegion":"system","fontSize":14}`), nil
		}
		return json.RawMessage(value), err
	case "display.update":
	case "model.members", "token.list":
		return accessList(tx, u, m.Op)
	case "token.create":
		if rank(m.Role) == 0 {
			return nil, denied
		}
		if m.Target != allCalendars {
			c, e := allowed(tx, u.ID, m.Target)
			if e != nil {
				return nil, e
			}
			if rank(m.Role) > rank(c.Role) {
				return nil, denied
			}
		}
		if !validName(m.Name) || !tokenPattern.MatchString(m.Token) {
			return nil, invalid("需要名称及 32 个随机字节的小写十六进制密钥")
		}
		m.Token = fmt.Sprintf("%x", digest(m.Token))
	case "model.grant", "token.revoke":
	case "calendar.publish":
		var retired bool
		if e := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM deleted_calendars WHERE id=?)", m.Target).Scan(&retired); e != nil {
			return nil, e
		}
		if retired {
			return nil, problem{409, "这本日历已删除"}
		}
	case "calendar.delete":
		// An actor may replay their own deletion receipt after the calendar is gone.
		// Check live ownership in doManage for every new deletion.
	case "calendar.create":
		if m.Team != "" {
			r, e := teamRole(tx, u.ID, m.Team)
			if e != nil {
				return nil, e
			}
			if r != "admin" {
				return nil, denied
			}
		}
	case "group.create", "invite.accept":
	case "calendar.update", "calendar.grant", "calendar.share", "calendar.invite", "calendar.uninvite", "calendar.detail", "calendar.visible":
		v, e := allowed(tx, u.ID, m.Target)
		if e != nil {
			return nil, e
		}
		if m.Op != "calendar.visible" && v.Role != "manager" {
			return nil, denied
		}
	case "group.detail", "group.member", "group.transfer", "group.invite", "group.uninvite":
		role, e := teamRole(tx, u.ID, m.Target)
		if e != nil {
			return nil, e
		}
		if role != "admin" {
			return nil, denied
		}
	default:
		return nil, invalid("未知管理操作")
	}
	if m.Op == "calendar.detail" || m.Op == "group.detail" {
		return details(tx, m)
	}
	if m.OperationID == "" || len(m.OperationID) > 128 {
		return nil, invalid("需要操作编号")
	}
	raw, e := json.Marshal(m)
	if e != nil {
		return nil, e
	}
	fingerprint := digest(string(raw))
	// Console receipts have a separate principal from calendar users.
	table := "admin_receipts"
	if consoleOp(m.Op) {
		table = "console_receipts"
	}
	var old, body []byte
	e = tx.QueryRow("SELECT fingerprint,body FROM "+table+" WHERE actor=? AND operation_id=?", u.ID, m.OperationID).Scan(&old, &body)
	if e == nil {
		if !bytes.Equal(old, fingerprint) {
			return nil, problem{409, "操作编号已用于其他请求"}
		}
		return json.RawMessage(body), nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return nil, e
	}
	if consoleOp(m.Op) {
		if err := auditChange(tx, u, m); err != nil {
			return nil, err
		}
	}
	value, e := a.doManage(tx, u, m)
	if e != nil {
		return nil, e
	}
	body, e = json.Marshal(value)
	if e != nil {
		return nil, e
	}
	_, e = tx.Exec("INSERT INTO "+table+" VALUES(?,?,?,?)", u.ID, m.OperationID, fingerprint, body)
	return value, e
}

type memberView struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Kind string `json:"kind"`
	Role string `json:"role"`
}
type inviteView struct {
	ID      string `json:"id"`
	Role    string `json:"role"`
	Expires int64  `json:"expires"`
}

func details(tx *sql.Tx, m management) (any, error) {
	kind := "group"
	query := `SELECT u.id,u.name,'user',m.role FROM members m JOIN users u ON u.id=m.user_id WHERE m.team=? ORDER BY u.name`
	if m.Op == "calendar.detail" {
		kind = "calendar"
		query = `SELECT g.subject,coalesce(u.name,t.name),g.subject_kind,
  CASE g.rank WHEN 4 THEN 'manager' WHEN 3 THEN 'editor' WHEN 2 THEN 'contributor' ELSE 'reader' END
  FROM grants g LEFT JOIN users u ON g.subject_kind='user' AND u.id=g.subject LEFT JOIN teams t ON g.subject_kind='group' AND t.id=g.subject WHERE g.calendar_id=? ORDER BY 2`
	}
	rows, e := tx.Query(query, m.Target)
	if e != nil {
		return nil, e
	}
	members := []memberView{}
	for rows.Next() {
		var v memberView
		if e = rows.Scan(&v.ID, &v.Name, &v.Kind, &v.Role); e != nil {
			rows.Close()
			return nil, e
		}
		members = append(members, v)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return nil, e
	}
	rows, e = tx.Query("SELECT id,role,expires FROM invites WHERE kind=? AND target=? AND expires>? ORDER BY id", kind, m.Target, time.Now().Unix())
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	invites := []inviteView{}
	for rows.Next() {
		var v inviteView
		if e = rows.Scan(&v.ID, &v.Role, &v.Expires); e != nil {
			return nil, e
		}
		invites = append(invites, v)
	}
	if e = rows.Err(); e != nil {
		return nil, e
	}
	token := ""
	if kind == "calendar" {
		e = tx.QueryRow("SELECT token FROM shares WHERE calendar_id=?", m.Target).Scan(&token)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return nil, e
		}
	}
	return map[string]any{"members": members, "invites": invites, "shareToken": token}, nil
}
func userID(tx *sql.Tx, name string) (string, error) {
	var target string
	e := tx.QueryRow("SELECT id FROM users WHERE name=?", name).Scan(&target)
	if errors.Is(e, sql.ErrNoRows) {
		return "", invalid("找不到此账号")
	}
	return target, e
}
func (a *App) doManage(tx *sql.Tx, u viewer, m management) (any, error) {
	if m.Op == "profile.update" {
		return saveProfile(tx, u, m)
	}
	if strings.HasPrefix(m.Op, "instance.") {
		return a.changeInstance(tx, m)
	}
	if m.Op == "display.update" {
		_, err := tx.Exec("INSERT INTO month_preferences VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET value=excluded.value", u.ID, string(m.Display))
		return m.Display, err
	}
	if m.Op == "calendar.publish" {
		if !validName(m.Name) || !colorPattern.MatchString(m.Color) {
			return nil, invalid("日历名称或颜色无效")
		}
		var exists bool
		if err := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM calendars WHERE id=?)", m.Target).Scan(&exists); err != nil {
			return nil, err
		}
		if exists {
			return nil, problem{409, "日历编号已被使用"}
		}
		if _, err := tx.Exec("INSERT INTO calendars VALUES(?,?,?,?,NULL)", m.Target, m.Name, strings.ToUpper(m.Color), u.ID); err != nil {
			return nil, err
		}
		for _, item := range m.Records {
			if err := item.Insert(tx, m.Target, u.ID); err != nil {
				if errors.Is(err, calendar.ErrPublication) {
					return nil, invalid(err.Error())
				}
				return nil, err
			}
		}
		return map[string]any{"id": m.Target, "count": len(m.Records)}, nil
	}
	ok := map[string]bool{"ok": true}
	var e error
	switch m.Op {
	case "model.grant":
		target, err := userID(tx, m.Subject)
		if err != nil {
			return nil, err
		}
		if m.Enabled {
			_, e = tx.Exec("INSERT OR IGNORE INTO ai_members VALUES(?)", target)
		} else {
			_, e = tx.Exec("DELETE FROM ai_members WHERE user_id=?", target)
		}
	case "token.create":
		key := id("key")
		hash, err := hex.DecodeString(m.Token)
		if err != nil {
			return nil, err
		}
		result, err := tx.Exec("INSERT INTO api_tokens VALUES(?,?,?,nullif(?,?),?,?,?) ON CONFLICT(digest) DO NOTHING", key, hash, u.ID, m.Target, allCalendars, m.Name, m.Role, time.Now().Add(90*24*time.Hour).Unix())
		if err != nil {
			return nil, err
		}
		n, err := result.RowsAffected()
		if err != nil {
			return nil, err
		}
		if n == 0 {
			return nil, problem{409, "此令牌已登记"}
		}
		return map[string]string{"id": key}, e
	case "token.revoke":
		_, e = tx.Exec("DELETE FROM api_tokens WHERE id=? AND user_id=?", m.Target, u.ID)
	case "group.create":
		if !validName(m.Name) {
			return nil, invalid("团组名称无效")
		}
		team := id("g")
		if _, e = tx.Exec("INSERT INTO teams VALUES(?,?)", team, m.Name); e == nil {
			_, e = tx.Exec("INSERT INTO members VALUES(?,?,'admin')", team, u.ID)
		}
		return map[string]string{"id": team}, e
	case "group.member", "group.transfer":
		target, err := userID(tx, m.Subject)
		if err != nil {
			return nil, err
		}
		if m.Op == "group.transfer" {
			if target == u.ID {
				return nil, invalid("请选择另一位成员")
			}
			if _, err = teamRole(tx, target, m.Target); err != nil {
				return nil, invalid("请选择现有团组成员")
			}
			_, err = tx.Exec("UPDATE members SET role=CASE WHEN user_id=? THEN 'admin' ELSE 'member' END WHERE team=? AND user_id IN (?,?)", target, m.Target, u.ID, target)
			return ok, err
		}
		if m.Role != "admin" && m.Role != "member" && m.Role != "remove" {
			return nil, invalid("团组角色无效")
		}
		if m.Role != "admin" {
			var count int
			e = tx.QueryRow("SELECT count(*) FROM members WHERE team=? AND role='admin' AND user_id<>?", m.Target, target).Scan(&count)
			if e != nil {
				return nil, e
			}
			if count == 0 {
				return nil, invalid("团组需要保留一位管理员")
			}
		}
		if m.Role == "remove" {
			_, e = tx.Exec("DELETE FROM members WHERE team=? AND user_id=?", m.Target, target)
		} else {
			_, e = tx.Exec("INSERT INTO members VALUES(?,?,?) ON CONFLICT(team,user_id) DO UPDATE SET role=excluded.role", m.Target, target, m.Role)
		}
	case "calendar.delete":
		return deleteCalendar(tx, u.ID, m.Target)
	case "calendar.create", "calendar.update":
		if !validName(m.Name) || !colorPattern.MatchString(m.Color) {
			return nil, invalid("请检查日历名称和颜色")
		}
		if m.Op == "calendar.update" {
			_, e = tx.Exec("UPDATE calendars SET name=?,color=? WHERE id=?", m.Name, strings.ToUpper(m.Color), m.Target)
			break
		}
		cal := id("c")
		var person, team any = u.ID, nil
		if m.Team != "" {
			person, team = nil, m.Team
		}
		_, e = tx.Exec("INSERT INTO calendars VALUES(?,?,?,?,?)", cal, m.Name, strings.ToUpper(m.Color), person, team)
		return map[string]string{"id": cal}, e
	case "calendar.grant":
		target := m.Subject
		switch m.SubjectKind {
		case "user":
			target, e = userID(tx, m.Subject)
		case "group":
			// Managers can revoke an existing grant even after leaving that group.
			if m.Role != "remove" {
				_, e = teamRole(tx, u.ID, target)
			}
		default:
			return nil, invalid("授权对象类型无效")
		}
		if e != nil {
			return nil, e
		}
		return writeCalendarGrant(tx, m, target)
	case "calendar.visible":
		if m.Enabled {
			_, e = tx.Exec("DELETE FROM hidden_calendars WHERE calendar_id=? AND user_id=?", m.Target, u.ID)
		} else {
			_, e = tx.Exec("INSERT OR IGNORE INTO hidden_calendars VALUES(?,?)", u.ID, m.Target)
		}
	case "calendar.share":
		if !m.Enabled {
			_, e = tx.Exec("DELETE FROM shares WHERE calendar_id=?", m.Target)
			break
		}
		token := secret()
		_, e = tx.Exec("INSERT INTO shares VALUES(?,?) ON CONFLICT(calendar_id) DO UPDATE SET token=excluded.token", m.Target, token)
		return map[string]string{"token": token}, e
	case "calendar.invite", "group.invite":
		kind := "calendar"
		if m.Op == "group.invite" {
			kind = "group"
			if m.Role != "member" && m.Role != "admin" {
				return nil, invalid("团组角色无效")
			}
		} else if rank(m.Role) == 0 {
			return nil, invalid("日历角色无效")
		}
		token := secret()
		iid := id("i")
		_, e = tx.Exec("INSERT INTO invites VALUES(?,?,?,?,?,?)", iid, digest(token), kind, m.Target, m.Role, time.Now().Add(7*24*time.Hour).Unix())
		return map[string]string{"id": iid, "token": token}, e
	case "calendar.uninvite", "group.uninvite":
		kind, _, _ := strings.Cut(m.Op, ".")
		_, e = tx.Exec("DELETE FROM invites WHERE kind=? AND target=? AND id=?", kind, m.Target, m.InviteID)
	case "invite.accept":
		return accept(tx, u.ID, m.Token)
	}
	return ok, e
}
func accept(tx *sql.Tx, uid, token string) (any, error) {
	var kind, target, role string
	e := tx.QueryRow("SELECT kind,target,role FROM invites WHERE digest=? AND expires>?", digest(token), time.Now().Unix()).Scan(&kind, &target, &role)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, missing
	}
	if e != nil {
		return nil, e
	}
	if kind == "calendar" {
		_, e = tx.Exec("INSERT INTO grants VALUES(?,'user',?,?) ON CONFLICT(calendar_id,subject_kind,subject) DO UPDATE SET rank=max(rank,excluded.rank)", target, uid, rank(role))
	} else if kind == "group" {
		_, e = tx.Exec("INSERT INTO members VALUES(?,?,?) ON CONFLICT(team,user_id) DO UPDATE SET role=CASE WHEN role='admin' THEN role ELSE excluded.role END", target, uid, role)
	}
	if e == nil {
		_, e = tx.Exec("DELETE FROM invites WHERE digest=?", digest(token))
	}
	return map[string]string{"kind": kind, "id": target}, e
}

func writeCalendarGrant(tx *sql.Tx, m management, target string) (any, error) {
	var e error
	if m.Role == "remove" {
		_, e = tx.Exec("DELETE FROM grants WHERE calendar_id=? AND subject_kind=? AND subject=?", m.Target, m.SubjectKind, target)
	} else {
		n := rank(m.Role)
		if n == 0 {
			return nil, invalid("日历角色无效")
		}
		_, e = tx.Exec("INSERT INTO grants VALUES(?,?,?,?) ON CONFLICT(calendar_id,subject_kind,subject) DO UPDATE SET rank=excluded.rank", m.Target, m.SubjectKind, target, n)
	}

	return map[string]bool{"ok": true}, e
}
