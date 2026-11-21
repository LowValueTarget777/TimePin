package server

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"timepin/internal/client"
)

func TestAdaptersAndSharedModel(t *testing.T) {
	db, e := Open(filepath.Join(t.TempDir(), "calendar.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	var calls atomic.Int32
	var proposal any
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/chat/completions" || r.Header.Get("Authorization") != "Bearer test-model-key" {
			t.Error("model transport")
		}
		var req struct {
			Model    string
			Tools    []any
			Messages []modelMessage
		}
		if json.NewDecoder(r.Body).Decode(&req) != nil || len(req.Tools) == 0 || req.Model != "compatible-test" {
			t.Error("model request")
		}
		b, _ := json.Marshal(proposal)
		msg := obj{"role": "assistant", "tool_calls": []any{obj{"id": "call-1", "type": "function", "function": obj{"name": "import_commit", "arguments": string(b)}}}}
		switch req.Messages[len(req.Messages)-1].Content {
		case "两个新增":
			calls := []any{}
			for _, title := range []string{"A", "B"} {
				args, _ := json.Marshal(obj{"event": obj{"title": title, "span": obj{"kind": "date", "start": "2026-11-19", "end": "2026-11-20"}}})
				calls = append(calls, obj{"id": title, "type": "function", "function": obj{"name": "event_create", "arguments": string(args)}})
			}
			msg["tool_calls"] = calls
		case "管理参数缺失":
			msg["tool_calls"] = []any{obj{"id": "bad", "type": "function", "function": obj{"name": "calendar_share", "arguments": "{}"}}}
		}
		json.NewEncoder(w).Encode(obj{"choices": []any{obj{"message": msg}}})
	}))
	defer model.Close()
	a := NewApp(db, true, false)
	a.Model = ModelConfig{BaseURL: model.URL, Name: "compatible-test", Key: "test-model-key", active: true}
	s := httptest.NewServer(a.Handler())
	defer s.Close()
	h := apiTest{url: s.URL, t: t}
	alice, bob := newClient(), newClient()
	pw := "only-for-synthetic-test"
	u := h.send(alice, "register", obj{"name": "Alice", "password": pw}, 200)
	h.send(bob, "register", obj{"name": "Bob", "password": pw}, 200)
	root := newClient()
	if e := a.SetConsoleAccount(context.Background(), "operator", pw); e != nil {
		t.Fatal(e)
	}
	h.send(root, "admin/login", obj{"name": "operator", "password": pw}, 200)
	cal := h.send(alice, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	h.manage(alice, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "Bob", "role": "editor"}, 200)
	key := strings.Repeat("a", 64)
	keyID := h.manage(alice, "token.create", obj{"name": "CLI", "target": cal, "role": "editor", "token": key}, 200)["id"]
	c, e := client.New(s.URL, key, filepath.Join(t.TempDir(), "session"))
	if e != nil {
		t.Fatal(e)
	}
	call := func(name string, p obj, wantFail bool) obj {
		t.Helper()
		r, e := c.Call(context.Background(), name, p)
		if e != nil || r.Failed != wantFail {
			t.Fatalf("%s failed=%v err=%v body=%s", name, r.Failed, e, r.Body)
		}
		var o obj
		json.Unmarshal(r.Body, &o)
		return o
	}
	rows := make([]any, 30)
	for i := range rows {
		rows[i] = obj{"externalId": fmt.Sprintf("s%d", i), "event": obj{"title": fmt.Sprintf("班 %02d", i), "span": obj{"kind": "date", "start": "2026-11-19", "end": "2026-11-20"}}}
	}
	batch := obj{"source": "shift", "rows": rows}
	args := func(op string) obj { return obj{"operationId": op, "calendarId": cal, "batch": batch} }
	pre := call("import.preview", args("batch1"), false)
	if len(pre["rows"].([]any)) != 30 {
		t.Fatal("preview")
	}
	first := call("import.commit", args("batch1"), false)
	rows[0].(obj)["event"].(obj)["title"] = "新班次"
	rev := call("import.commit", args("batch2"), false)
	if !reflect.DeepEqual(rev, call("import.commit", args("batch2"), false)) {
		t.Fatal("lost idempotency")
	}
	for i, row := range rev["rows"].([]any) {
		want := "unchanged"
		if i == 0 {
			want = "updated"
		}
		if row.(obj)["outcome"] != want {
			t.Fatal(row)
		}
	}
	changed := first["rows"].([]any)[0].(obj)["eventId"]
	single := obj{"calendarId": cal, "eventId": changed}
	web := h.send(alice, "commands", obj{"v": 1, "op": "event.get", "calendarId": cal, "eventId": changed}, 200)
	fromCLI := call("event.get", single, false)
	if !reflect.DeepEqual(web, fromCLI) {
		t.Fatal("HTTP/CLI parity")
	}
	var rpc bytes.Buffer
	input := "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n"
	mcp, _ := json.Marshal(obj{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": obj{"name": "event_get", "arguments": obj{"calendarId": cal, "eventId": changed}}})
	if e := client.ServeMCP(context.Background(), c, strings.NewReader(input+string(mcp)+"\n"), &rpc); e != nil {
		t.Fatal(e)
	}
	var response struct {
		Result struct{ Content []struct{ Text string } }
	}
	lines := strings.Split(strings.TrimSpace(rpc.String()), "\n")
	if len(lines) != 2 {
		t.Fatal("rpc response count")
	}
	json.Unmarshal([]byte(lines[1]), &response)
	var viaMCP obj
	if len(response.Result.Content) != 1 || json.Unmarshal([]byte(response.Result.Content[0].Text), &viaMCP) != nil || !reflect.DeepEqual(viaMCP, web) {
		t.Fatal("MCP parity")
	}
	h.send(bob, "commands", obj{"v": 1, "op": "event.replace", "calendarId": cal, "eventId": changed, "operationId": "human", "baseVersion": 2, "event": obj{"title": "同事手改", "span": obj{"kind": "date", "start": "2026-11-19", "end": "2026-11-20"}}}, 200)
	undo := call("import.undo", obj{"calendarId": cal, "operationId": "undo-first", "batchId": first["batchId"]}, false)
	var removed, kept int
	for _, v := range undo["undo"].([]any) {
		switch v.(obj)["outcome"] {
		case "removed":
			removed++
		case "changed":
			kept++
		}
	}
	if removed != 29 || kept != 1 {
		t.Fatal("undo lost another member's revision")
	}
	if calls.Load() != 0 {
		t.Fatal("manual or structured traffic invoked the model")
	}
	chat := obj{"operationId": "chat-01", "calendarId": cal, "zone": "Asia/Shanghai", "message": "导入晚班"}
	proposal = obj{"batch": obj{"source": "from-ai", "rows": []any{rows[1]}}}
	h.send(alice, "ai/chat", chat, 403)
	h.manage(bob, "model.grant", obj{"subject": "Bob", "enabled": true}, 403)
	h.console(root, "model.grant", obj{"subject": "Alice", "enabled": true}, 200)
	plan := h.send(alice, "ai/chat", chat, 200)
	if plan["status"] != "confirmation" || calls.Load() != 1 {
		t.Fatal(plan)
	}
	if !reflect.DeepEqual(plan, h.send(alice, "ai/chat", chat, 200)) || calls.Load() != 1 {
		t.Fatal("model retry invoked twice")
	}
	id := plan["result"].(obj)["rows"].([]any)[0].(obj)["eventId"]
	if got := call("event.get", obj{"calendarId": cal, "eventId": id}, true); got["code"] != "not_found" {
		t.Fatal("preview wrote data")
	}
	h.send(bob, "ai/confirm", obj{"operationId": "chat-01"}, 404)
	h.console(root, "model.grant", obj{"subject": "Alice", "enabled": false}, 200)
	h.send(alice, "ai/confirm", obj{"operationId": "chat-01"}, 403)
	h.console(root, "model.grant", obj{"subject": "Alice", "enabled": true}, 200)
	res := h.send(alice, "ai/confirm", obj{"operationId": "chat-01"}, 200)
	if res["status"] != "completed" || res["result"].(obj)["rows"].([]any)[0].(obj)["outcome"] != "created" || !reflect.DeepEqual(res, h.send(alice, "ai/confirm", obj{"operationId": "chat-01"}, 200)) || calls.Load() != 1 {
		t.Fatal("confirmation or replay")
	}
	if fromAI := call("event.get", obj{"calendarId": cal, "eventId": id}, false)["record"].(obj); fromAI["creatorId"] != u["id"] {
		t.Fatal("AI identity")
	}
	chat["operationId"] = "chat-multi"
	chat["message"] = "两个新增"
	multi := h.send(alice, "ai/chat", chat, 200)
	if multi["status"] != "confirmation" || len(multi["result"].(obj)["rows"].([]any)) != 2 {
		t.Fatal("multiple creates bypassed preview")
	}
	chat["operationId"] = "chat-bad"
	chat["message"] = "管理参数缺失"
	h.send(alice, "ai/chat", chat, 400)
	call("calendar.create", obj{"name": "unexpected", "color": "#112233", "operationId": "new", "target": cal}, true)
	call("group.create", obj{"name": "unexpected", "operationId": "new"}, true)
	call("model.grant", obj{"subject": "Bob", "enabled": true, "operationId": "no"}, true)
	// A token's role is a ceiling. Lowering the granting user's live role takes effect immediately.
	bobKey := strings.Repeat("b", 64)
	h.manage(bob, "token.create", obj{"target": cal, "role": "editor", "token": bobKey, "name": "Robot"}, 200)
	bobClient, e := client.New(s.URL, bobKey, filepath.Join(t.TempDir(), "robot"))
	if e != nil {
		t.Fatal(e)
	}
	h.manage(alice, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "Bob", "role": "reader"}, 200)
	limited, e := bobClient.Call(context.Background(), "import.undo", obj{"calendarId": cal, "operationId": "lowered", "batchId": first["batchId"]})
	if e != nil || !limited.Failed || !bytes.Contains(limited.Body, []byte("forbidden")) {
		t.Fatal("token kept revoked edit role")
	}
	h.manage(alice, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "Bob", "role": "remove"}, 200)
	limited, e = bobClient.Call(context.Background(), "state", nil)
	if e != nil || !limited.Failed {
		t.Fatal("token kept revoked calendar access")
	}
	h.manage(alice, "token.revoke", obj{"target": keyID}, 200)
	call("state", nil, true)
}
func TestPhaseTwoUpgrade(t *testing.T) {
	path := filepath.Join(t.TempDir(), "p2.db")
	db, e := sql.Open("sqlite", path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec(schema); e != nil {
		t.Fatal(e)
	}
	// A real phase-2 no-change batch has a receipt and no import_history rows.
	_, e = db.Exec("INSERT INTO teams VALUES('team','Team');INSERT INTO calendars VALUES('work','Roster','#123456',NULL,'team');INSERT INTO receipts VALUES('work','actor','old',zeroblob(32),'{}',?);", []byte(`{"code":"ok","batchId":"batch-before-upgrade","rows":[]}`))
	if e != nil {
		t.Fatal(e)
	}
	db.Close()
	db, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	var version, count int
	if e = db.QueryRow("pragma user_version").Scan(&version); e != nil || version != 11 {
		t.Fatal(version, e)
	}
	if e = db.QueryRow("SELECT COUNT(*) FROM import_batches WHERE calendar_id='work' AND id='batch-before-upgrade'").Scan(&count); e != nil || count != 1 {
		t.Fatal(count, e)
	}
}

func TestDeepSeekDefaultsAndCustomEndpoint(t *testing.T) {
	t.Setenv("TIMEPIN_AI_BASE_URL", "")
	t.Setenv("TIMEPIN_AI_MODEL", "")
	t.Setenv("TIMEPIN_AI_API_KEY", "")
	t.Setenv("DEEPSEEK_API_KEY", "")
	m, e := ModelFromEnv()
	if e != nil || m.Enabled() || m.BaseURL != deepSeekBase || m.Name != "deepseek-flash" {
		t.Fatal("unexpected unconfigured defaults")
	}
	t.Setenv("DEEPSEEK_API_KEY", "synthetic-deepseek-key")
	m, e = ModelFromEnv()
	if e != nil || !m.Enabled() || m.Key != "synthetic-deepseek-key" {
		t.Fatal("default credential routing")
	}
	t.Setenv("TIMEPIN_AI_BASE_URL", "http://127.0.0.1:1234/v1/")
	t.Setenv("TIMEPIN_AI_MODEL", "local-tool-model")
	m, e = ModelFromEnv()
	if e != nil || !m.Enabled() || m.Key != "" || m.Name != "local-tool-model" || m.BaseURL != "http://127.0.0.1:1234/v1" {
		t.Fatal("custom endpoint reused default provider credential")
	}
	t.Setenv("TIMEPIN_AI_API_KEY", "dedicated-test-key")
	m, e = ModelFromEnv()
	if e != nil || m.Key != "dedicated-test-key" {
		t.Fatal("custom credential routing")
	}
}
