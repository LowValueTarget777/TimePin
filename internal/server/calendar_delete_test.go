package server

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestDeleteCalendar(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "calendar.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	site := httptest.NewServer(NewApp(db, true, false).Handler())
	defer site.Close()
	h := apiTest{url: site.URL, t: t}
	a, b := newClient(), newClient()
	for name, c := range map[string]*http.Client{"Owner": a, "Other": b} {
		h.send(c, "register", obj{"name": name, "password": "test-delete-credentials"}, 200)
	}
	group := h.manage(a, "group.create", obj{"name": "Group"}, 200)["id"]
	book := h.manage(a, "calendar.create", obj{"name": "Shared", "color": "#113355", "team": group}, 200)["id"]
	h.manage(a, "calendar.grant", obj{"target": book, "subjectKind": "user", "subject": "Other", "role": "manager"}, 200)
	h.manage(b, "calendar.delete", obj{"target": book}, 403)
	h.manage(a, "group.member", obj{"target": group, "subject": "Other", "role": "admin"}, 200)
	for k, target := range []any{book, "*"} {
		h.manage(a, "token.create", obj{"target": target, "token": strings.Repeat([]string{"a", "b"}[k], 64), "name": "fixture", "role": "manager"}, 200)
	}
	h.send(a, "commands", obj{"v": 1, "op": "event.create", "calendarId": book, "eventId": "sample", "operationId": "insert", "event": obj{"title": "QA", "span": obj{"kind": "date", "start": "2026-01-02", "end": "2026-01-03"}}}, 200)
	// Simulate a failure after child rows have been removed.
	if _, err = db.Exec("CREATE TEMP TRIGGER delete_failure BEFORE DELETE ON calendars BEGIN SELECT RAISE(ABORT,'test rollback'); END"); err != nil {
		t.Fatal(err)
	}
	deletion := obj{"target": book, "operationId": "remove-fixture"}
	h.manage(b, "calendar.delete", deletion, 500)
	var n int
	if err = db.QueryRow("SELECT count(*) FROM events WHERE calendar_id=?", book).Scan(&n); err != nil || n != 1 {
		t.Fatal("partial deletion", n, err)
	}
	if _, err = db.Exec("DROP TRIGGER delete_failure"); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if v := h.manage(b, "calendar.delete", deletion, 200); v["ok"] != true {
			t.Fatal(v)
		}
	}
	if err = db.QueryRow("SELECT count(*) FROM api_tokens WHERE calendar_id IS NULL").Scan(&n); err != nil || n != 1 {
		t.Fatal("global token", n, err)
	}
	for _, tbl := range []string{"events", "receipts", "api_tokens", "grants"} {
		if err = db.QueryRow("SELECT count(*) FROM "+tbl+" WHERE calendar_id=?", book).Scan(&n); err != nil || n != 0 {
			t.Fatal(tbl, n, err)
		}
	}
	publish := obj{"target": "mobile-01234567-89ab-cdef-0123-456789abcdef", "name": "QA", "color": "#123456", "records": []any{}, "operationId": "new-mobile"}
	h.manage(a, "calendar.publish", publish, 200)
	h.manage(a, "calendar.delete", obj{"target": publish["target"]}, 200)
	h.manage(a, "calendar.publish", publish, 409)
	publish["operationId"] = "stale-device"
	h.manage(a, "calendar.publish", publish, 409)
}
