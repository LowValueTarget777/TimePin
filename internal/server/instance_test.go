package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstanceAdministration(t *testing.T) {
	db, e := Open(filepath.Join(t.TempDir(), "test.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	a := NewApp(db, true, false)
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	h := apiTest{srv.URL, t}
	root, member := newClient(), newClient()
	create := func(s string) obj { return obj{"name": s, "password": "only-for-test-1234"} }
	owner := newClient()
	h.send(owner, "register", create("owner"), 200)
	if err := a.SetConsoleAccount(context.Background(), "operator", "different-console-password"); err != nil {
		t.Fatal(err)
	}
	auth := obj{"name": "operator", "password": "different-console-password"}
	h.send(root, "admin/login", create("owner"), 401)
	h.send(root, "login", auth, 401)
	h.send(root, "admin/login", auth, 200)
	h.send(owner, "admin/state", nil, 401)
	h.send(owner, "manage", obj{"op": "instance.detail"}, 403)
	h.send(root, "admin/manage", obj{"op": "display.detail"}, 403)
	if h.send(root, "state", nil, 200)["user"] != nil {
		t.Fatal("operator became a calendar user")
	}
	h.send(owner, "admin/login", auth, 200)
	h.send(owner, "logout", obj{}, 200)
	h.send(owner, "admin/state", nil, 200)
	h.send(owner, "login", create("owner"), 200)
	h.send(owner, "admin/logout", obj{}, 200)
	if h.send(owner, "state", nil, 200)["user"] == nil {
		t.Fatal("console logout ended personal session")
	}
	bob := h.send(member, "register", create("member"), 200)
	h.send(member, "manage", obj{"op": "instance.detail"}, 403)
	h.manage(member, "instance.registration", obj{"enabled": false}, 403)
	h.send(member, "admin/manage", obj{"op": "instance.detail"}, 401)
	h.console(root, "instance.registration", obj{"enabled": false}, 200)
	h.send(newClient(), "register", create("closed"), 403)
	h.console(root, "instance.model", obj{"enabled": true, "baseUrl": "http://localhost:18799/v1", "model": "qa", "key": "instance-test-secret"}, 200)
	detail := h.send(root, "admin/manage", obj{"op": "instance.detail"}, 200)
	raw, _ := json.Marshal(detail)
	if strings.Contains(string(raw), "instance-test-secret") || detail["model"].(obj)["hasKey"] != true {
		t.Fatal("model key projection")
	}
	ai := func() bool { return h.send(member, "state", nil, 200)["ai"].(obj)["enabled"].(bool) }
	if ai() {
		t.Fatal("new configuration granted a member")
	}
	h.console(root, "model.grant", obj{"subject": "member", "enabled": true}, 200)
	if !ai() {
		t.Fatal("grant did not apply")
	}
	h.console(root, "instance.model", obj{"enabled": false, "baseUrl": "http://localhost:18799/v1", "model": "qa"}, 200)
	if ai() {
		t.Fatal("disabled model still available")
	}
	h.console(root, "instance.model", obj{"enabled": true, "baseUrl": "http://localhost:18800/v1", "model": "qa"}, 200)
	detail = h.send(root, "admin/manage", obj{"op": "instance.detail"}, 200)
	if detail["model"].(obj)["hasKey"] != false {
		t.Fatal("old provider key retained")
	}
	key := strings.Repeat("a", 64)
	cal := h.send(member, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	h.manage(member, "token.create", obj{"name": "qa", "target": cal, "role": "reader", "token": key}, 200)
	h.console(root, "instance.account", obj{"target": bob["id"], "enabled": false}, 200)
	if h.send(member, "state", nil, 200)["user"] != nil {
		t.Fatal("session survived suspension")
	}
	var count int
	if e = db.QueryRow("SELECT count(*) FROM api_tokens WHERE user_id=?", bob["id"]).Scan(&count); e != nil || count != 0 {
		t.Fatal("scoped token survived")
	}
	h.send(member, "login", create("member"), 401)
	h.console(root, "instance.account", obj{"target": bob["id"], "enabled": true}, 200)
	h.send(member, "login", create("member"), 200)
	if err := a.SetConsoleAccount(context.Background(), "operator", "rotated-console-password"); err != nil {
		t.Fatal(err)
	}
	h.send(root, "admin/state", nil, 401)
	h.send(root, "admin/login", auth, 401)
}

func (h *apiTest) console(c *http.Client, op string, data obj, status int) obj {
	h.t.Helper()
	data["op"] = op
	if data["operationId"] == nil {
		data["operationId"] = id("test")
	}
	return h.send(c, "admin/manage", data, status)
}
