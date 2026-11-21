package server

import (
	"database/sql"
	"errors"
	"net/url"
	"strings"
)

func (a *App) registration(tx *sql.Tx) (bool, error) {
	var open bool
	err := tx.QueryRow("SELECT coalesce(registration,?) FROM instance_policy", a.OpenRegistration).Scan(&open)
	return open, err
}

func (a *App) instanceModel(tx *sql.Tx) (ModelConfig, error) {
	m := a.Model
	if m.BaseURL == "" {
		m.BaseURL = deepSeekBase
	}
	if m.Name == "" {
		m.Name = "deepseek-flash"
	}
	err := tx.QueryRow("SELECT coalesce(base,?),coalesce(model,?),coalesce(api_key,?),coalesce(model_enabled,?) FROM instance_policy", m.BaseURL, m.Name, m.Key, m.active).Scan(&m.BaseURL, &m.Name, &m.Key, &m.active)
	return m, err
}

func (a *App) instanceDetail(tx *sql.Tx) (any, error) {
	model, e := a.instanceModel(tx)
	if e != nil {
		return nil, e
	}
	open, e := a.registration(tx)
	if e != nil {
		return nil, e
	}
	rows, e := tx.Query("SELECT u.id,u.name,d.user_id IS NULL,g.user_id IS NOT NULL FROM users u LEFT JOIN disabled_users d ON d.user_id=u.id LEFT JOIN ai_members g ON g.user_id=u.id ORDER BY u.name")
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	users := []map[string]any{}
	for rows.Next() {
		var id, name string
		var enabled, ai bool
		if e = rows.Scan(&id, &name, &enabled, &ai); e != nil {
			return nil, e
		}
		users = append(users, map[string]any{"id": id, "name": name, "enabled": enabled, "ai": ai})
	}
	return map[string]any{"users": users, "registration": open, "model": map[string]any{"enabled": model.Enabled(), "baseUrl": model.BaseURL, "model": model.Name, "hasKey": model.Key != ""}}, rows.Err()
}

func (a *App) changeInstance(tx *sql.Tx, m management) (any, error) {
	var e error
	switch m.Op {
	case "instance.registration":
		_, e = tx.Exec("UPDATE instance_policy SET registration=? WHERE id=1", m.Enabled)
	case "instance.account":
		var id string
		e = tx.QueryRow("SELECT id FROM users WHERE id=?", m.Target).Scan(&id)
		if errors.Is(e, sql.ErrNoRows) {
			return nil, missing
		}
		if e != nil {
			return nil, e
		}
		if m.Enabled {
			_, e = tx.Exec("DELETE FROM disabled_users WHERE user_id=?", id)
			break
		}
		for _, q := range []string{"INSERT OR IGNORE INTO disabled_users VALUES(?)", "DELETE FROM sessions WHERE user_id=?", "DELETE FROM api_tokens WHERE user_id=?", "DELETE FROM account_recovery WHERE user_id=?"} {
			if _, err := tx.Exec(q, id); err != nil {
				return nil, err
			}
		}
	case "instance.model":
		current, err := a.instanceModel(tx)
		if err != nil {
			return nil, err
		}
		base := strings.TrimRight(strings.TrimSpace(m.BaseURL), "/")
		endpoint, err := url.Parse(base)
		if err != nil || endpoint.Hostname() == "" || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" || (endpoint.Scheme != "https" && endpoint.Scheme != "http") || !validName(m.ModelName) {
			return nil, invalid("模型地址或编号无效")
		}
		key := current.Key
		if m.Key != nil {
			key = *m.Key
		} else if base != current.BaseURL {
			key = ""
		}
		_, e = tx.Exec("UPDATE instance_policy SET model_enabled=?,base=?,model=?,api_key=? WHERE id=1", m.Enabled, base, m.ModelName, key)
	default:
		return a.consoleChange(tx, m)
	}
	return map[string]bool{"ok": true}, e
}
