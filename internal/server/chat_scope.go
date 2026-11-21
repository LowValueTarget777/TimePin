package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
	contract "timepin/contract/v1"
)

type chatScope []calendarView

func selectedBooks(tx *sql.Tx, user string, ids []string) (chatScope, error) {
	if len(ids) == 0 {
		return nil, invalid("请至少选择一本日历")
	}
	if len(ids) == 1 {
		b, e := allowed(tx, user, ids[0])
		return chatScope{b}, e
	}
	books, e := listCalendars(tx, user)
	if e != nil {
		return nil, e
	}
	byID := make(map[string]calendarView, len(books))
	for _, b := range books {
		byID[b.ID] = b
	}
	scope := make(chatScope, 0, len(ids))
	for _, id := range ids {
		b, ok := byID[id]
		if !ok {
			return nil, denied
		}
		scope = append(scope, b)
		delete(byID, id)
	}
	return scope, nil
}

func (s chatScope) roles() string {
	if len(s) == 1 {
		return s[0].Role
	}
	roles := map[string]string{}
	for _, b := range s {
		roles[b.ID] = b.Role
	}
	data, _ := json.Marshal(roles)
	return string(data)
}

func verifyRoles(tx *sql.Tx, user string, c calendarView, proof string) error {
	if !strings.HasPrefix(proof, "{") {
		if c.Role != proof {
			return denied
		}
		return nil
	}
	var roles map[string]string
	if json.Unmarshal([]byte(proof), &roles) != nil || roles[c.ID] != c.Role {
		return denied
	}
	books, e := listCalendars(tx, user)
	if e != nil {
		return e
	}
	for _, b := range books {
		if role, found := roles[b.ID]; found && role == b.Role {
			delete(roles, b.ID)
		}
	}
	if len(roles) > 0 {
		return denied
	}
	return nil
}

func (s chatScope) book(id string) (calendarView, bool) {
	for _, b := range s {
		if b.ID == id {
			return b, true
		}
	}
	return calendarView{}, false
}

func (s chatScope) tools(tx *sql.Tx, user string) ([]any, error) {
	role := "reader"
	owners := map[string]bool{}
	for _, b := range s {
		if rank(b.Role) > rank(role) {
			role = b.Role
		}
		if b.Role == "manager" {
			owner, err := ownsCalendar(tx, user, b.ID)
			if owner {
				owners[b.ID] = true
			}
			if err != nil {
				return nil, err
			}
		}
	}
	tools := chatTools(role, len(owners) > 0)
	if len(s) > 1 {
		for _, tool := range tools {
			fn := tool.(map[string]any)["function"].(map[string]any)
			name := fn["name"].(string)
			if strings.HasPrefix(name, "display_") {
				continue
			}
			op, _ := contract.Find(name)
			ids := []string{}
			for _, b := range s {
				if chatToolAllowed(op, b.Role) && (op.Op != "calendar.delete" || owners[b.ID]) {
					ids = append(ids, b.ID)
				}
			}
			params := fn["parameters"].(map[string]any)
			params["properties"].(map[string]any)["calendarId"] = map[string]any{"type": "string", "enum": ids, "description": "目标日历编号；event_query 省略时查询全部勾选日历"}
			if name != "event_query" {
				params["required"] = append(params["required"].([]any), "calendarId")
			}
		}
	}
	return tools, nil
}

func queryScope(ctx context.Context, tx *sql.Tx, u viewer, scope chatScope, window json.RawMessage) (json.RawMessage, error) {
	groups := make([]json.RawMessage, 0, len(scope))
	size := 0
	for _, b := range scope {
		raw, _ := json.Marshal(map[string]any{"v": 1, "op": "event.query", "calendarId": b.ID, "window": window})
		result, err := execute(ctx, tx, access{user: u}, raw)
		if err != nil {
			return nil, err
		}
		var check struct{ Code string }
		if err = json.Unmarshal(result, &check); err != nil {
			return nil, err
		}
		if check.Code != "ok" {
			return result, nil
		}
		group, _ := json.Marshal(map[string]any{"calendarId": b.ID, "name": b.Name, "result": result})
		size += len(group)
		if size > 24000 {
			return json.RawMessage(`{"code":"window_too_dense","detail":"请缩小查询范围"}`), nil
		}
		groups = append(groups, group)
	}
	return json.Marshal(map[string]any{"code": "ok", "calendars": groups})
}
func (a *App) scopeQuery(_ http.ResponseWriter, r *http.Request) (any, error) {
	var in struct {
		CalendarIDs []string        `json:"calendarIds"`
		Window      json.RawMessage `json:"window"`
	}
	if e := input(r, &in); e != nil {
		return nil, e
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		u, e := who(tx, r)
		if e != nil {
			return nil, e
		}
		scope, e := selectedBooks(tx, u.ID, in.CalendarIDs)
		if e != nil {
			return nil, e
		}
		return queryScope(r.Context(), tx, u, scope, in.Window)
	})
}

// Some providers issue one query per named calendar in the same model message.
func (a *App) parallelQueries(ctx context.Context, r *http.Request, t turn, calls []modelCall) ([]modelMessage, json.RawMessage, error) {
	response, err := transaction(ctx, a.DB, func(tx *sql.Tx) (any, error) {
		u, c, e := a.chatIdentity(tx, r, t.calendar.ID)
		if e != nil {
			return nil, e
		}
		if e = verifyRoles(tx, u.ID, c, t.proof); e != nil {
			return nil, e
		}
		output := make([]json.RawMessage, 0, len(calls))
		size := 0
		for _, call := range calls {
			var input struct {
				CalendarID string          `json:"calendarId"`
				Window     json.RawMessage `json:"window"`
			}
			if e = json.Unmarshal([]byte(call.Function.Arguments), &input); e != nil {
				return nil, invalid("查询参数无效")
			}
			scope := t.scope
			if input.CalendarID != "" {
				b, ok := scope.book(input.CalendarID)
				if !ok {
					return nil, denied
				}
				scope = chatScope{b}
			}
			raw, e := queryScope(ctx, tx, u, scope, input.Window)
			if e != nil {
				return nil, e
			}
			size += len(raw)
			if size > 24000 {
				return nil, invalid("所选结果太多，请缩小查询范围")
			}
			output = append(output, raw)
		}
		return output, nil
	})
	if err != nil {
		return nil, nil, err
	}
	results := response.([]json.RawMessage)
	messages := make([]modelMessage, len(results))
	for i, row := range results {
		messages[i] = modelMessage{Role: "tool", CallID: calls[i].ID, Content: string(row)}
	}
	receipt, err := json.Marshal(map[string]any{"code": "ok", "queries": results})
	return messages, receipt, err
}
