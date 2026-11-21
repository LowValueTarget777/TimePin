package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"timepin/internal/client"
)

func TestAllCalendarToken(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "scope.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	srv := httptest.NewServer(NewApp(db, true, false).Handler())
	defer srv.Close()
	h := apiTest{url: srv.URL, t: t}
	owner, member := newClient(), newClient()
	for name, c := range map[string]*http.Client{"owner": owner, "member": member} {
		h.send(c, "register", obj{"name": name, "password": "only-for-test-1234"}, 200)
	}
	shared := h.send(owner, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	own := h.send(member, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	key := strings.Repeat("d", 64)
	token := h.manage(member, "token.create", obj{"name": "all", "target": "*", "token": key, "role": "editor"}, 200)["id"]
	c, err := client.New(srv.URL, key, filepath.Join(t.TempDir(), "client"))
	if err != nil {
		t.Fatal(err)
	}
	call := func(op string, args obj, fail bool) obj {
		t.Helper()
		r, err := c.Call(context.Background(), op, args)
		if err != nil || r.Failed != fail {
			t.Fatalf("%s failed=%v, error=%v, reply=%s", op, r.Failed, err, r.Body)
		}
		var out obj
		if err := json.Unmarshal(r.Body, &out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	state := func() map[string]string {
		out := map[string]string{}
		for _, item := range call("state", nil, false)["calendars"].([]any) {
			cal := item.(obj)
			out[cal["id"].(string)] = cal["role"].(string)
		}
		return out
	}
	if list := state(); len(list) != 1 || list[own.(string)] != "editor" {
		t.Fatal(list)
	}
	query := obj{"calendarId": shared, "window": obj{"start": "2026-11-01", "end": "2026-12-01", "zone": "UTC"}}
	call("event.query", query, true)
	grant := func(role string) {
		h.manage(owner, "calendar.grant", obj{"target": shared, "subjectKind": "user", "subject": "member", "role": role}, 200)
	}
	grant("editor")
	event := obj{"calendarId": shared, "operationId": "create", "eventId": "shift", "event": obj{"title": "班", "span": obj{"kind": "date", "start": "2026-11-19", "end": "2026-11-20"}}}
	call("event.create", event, false)
	grant("reader")
	if list := state(); list[shared.(string)] != "reader" {
		t.Fatal(list)
	}
	call("event.query", query, false)
	call("event.delete", obj{"calendarId": shared, "eventId": "shift", "baseVersion": 1, "operationId": "delete"}, true)
	call("calendar.detail", obj{"target": own}, true)
	call("display.detail", nil, true)
	call("token.list", nil, true)
	grant("remove")
	call("event.query", query, true)
	if list := state(); len(list) != 1 {
		t.Fatal(list)
	}
	team := h.manage(owner, "group.create", obj{"name": "Team"}, 200)["id"]
	groupCal := h.manage(owner, "calendar.create", obj{"team": team, "name": "共同日历", "color": "#112233"}, 200)["id"]
	h.manage(owner, "group.member", obj{"target": team, "subject": "member", "role": "member"}, 200)
	if list := state(); list[groupCal.(string)] != "reader" {
		t.Fatal(list)
	}
	h.manage(member, "token.revoke", obj{"target": token}, 200)
	call("state", nil, true)
}

func TestV7TokenMigration(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, migration := range []string{schema, schemaV2, schemaV3, schemaV4, schemaV5, schemaV6, schemaV7} {
		if _, err = old.Exec(migration); err != nil {
			t.Fatal(err)
		}
	}
	_, err = old.Exec("INSERT INTO users VALUES('u','user',x'01',x'01'); INSERT INTO calendars VALUES('c','Calendar','#123456','u',NULL); INSERT INTO api_tokens VALUES('old',zeroblob(32),'u','c','bot','reader',12345)")
	if err != nil {
		t.Fatal(err)
	}
	old.Close()
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var kept int
	err = db.QueryRow("SELECT count(*) FROM api_tokens WHERE id='old' AND digest=zeroblob(32) AND user_id='u' AND calendar_id='c' AND label='bot' AND role='reader' AND expires=12345").Scan(&kept)
	if err != nil || kept != 1 {
		t.Fatal("old token changed", err)
	}
}
