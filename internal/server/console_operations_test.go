package server

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConsoleMemberCredentials(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "credentials.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	a := NewApp(db, false, false)
	if err := a.SetConsoleAccount(context.Background(), "operator", "test-console-password"); err != nil {
		t.Fatal(err)
	}
	s := httptest.NewServer(a.Handler())
	defer s.Close()
	h := apiTest{s.URL, t}
	console, member, fresh := newClient(), newClient(), newClient()
	h.send(console, "admin/login", obj{"name": "operator", "password": "test-console-password"}, 200)
	create := obj{"name": "alice", "password": "original-credentials", "operationId": "create-once"}
	h.console(fresh, "instance.accountCreate", create, 401)
	u := h.console(console, "instance.accountCreate", create, 200)
	if again := h.console(console, "instance.accountCreate", create, 200); again["id"] != u["id"] {
		t.Fatal("create replay")
	}
	h.console(console, "instance.accountCreate", obj{"name": "ALICE", "password": "duplicate-password"}, 409)
	h.send(fresh, "register", obj{"name": "bob", "password": "outside-invite-only"}, 403)
	h.send(member, "login", obj{"name": "alice", "password": "original-credentials"}, 200)
	state := h.send(member, "state", nil, 200)
	cal := state["calendars"].([]any)[0].(obj)["id"]
	h.manage(member, "instance.accountPassword", obj{"target": u["id"], "password": "forbidden-password"}, 403)
	h.console(member, "instance.accountPassword", obj{"target": u["id"], "password": "forbidden-password"}, 401)
	h.console(console, "instance.accountRename", obj{"name": "a2", "target": u["id"]}, 200)
	h.send(fresh, "login", obj{"name": "alice", "password": "original-credentials"}, 401)
	h.send(fresh, "login", obj{"name": "a2", "password": "original-credentials"}, 200)
	h.manage(member, "token.create", obj{"target": cal, "role": "reader", "name": "test", "token": strings.Repeat("d", 64)}, 200)
	h.console(console, "instance.recovery", obj{"target": u["id"], "token": strings.Repeat("a", 64)}, 200)
	reset := obj{"target": u["id"], "password": "replacement-secret", "operationId": "reset-once"}
	h.console(console, "instance.accountPassword", reset, 200)
	for _, table := range []string{"api_tokens", "sessions", "account_recovery"} {
		var n int
		if e := db.QueryRow("SELECT count(*) FROM "+table+" WHERE user_id=?", u["id"]).Scan(&n); e != nil || n != 0 {
			t.Fatalf("revoke %s: %d %v", table, n, e)
		}
	}
	h.send(fresh, "login", obj{"name": "a2", "password": "original-credentials"}, 401)
	h.send(fresh, "login", obj{"name": "a2", "password": "replacement-secret"}, 200)
	h.console(console, "instance.accountPassword", reset, 200)
	reset["password"] = "changed-request-password"
	h.console(console, "instance.accountPassword", reset, 409)
	state = h.send(fresh, "state", nil, 200)
	if state["user"].(obj)["id"] != u["id"] || len(state["calendars"].([]any)) != 1 || state["calendars"].([]any)[0].(obj)["id"] != cal {
		t.Fatal("lost user, session or calendar")
	}
	h.send(fresh, "admin/state", nil, 401)
	if h.send(console, "state", nil, 200)["user"] != nil {
		t.Fatal("provisioning created a member session")
	}
}

