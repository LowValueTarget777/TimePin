package server

import (
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"testing"
)

func TestMobilePublication(t *testing.T) {
	for _, prefix := range []string{"mobile-", "system-"} {
		t.Run(prefix, func(t *testing.T) {
			testCalendarPublication(t, prefix+"182fc528-6398-4e1d-8d51-38263a14714f")
		})
	}
}

func testCalendarPublication(t *testing.T, cal string) {
	db, err := Open(filepath.Join(t.TempDir(), "nas.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	srv := httptest.NewServer(NewApp(db, true, false).Handler())
	defer srv.Close()
	h := apiTest{srv.URL, t}
	phone, other := newClient(), newClient()
	for i, c := range []string{"phone", "other"} {
		client := phone
		if i == 1 {
			client = other
		}
		h.send(client, "register", obj{"name": c, "password": "synthetic-phone-qa"}, 200)
	}
	row := obj{"id": "imp-before", "version": 3, "sourceVersion": 2, "event": obj{"title": "夜班", "span": obj{"kind": "date", "start": "2026-11-19", "end": "2026-11-20"}}}
	publish := obj{"op": "calendar.publish", "target": cal, "operationId": "publish-1", "name": "手机班表", "color": "#337755", "records": []any{row, row}}
	h.send(phone, "manage", publish, 400)
	var count int
	if err = db.QueryRow("SELECT count(*) FROM calendars WHERE id=?", cal).Scan(&count); err != nil || count != 0 {
		t.Fatal("partial publication", count, err)
	}
	publish["records"] = []any{row}
	first := h.send(phone, "manage", publish, 200)
	if first["count"] != float64(1) || !reflect.DeepEqual(first, h.send(phone, "manage", publish, 200)) {
		t.Fatal(first)
	}
	h.send(other, "manage", publish, 409)
	h.send(other, "sync/pull", obj{"calendarId": cal}, 403)
	p := h.send(phone, "sync/pull", obj{"calendarId": cal, "sources": true}, 200)
	record := p["changes"].([]any)[0].(obj)
	if p["sources"].(obj)["imp-before"] != float64(2) || record["version"] != float64(3) || record["id"] != "imp-before" {
		t.Fatal(p)
	}
	if h.send(phone, "sync/pull", obj{"calendarId": cal}, 200)["sources"] != nil {
		t.Fatal("changed v1.2 response")
	}
	publish["name"] = "different"
	h.send(phone, "manage", publish, 409)
}
