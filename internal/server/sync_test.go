package server

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestSyncPaginationAndAuthority(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	srv := httptest.NewServer(NewApp(db, true, false).Handler())
	defer srv.Close()
	h := apiTest{srv.URL, t}
	owner, peer := newClient(), newClient()
	for name, c := range map[string]*http.Client{"owner": owner, "peer": peer} {
		h.send(c, "register", obj{"name": name, "password": "test-only-passphrase"}, 200)
	}
	cal := h.send(owner, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	peerCal := h.send(peer, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	group := h.manage(owner, "group.create", obj{"name": "Roster"}, 200)["id"]
	h.manage(owner, "group.member", obj{"target": group, "subject": "peer", "role": "member"}, 200)
	h.manage(owner, "calendar.grant", obj{"target": cal, "subjectKind": "group", "subject": group, "role": "reader"}, 200)
	event := obj{"span": obj{"kind": "date", "start": "2026-11-01", "end": "2026-11-02"}, "title": "班"}
	command := func(name string, args obj) obj {
		args["op"], args["v"], args["calendarId"] = name, 1, cal
		if args["operationId"] == nil {
			args["operationId"] = id("op")
		}
		result := h.send(owner, "commands", args, 200)
		if result["code"] != "ok" {
			t.Fatal(result)
		}
		return result
	}
	for _, key := range []string{"old", "a", "b", "c"} {
		command("event.create", obj{"eventId": key, "event": event})
	}
	command("event.delete", obj{"eventId": "old", "baseVersion": 1})
	pull := func(c *http.Client, cal, cursor any, limit, status int) obj {
		data := obj{"calendarId": cal, "limit": limit}
		if cursor != nil {
			data["cursor"] = cursor
		}
		return h.send(c, "sync/pull", data, status)
	}
	first := pull(peer, cal, nil, 1, 200)
	if first["changes"].([]any)[0].(obj)["id"] != "a" || first["more"] != true || first["calendar"].(obj)["role"] != "reader" {
		t.Fatal(first)
	}
	c0 := first["cursor"]
	pull(owner, cal, c0, 1, 409)
	pull(peer, peerCal, c0, 1, 409)
	pull(peer, cal, strings.Repeat("\n", 64), 1, 409)
	pull(peer, cal, nil, 513, 400)
	pull(newClient(), cal, c0, 2, 401)
	// Delete an already delivered item and move an unread item twice between pages.
	command("event.delete", obj{"eventId": "a", "baseVersion": 1})
	command("event.replace", obj{"eventId": "b", "baseVersion": 1, "event": event})
	command("event.replace", obj{"eventId": "b", "baseVersion": 2, "event": event})
	replayed := pull(peer, cal, c0, 2, 200)
	if !reflect.DeepEqual(replayed, pull(peer, cal, c0, 2, 200)) {
		t.Fatal("page retry changed without writes")
	}
	changes := replayed["changes"].([]any)
	if changes[0].(obj)["id"] != "c" || !reflect.DeepEqual(changes[1], obj{"id": "a", "deleted": true, "version": float64(2)}) {
		t.Fatal(changes)
	}
	page := pull(peer, cal, replayed["cursor"], 2, 200)
	updates := page["changes"].([]any)
	if len(updates) != 1 || updates[0].(obj)["id"] != "b" || updates[0].(obj)["version"] != float64(3) || page["more"] != false {
		t.Fatal(page)
	}
	c1 := page["cursor"]
	world := obj{"v": 1, "op": "event.create", "operationId": "own", "calendarId": peerCal, "eventId": "private", "event": event}
	h.send(peer, "commands", world, 200)
	if idle := pull(peer, cal, c1, 1, 200); idle["cursor"] != c1 || len(idle["changes"].([]any)) != 0 || idle["calendar"] != nil {
		t.Fatal(idle)
	}
	// Replay, no-op import and undo use the same transactional change index.
	batch := obj{"source": "test", "rows": []any{obj{"externalId": "shift", "event": event}}}
	imported := command("import.commit", obj{"operationId": "batch", "batch": batch})
	created := pull(peer, cal, c1, 1, 200)
	if len(created["changes"].([]any)) != 1 {
		t.Fatal(created)
	}
	command("import.commit", obj{"operationId": "batch", "batch": batch})
	command("import.commit", obj{"batch": batch})
	if idle := pull(peer, cal, created["cursor"], 1, 200); idle["cursor"] != created["cursor"] {
		t.Fatal(idle)
	}
	command("import.undo", obj{"batchId": imported["batchId"]})
	if undo := pull(peer, cal, created["cursor"], 2, 200); undo["changes"].([]any)[0].(obj)["deleted"] != true {
		t.Fatal(undo)
	}
	h.manage(owner, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "peer", "role": "editor"}, 200)
	raised := pull(peer, cal, c1, 4, 200)
	h.manage(owner, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "peer", "role": "remove"}, 200)
	lowered := pull(peer, cal, raised["cursor"], 4, 200)
	if raised["calendar"].(obj)["role"] != "editor" || lowered["calendar"].(obj)["role"] != "reader" || len(lowered["changes"].([]any)) != 0 {
		t.Fatal("role metadata")
	}
	h.manage(owner, "group.member", obj{"target": group, "subject": "peer", "role": "remove"}, 200)
	for range 2 {
		if rev := pull(peer, cal, lowered["cursor"], 2, 200); !reflect.DeepEqual(rev, obj{"status": "revoked"}) {
			t.Fatal(rev)
		}
	}
	pull(peer, cal, nil, 1, 403)
	h.manage(owner, "calendar.grant", obj{"target": cal, "subjectKind": "user", "subject": "peer", "role": "reader"}, 200)
	if fresh := pull(peer, cal, nil, 10, 200); len(fresh["changes"].([]any)) != 2 {
		t.Fatal(fresh)
	}
}

