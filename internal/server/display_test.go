package server

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestMonthPreferences(t *testing.T) {
	db, e := Open(filepath.Join(t.TempDir(), "qa.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	server := httptest.NewServer(NewApp(db, true, false).Handler())
	defer server.Close()
	h := apiTest{server.URL, t}
	alice, bob := newClient(), newClient()
	for name, c := range map[string]*http.Client{"alice": alice, "bob": bob} {
		h.send(c, "register", obj{"name": name, "password": "test-only-month"}, 200)
	}
	read := obj{"op": "display.detail"}
	h.send(newClient(), "manage", read, 401)
	if defaults := h.send(alice, "manage", read, 200); defaults["holidayRegion"] != "system" {
		t.Fatal(defaults)
	}
	prefs := obj{"weekStart": "monday", "weekNumbers": false, "lunar": true, "holidayRegion": "JP", "fontSize": 18}
	update := obj{"operationId": "month-1", "display": prefs}
	for i := 0; i < 2; i++ {
		if got := h.manage(alice, "display.update", update, 200); got["fontSize"] != 18.0 {
			t.Fatal(got)
		}
	}
	if got := h.send(alice, "manage", read, 200); got["holidayRegion"] != "JP" {
		t.Fatal(got)
	}
	if got := h.send(bob, "manage", read, 200); got["holidayRegion"] != "system" {
		t.Fatal(got)
	}
	prefs["lunarFormat"] = "numeric"
	if got := h.manage(alice, "display.update", obj{"display": prefs}, 200); got["lunarFormat"] != "numeric" {
		t.Fatal(got)
	}
	prefs["lunarFormat"] = "unknown"
	h.manage(alice, "display.update", obj{"display": prefs}, 400)
	delete(prefs, "lunarFormat")
	prefs["fontSize"] = 16
	h.manage(alice, "display.update", update, 409)
	prefs["holidayRegion"] = "ZZ"
	h.manage(alice, "display.update", update, 400)
}
