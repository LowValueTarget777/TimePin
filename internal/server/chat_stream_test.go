package server

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestStreamingCalendarLifecycle(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "stream.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	release := make(chan struct{})
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Stream   bool
			Messages []modelMessage
		}
		if json.NewDecoder(r.Body).Decode(&request) != nil || !request.Stream {
			t.Error("expected streaming request")
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		packet := func(d obj, finish any) {
			b, _ := json.Marshal(obj{"choices": []any{obj{"index": 0, "delta": d, "finish_reason": finish}}})
			fmt.Fprintf(w, "data: %s\r\n\r\n", b)
			w.(http.Flusher).Flush()
		}
		switch request.Messages[len(request.Messages)-1].Content {
		case "stream":
			packet(obj{"content": "首段", "reasoning_content": "private-thought"}, nil)
			select {
			case <-release:
			case <-r.Context().Done():
				return
			case <-time.After(5 * time.Second):
				t.Error("client never saw first delta")
				return
			}
			packet(obj{"content": "末段"}, "stop")
		case "reschedule":
			args := `{"eventId":"shift","baseVersion":1,"event":{"title":"晚班","span":{"kind":"date","start":"2026-11-21","end":"2026-11-22"}}}`
			packet(obj{"tool_calls": []any{obj{"index": 0, "id": "reschedule", "type": "function", "function": obj{"name": "event_replace", "arguments": args[:12]}}}}, nil)
			packet(obj{"tool_calls": []any{obj{"index": 0, "function": obj{"arguments": args[12:]}}}}, "tool_calls")
		case "delete", "broken":
			packet(obj{"tool_calls": []any{obj{"index": 0, "id": "tool", "type": "function", "function": obj{"name": "calendar_", "arguments": "{"}}}}, nil)
			packet(obj{"tool_calls": []any{obj{"index": 0, "function": obj{"name": "delete", "arguments": "}"}}}}, "tool_calls")
			if request.Messages[len(request.Messages)-1].Content == "broken" {
				return
			}
		}
		fmt.Fprint(w, "data: [DONE]\n\n")
	}))
	defer model.Close()
	app := NewApp(db, true, false)
	app.Model = ModelConfig{BaseURL: model.URL, Name: "fixture", active: true}
	site := httptest.NewServer(app.Handler())
	defer site.Close()
	h := apiTest{url: site.URL, t: t}
	owner, member := newClient(), newClient()
	pw := "temporary-test-only"
	h.send(owner, "register", obj{"name": "Owner", "password": pw}, 200)
	h.send(member, "register", obj{"name": "Member", "password": pw}, 200)
	state := h.send(owner, "state", nil, 200)
	user := state["user"].(obj)["id"]
	cal := state["calendars"].([]any)[0].(obj)["id"]
	h.manage(owner, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "Member", "role": "manager"}, 200)
	h.manage(member, "calendar.delete", obj{"target": cal}, 403)
	cursor := h.send(member, "sync/pull", obj{"calendarId": cal}, 200)["cursor"]
	link := h.manage(owner, "calendar.share", obj{"target": cal, "enabled": true}, 200)["token"].(string)
	invite := h.manage(owner, "calendar.invite", obj{"target": cal, "role": "reader"}, 200)["token"]
	post := func(c *http.Client, prompt string) *http.Response {
		t.Helper()
		b, _ := json.Marshal(obj{"operationId": prompt, "message": prompt, "zone": "UTC", "calendarId": cal})
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		t.Cleanup(cancel)
		req, _ := http.NewRequestWithContext(ctx, "POST", site.URL+"/api/v1/ai/chat", bytes.NewReader(b))
		req.Header.Set("Accept", "text/event-stream")
		req.Header.Set("Content-Type", "application/json")
		res, e := c.Do(req)
		if e != nil {
			t.Fatal(e)
		}
		return res
	}
	denied := post(owner, "stream")
	denied.Body.Close()
	if denied.StatusCode != 403 {
		t.Fatal("unauthorized stream")
	}
	if _, err = db.Exec("INSERT INTO ai_members VALUES(?)", user); err != nil {
		t.Fatal(err)
	}
	res := post(owner, "stream")
	reader := bufio.NewReader(res.Body)
	var first strings.Builder
	for !strings.Contains(first.String(), "首段") {
		line, e := reader.ReadString('\n')
		if e != nil {
			t.Fatal(e)
		}
		first.WriteString(line)
	}
	close(release)
	tail, _ := io.ReadAll(reader)
	res.Body.Close()
	if !strings.Contains(string(tail), "event: result") || !strings.Contains(string(tail), "首段末段") || strings.Contains(first.String()+string(tail), "private-thought") {
		t.Fatal("incomplete or private output")
	}
	body := func(prompt string) string {
		r := post(owner, prompt)
		defer r.Body.Close()
		b, _ := io.ReadAll(r.Body)
		return string(b)
	}
	if b := body("stream"); strings.Contains(b, "event: delta") || !strings.Contains(b, "首段末段") {
		t.Fatal("expected cached final result")
	}
	if b := body("broken"); !strings.Contains(b, "event: error") || strings.Contains(b, "event: result") {
		t.Fatal("truncated tool was accepted")
	}
	h.send(owner, "commands", obj{"v": 1, "op": "event.create", "calendarId": cal, "eventId": "shift", "operationId": "seed", "event": obj{"title": "早班", "span": obj{"kind": "date", "start": "2026-11-20", "end": "2026-11-21"}}}, 200)
	for i := 0; i < 2; i++ {
		if b := body("reschedule"); !strings.Contains(b, `"op":"event.replace"`) || !strings.Contains(b, `"code":"ok"`) {
			t.Fatal("reschedule", b)
		}
	}
	record := h.send(owner, "commands", obj{"v": 1, "op": "event.get", "calendarId": cal, "eventId": "shift"}, 200)["record"].(obj)
	if record["version"] != float64(2) || record["event"].(obj)["title"] != "晚班" {
		t.Fatal("reschedule did not apply exactly once")
	}
	if b := body("delete"); !strings.Contains(b, `"status":"confirmation"`) {
		t.Fatal("missing proposal", b)
	}
	h.send(member, "ai/confirm", obj{"operationId": "delete"}, 404)
	for i := 0; i < 2; i++ {
		if out := h.send(owner, "ai/confirm", obj{"operationId": "delete"}, 200); out["op"] != "calendar.delete" {
			t.Fatal(out)
		}
	}
	h.send(member, "public/"+link, nil, 404)
	h.manage(member, "invite.accept", obj{"token": invite}, 404)
	if out := h.send(member, "sync/pull", obj{"calendarId": cal, "cursor": cursor}, 200); out["status"] != "revoked" {
		t.Fatal(out)
	}
	if len(h.send(owner, "state", nil, 200)["calendars"].([]any)) != 0 {
		t.Fatal("deleted calendar still listed")
	}
}