func TestSyncUpgradeAndRecovery(t *testing.T) {
	path := filepath.Join(t.TempDir(), "p3.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	// Start with the previous release's schema, a live record and a tombstone.
	if _, err = db.Exec(schema + schemaV2); err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO users VALUES('owner','Owner',zeroblob(16),zeroblob(32));
		INSERT INTO calendars VALUES('work','Work','#123456','owner',NULL);
		INSERT INTO events VALUES('work','n',? ,0,1,'2026-01-01','2026-01-02'),
		('work','gone',?,0,0,'2026-01-01','2026-01-02')`,
		[]byte(`{"id":"n","calendarId":"work","creatorId":"owner","version":7,"deleted":false,"event":{"title":"legacy","span":{"kind":"date","start":"2026-01-01","end":"2026-01-02"}}}`),
		[]byte(`{"id":"gone","calendarId":"work","creatorId":"owner","version":2,"deleted":true,"event":{"title":"old","span":{"kind":"date","start":"2026-01-01","end":"2026-01-02"}}}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("INSERT INTO sessions VALUES(?,'owner',9999999999)", digest("old-session")); err != nil {
		t.Fatal(err)
	}
	db.Close()
	cursor := ""
	for pass := 0; pass < 3; pass++ {
		db, err = Open(path)
		if err != nil {
			t.Fatal(err)
		}
		defer db.Close()
		if pass == 2 {
			if _, err = db.Exec("UPDATE sync_state SET secret=randomblob(32)"); err != nil {
				t.Fatal(err)
			}
		}
		a := NewApp(db, false, false).Handler()
		body := func(token string) *httptest.ResponseRecorder {
			args := obj{"calendarId": "work"}
			if token != "" {
				args["cursor"] = token
			}
			raw, _ := json.Marshal(args)
			r := httptest.NewRequest("POST", "/api/v1/sync/pull", strings.NewReader(string(raw)))
			r.Header.Set("Content-Type", "application/json")
			r.AddCookie(&http.Cookie{Name: "timepin_session", Value: "old-session"})
			w := httptest.NewRecorder()
			a.ServeHTTP(w, r)
			return w
		}
		if pass == 2 {
			if body(cursor).Code != 409 {
				t.Fatal("restored instance accepted discarded history")
			}
			cursor = ""
		}
		w := body(cursor)
		var page pullPage
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &page) != nil {
			t.Fatal(w.Code)
		}
		if cursor == "" {
			if len(page.Changes) != 1 || !strings.Contains(string(page.Changes[0]), `"version":7`) {
				t.Fatal("migration lost live data or sent old tombstone")
			}
		} else if page.Cursor != cursor || len(page.Changes) != 0 {
			t.Fatal("restart invalidated cursor")
		}
		cursor = page.Cursor
		db.Close()
	}
}
