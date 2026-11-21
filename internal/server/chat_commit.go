package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

func (a *App) finishTurn(ctx context.Context, r *http.Request, t turn, op string, command json.RawMessage, text string, readResult json.RawMessage) (any, error) {
	return transaction(ctx, a.DB, func(tx *sql.Tx) (any, error) {
		u, c, e := a.chatIdentity(tx, r, t.calendar.ID)
		if e != nil {
			return nil, e
		}
		if e = verifyRoles(tx, u.ID, c, t.proof); e != nil {
			return nil, e
		}
		if cached, e := t.cached(tx); e != nil || cached != nil {
			return cached, e
		}
		response := chatReply{OperationID: t.input.OperationID, Status: "completed", Text: text, Op: op}
		var pending []byte
		switch {
		case command == nil:
			response.Result = readResult
			if strings.TrimSpace(text) == "" {
				return nil, problem{502, "模型返回空回复"}
			}
		case op == "calendar.delete":
			owner, err := ownsCalendar(tx, u.ID, c.ID)
			if err != nil {
				return nil, err
			}
			if !owner {
				return nil, denied
			}
			pending = command
			response.Status, response.Proposal = "confirmation", pending
			response.Text = "确认后将删除「" + c.Name + "」和全部日程，共享成员也会失去访问权限。"
		case op == "import.commit" || op == "import.undo":
			var preview map[string]any
			if e = json.Unmarshal(command, &preview); e != nil {
				return nil, e
			}
			if op == "import.commit" {
				preview["op"] = "import.preview"
			} else {
				preview["op"] = "import.undoPreview"
				delete(preview, "operationId")
			}
			raw, _ := json.Marshal(preview)
			response.Result, e = execute(ctx, tx, access{user: u}, raw)
			if e != nil {
				return nil, e
			}
			var result struct {
				Code string
				Rows []struct{ BaseVersion int64 }
			}
			if e = json.Unmarshal(response.Result, &result); e != nil {
				return nil, e
			}
			response.Text = "请核对预览结果。"
			if result.Code == "ok" {
				if op == "import.commit" {
					rows := preview["batch"].(map[string]any)["rows"].([]any)
					for i, row := range rows {
						fields := row.(map[string]any)
						if _, given := fields["baseVersion"]; !given {
							fields["baseVersion"] = result.Rows[i].BaseVersion
						}
					}
				}
				preview["op"] = op
				var orig struct {
					OperationID string `json:"operationId"`
				}
				json.Unmarshal(command, &orig)
				preview["operationId"] = orig.OperationID
				pending, e = json.Marshal(preview)
				if e != nil {
					return nil, e
				}
				response.Status = "confirmation"
				response.Proposal = pending
			}
		case len(t.scope) > 1 && op != "calendar.detail" && op != "import.preview" && op != "import.undoPreview":
			pending = command
			response.Status, response.Proposal = "confirmation", pending
			response.Text = "请确认对「" + c.Name + "」的操作。"
		case strings.HasPrefix(op, "calendar.") || op == "display.update":
			var m management
			if e = json.Unmarshal(command, &m); e != nil {
				return nil, invalid("管理参数无效")
			}
			v, e := a.scopedManage(tx, access{user: u}, m)
			if e != nil {
				return nil, e
			}
			response.Result, e = json.Marshal(v)
			if e != nil {
				return nil, e
			}
			response.Text = "已取得日历操作回执。"
		default:
			response.Result, e = execute(ctx, tx, access{user: u}, command)
			if e != nil {
				return nil, e
			}
			response.Text = "已取得日程操作回执。"
		}
		body, e := json.Marshal(response)
		if e != nil {
			return nil, e
		}
		_, e = tx.Exec("INSERT INTO ai_turns VALUES(?,?,?,?,?,?,?)", u.ID, t.input.OperationID, t.fingerprint, c.ID, t.proof, body, pending)
		return response, e
	})
}
func (a *App) confirm(w http.ResponseWriter, r *http.Request) (any, error) {
	var in struct {
		OperationID string `json:"operationId"`
	}
	if e := input(r, &in); e != nil {
		return nil, e
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		u, e := who(tx, r)
		if e != nil {
			return nil, e
		}
		var calendarID, role string
		var body, pending []byte
		e = tx.QueryRow("SELECT calendar_id,role,reply,pending FROM ai_turns WHERE user_id=? AND op_id=?", u.ID, in.OperationID).Scan(&calendarID, &role, &body, &pending)
		if errors.Is(e, sql.ErrNoRows) {
			// Deleting the calendar removes its previous conversations and proposals.
			var found bool
			if err := tx.QueryRow("SELECT EXISTS(SELECT 1 FROM deleted_calendars WHERE confirmed_by=? AND chat_operation=?)", u.ID, in.OperationID).Scan(&found); err != nil {
				return nil, err
			}
			if !found {
				return nil, missing
			}
			ok, err := a.aiAllowed(tx, u)
			if err != nil {
				return nil, err
			}
			if !ok {
				return nil, denied
			}
			return deletionReply(in.OperationID), nil
		}
		if e != nil {
			return nil, e
		}
		_, c, e := a.chatIdentity(tx, r, calendarID)
		if e != nil {
			return nil, e
		}
		if e = verifyRoles(tx, u.ID, c, role); e != nil {
			return nil, e
		}
		if pending == nil {
			return json.RawMessage(body), nil
		}
		var proposal management
		if json.Unmarshal(pending, &proposal) == nil && proposal.Op == "calendar.delete" {
			if _, err := a.scopedManage(tx, access{user: u}, proposal); err != nil {
				return nil, err
			}
			_, err := tx.Exec("UPDATE deleted_calendars SET confirmed_by=?,chat_operation=? WHERE id=?", u.ID, in.OperationID, calendarID)
			return deletionReply(in.OperationID), err
		}
		var result json.RawMessage
		if strings.HasPrefix(proposal.Op, "calendar.") || proposal.Op == "display.update" {
			v, err := a.scopedManage(tx, access{user: u}, proposal)
			if err != nil {
				return nil, err
			}
			result, e = json.Marshal(v)
		} else {
			result, e = execute(r.Context(), tx, access{user: u}, pending)
		}
		if e != nil {
			return nil, e
		}
		var reply chatReply
		if e = json.Unmarshal(body, &reply); e != nil {
			return nil, e
		}
		reply.Status = "completed"
		reply.Text = "已取得操作回执。"
		reply.Result = result
		reply.Proposal = nil
		body, e = json.Marshal(reply)
		if e != nil {
			return nil, e
		}
		_, e = tx.Exec("UPDATE ai_turns SET reply=?,pending=NULL WHERE user_id=? AND op_id=?", body, u.ID, in.OperationID)
		return reply, e
	})
}

func deletionReply(operation string) chatReply {
	return chatReply{OperationID: operation, Status: "completed", Text: "日历已删除", Op: "calendar.delete", Result: json.RawMessage(`{"ok":true}`)}
}
