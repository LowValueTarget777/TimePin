package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestChatCalendarSelection(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "scope.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var target string
	window := obj{"start": "2026-11-20", "end": "2026-11-22", "zone": "UTC"}
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Messages []modelMessage }
		if json.NewDecoder(r.Body).Decode(&req) != nil {
			t.Error("request")
			return
		}
		last := req.Messages[len(req.Messages)-1]
		answer := obj{"role": "assistant"}
		if last.Role == "tool" {
			var result struct {
				Calendars []struct {
					CalendarID string
					Result     struct{ Occurrences []any }
				}
			}
			if json.Unmarshal([]byte(last.Content), &result) != nil || len(result.Calendars) != 2 || len(result.Calendars[0].Result.Occurrences) != 1 || len(result.Calendars[1].Result.Occurrences) != 1 {
				t.Error("wrong query scope", last.Content)
			}
			answer["content"] = "两本日历各有一个班次"
		} else {
			name := "event_query"
			args := obj{"window": window}
			if last.Content != "read" && last.Content != "parallel" {
				name = "event_replace"
				args = obj{"eventId": "shift", "baseVersion": 1, "event": obj{"title": "晚班", "span": obj{"kind": "date", "start": "2026-11-21", "end": "2026-11-22"}}}
				if last.Content != "ambiguous" {
					args["calendarId"] = target
				}
			}
			b, _ := json.Marshal(args)
			answer["tool_calls"] = []any{obj{"id": "tool", "type": "function", "function": obj{"name": name, "arguments": string(b)}}}
			if last.Content == "parallel" {
				answer["tool_calls"] = append(answer["tool_calls"].([]any), obj{"id": "tool2", "type": "function", "function": obj{"name": "event_query", "arguments": string(b)}})
			}
		}
		json.NewEncoder(w).Encode(obj{"choices": []any{obj{"message": answer}}})
	}))
	defer model.Close()
	app := NewApp(db, true, false)
	app.Model = ModelConfig{BaseURL: model.URL, Name: "qa", active: true}
	site := httptest.NewServer(app.Handler())
	defer site.Close()
	h := apiTest{url: site.URL, t: t}
	a, b := newClient(), newClient()
	h.send(a, "register", obj{"name": "Owner", "password": "scope-test-password"}, 200)
	h.send(b, "register", obj{"name": "Other", "password": "scope-test-password"}, 200)
	state := h.send(a, "state", nil, 200)
	first := state["calendars"].([]any)[0].(obj)["id"].(string)
	second := h.send(b, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"].(string)
	third := h.manage(a, "calendar.create", obj{"name": "Excluded", "color": "#222222"}, 200)["id"].(string)
	h.manage(b, "calendar.grant", obj{"target": second, "subjectKind": "user", "subject": "Owner", "role": "reader"}, 200)
	for _, cal := range []string{first, second, third} {
		actor := a
		if cal == second {
			actor = b
		}
		h.send(actor, "commands", obj{"v": 1, "calendarId": cal, "op": "event.create", "eventId": "shift", "operationId": "seed", "event": obj{"title": "早班", "span": obj{"kind": "date", "start": "2026-11-20", "end": "2026-11-21"}}}, 200)
	}
	if _, err := db.Exec("INSERT INTO ai_members VALUES(?)", state["user"].(obj)["id"]); err != nil {
		t.Fatal(err)
	}
	call := func(msg string, status int) obj {
		return h.send(a, "ai/chat", obj{"calendarIds": []string{first, second}, "operationId": msg, "message": msg, "zone": "UTC"}, status)
	}
	if call("read", 200)["text"] != "两本日历各有一个班次" {
		t.Fatal("aggregate")
	}
	if r := call("parallel", 200); len(r["result"].(obj)["queries"].([]any)) != 2 {
		t.Fatal("parallel query scope")
	}
	if call("ambiguous", 200)["op"] != nil {
		t.Fatal("ambiguous edit")
	}
	target = third
	call("outside", 403)
	target = second
	call("reader", 403)
	target = first
	if call("edit", 200)["status"] != "confirmation" {
		t.Fatal("expected target preview")
	}
	h.manage(b, "calendar.grant", obj{"target": second, "subjectKind": "user", "subject": "Owner", "role": "remove"}, 200)
	call("read", 403)
	h.send(a, "ai/confirm", obj{"operationId": "edit"}, 403)
	h.send(a, "ai/query", obj{"calendarIds": []string{first, second}, "window": window}, 403)
	h.manage(b, "calendar.grant", obj{"target": second, "subjectKind": "user", "subject": "Owner", "role": "reader"}, 200)
	h.send(a, "ai/confirm", obj{"operationId": "edit"}, 200)
	for _, cal := range []string{first, second, third} {
		rec := h.send(a, "commands", obj{"v": 1, "op": "event.get", "calendarId": cal, "eventId": "shift"}, 200)["record"].(obj)
		expected := float64(1)
		if cal == first {
			expected = 2
		}
		if rec["version"] != expected {
			t.Fatal("wrong calendar modified", cal)
		}
	}
}