func TestConsoleOperations(t *testing.T) {
	dir := t.TempDir()
	db, e := Open(filepath.Join(dir, "instance.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	a := NewApp(db, false, false)
	if e = a.SetConsoleAccount(context.Background(), "operator", "test-console-password"); e != nil {
		t.Fatal(e)
	}
	s := httptest.NewServer(a.Handler())
	defer s.Close()
	h := apiTest{s.URL, t}
	root, user, guest := newClient(), newClient(), newClient()
	h.send(root, "admin/login", obj{"name": "operator", "password": "test-console-password"}, 200)
	alice := h.send(user, "register", obj{"name": "alice", "password": "test-account-password"}, 200)
	read := func(op string, p obj) obj { p["op"] = op; return h.send(root, "admin/manage", p, 200) }
	list := func(section string, p obj) []any {
		p["section"] = section
		return read("instance.directory", p)["items"].([]any)
	}
	h.send(user, "admin/manage", obj{"op": "instance.overview"}, 401)
	h.send(user, "manage", obj{"op": "instance.directory", "section": "accounts"}, 403)
	code := strings.Repeat("b", 64)
	inv := h.console(root, "instance.invite", obj{"token": code, "operationId": "onboard"}, 200)
	if again := h.console(root, "instance.invite", obj{"token": code, "operationId": "onboard"}, 200); inv["id"] != again["id"] {
		t.Fatal("invite replay")
	}
	bob := h.send(guest, "register", obj{"name": "bob", "password": "test-member-password", "invite": code}, 200)
	h.send(newClient(), "register", obj{"name": "unexpected", "password": "test-member-password", "invite": code}, 404)
	team := h.console(root, "instance.groupCreate", obj{"name": "运营", "subject": "alice"}, 200)["id"]
	h.console(root, "instance.groupMember", obj{"target": team, "subject": "alice", "role": "remove"}, 400)
	h.console(root, "instance.groupMember", obj{"target": team, "subject": "bob", "role": "admin"}, 200)
	h.console(root, "instance.groupRename", obj{"target": team, "name": "排班组"}, 200)
	cal := h.console(root, "instance.calendarCreate", obj{"name": "值班", "color": "#347255", "subject": team, "subjectKind": "group"}, 200)["id"]
	owners := list("calendarOwners", obj{})
	if len(owners) != 3 {
		t.Fatalf("expected two members and one team: %v", owners)
	}
	for _, entry := range owners {
		owner := entry.(obj)
		items := list("calendars", obj{"subjectKind": owner["kind"], "target": owner["id"]})
		if len(items) != 1 || items[0].(obj)["owner"] != owner["name"] || owner["calendars"] != float64(1) {
			t.Fatalf("owner directory: %v %v", owner, items)
		}
	}
	if rows := list("calendars", obj{"subjectKind": "user", "target": team}); len(rows) != 0 {
		t.Fatal("wrong owner type")
	}
	h.console(root, "instance.calendarUpdate", obj{"target": cal, "name": "周末值班", "color": "#445566"}, 200)
	h.console(root, "instance.calendarGrant", obj{"target": cal, "subject": "bob", "subjectKind": "user", "role": "editor"}, 200)
	if len(list("members", obj{"target": team})) != 2 || len(list("grants", obj{"target": cal})) != 1 || len(list("calendars", obj{"search": "排班组"})) != 1 {
		t.Fatal("directory")
	}
	share := h.manage(user, "calendar.share", obj{"target": cal, "enabled": true}, 200)["token"].(string)
	rev := list("shares", obj{})[0].(obj)["revision"].(string)
	h.manage(user, "calendar.share", obj{"target": cal, "enabled": true}, 200)
	h.console(root, "instance.unshare", obj{"target": cal, "revision": rev}, 409)
	h.console(root, "instance.unshare", obj{"target": cal, "revision": list("shares", obj{})[0].(obj)["revision"]}, 200)
	if len(list("shares", obj{})) != 0 {
		t.Fatal("still shared")
	}
	invite := h.manage(user, "group.invite", obj{"target": team, "role": "member"}, 200)
	h.console(root, "instance.uninvite", obj{"target": invite["id"]}, 200)
	h.manage(guest, "invite.accept", obj{"token": invite["token"]}, 404)
	issueToken := func() string {
		return h.manage(user, "token.create", obj{"target": cal, "role": "reader", "name": "机器人", "token": strings.Repeat("c", 64)}, 200)["id"].(string)
	}
	apiToken := issueToken()
	h.console(root, "instance.revokeToken", obj{"target": apiToken}, 200)
	issueToken()
	h.console(root, "instance.signout", obj{"target": bob["id"]}, 200)
	if h.send(guest, "state", nil, 200)["user"] != nil {
		t.Fatal("session remained")
	}
	tempCode := strings.Repeat("a", 64)
	h.console(root, "instance.recovery", obj{"target": alice["id"], "token": tempCode}, 200)
	h.console(root, "instance.cancelRecovery", obj{"target": alice["id"]}, 200)
	reset := obj{"token": tempCode, "name": "alice", "password": "restored-user-password"}
	h.send(guest, "recover", reset, 400)
	h.console(root, "instance.recovery", obj{"target": alice["id"], "token": tempCode}, 200)
	if _, e = db.Exec("UPDATE account_recovery SET expires=0"); e != nil {
		t.Fatal(e)
	}
	h.send(guest, "recover", reset, 400)
	h.console(root, "instance.recovery", obj{"target": alice["id"], "token": tempCode}, 200)
	h.send(guest, "recover", reset, 200)
	h.send(guest, "recover", reset, 400)
	if h.send(user, "state", nil, 200)["user"] != nil || len(list("tokens", obj{})) != 0 {
		t.Fatal("credential revocation")
	}
	h.send(user, "login", obj{"name": "alice", "password": "restored-user-password"}, 200)
	overview := read("instance.overview", obj{})
	if overview["counts"].(obj)["accounts"] != float64(2) {
		t.Fatal("summary")
	}
	audit := read("instance.history", obj{})
	entries := audit["entries"].([]any)
	n := 0
	for _, v := range entries {
		if v.(obj)["action"] == "instance.invite" {
			n++
		}
	}
	if n != 1 {
		t.Fatal("incorrect audit deduplication")
	}
	raw, _ := json.Marshal([]any{overview, audit, list("accounts", obj{}), list("invites", obj{})})
	for _, s := range []string{code, tempCode, share, "restored-user-password"} {
		if strings.Contains(string(raw), s) {
			t.Fatal("credential disclosed")
		}
	}
	backup := h.console(root, "instance.backupCreate", obj{"operationId": "snapshot"}, 200)
	again := h.console(root, "instance.backupCreate", obj{"operationId": "snapshot"}, 200)
	if backup["file"] != again["file"] || len(read("instance.backups", obj{})["backups"].([]any)) != 1 {
		t.Fatal("backup replay")
	}
	h.console(root, "instance.backupCreate", obj{"operationId": "onboard"}, 409)
	name := filepath.Join(dir, "backups", backup["file"].(string))
	if _, e = os.Stat(name); e != nil {
		t.Fatal(e)
	}
	copyDB, e := readOnlyDB(name)
	if e != nil {
		t.Fatal(e)
	}
	defer copyDB.Close()
	var users int
	if e = copyDB.QueryRow("SELECT count(*) FROM users").Scan(&users); e != nil || users != 2 {
		t.Fatalf("backup verification %v count %d", e, users)
	}
}
