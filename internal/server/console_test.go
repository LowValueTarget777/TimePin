package server

import (
	"context"
	"database/sql"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func TestConsoleUpgradeAndSeparateWeb(t *testing.T) {
	path := filepath.Join(t.TempDir(), "upgrade.db")
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	for _, migration := range []string{schema, schemaV2, schemaV3, schemaV4, schemaV5} {
		if _, e := old.Exec(migration); e != nil {
			t.Fatal(e)
		}
	}
	if _, err = old.Exec("INSERT INTO users VALUES('old','owner',randomblob(16),randomblob(32)); INSERT INTO instance_admins VALUES('old'); INSERT INTO calendars VALUES('book','日历','#445566','old',NULL); INSERT INTO ai_members VALUES('old')"); err != nil {
		t.Fatal(err)
	}
	legacy := secret()
	if _, err = old.Exec("INSERT INTO sessions VALUES(?,'old',?)", digest(legacy), time.Now().Add(time.Hour).Unix()); err != nil {
		t.Fatal(err)
	}
	if err = old.Close(); err != nil {
		t.Fatal(err)
	}
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	a := NewApp(db, false, false)
	a.APIOnly = true
	s := httptest.NewServer(a.Handler())
	defer s.Close()
	h := apiTest{s.URL, t}
	c := newClient()
	req, _ := http.NewRequest("GET", s.URL+"/api/v1/state", nil)
	c.Jar.SetCookies(req.URL, []*http.Cookie{{Name: "timepin_session", Value: legacy, Path: "/"}})
	state := h.send(c, "state", nil, 200)
	if state["user"].(obj)["id"] != "old" || state["calendars"].([]any)[0].(obj)["id"] != "book" {
		t.Fatal("old session or calendar lost")
	}
	h.send(c, "manage", obj{"op": "instance.detail"}, 403)
	h.send(c, "admin/state", nil, 401)
	var grants int
	if err = db.QueryRow("SELECT count(*) FROM ai_members WHERE user_id='old'").Scan(&grants); err != nil || grants != 1 {
		t.Fatal("member grant lost", err)
	}
	for page, want := range map[string]int{"/": 404, "/login.html": 404, "/app.js": 404, "/admin": 200, "/admin/": 200, "/admin/panel.js": 200, "/admin/ui.js": 200, "/admin/components.js": 200, "/admin/app.css": 200, "/admin/providers.json": 200} {
		res, e := c.Get(s.URL + page)
		if e != nil {
			t.Fatal(e)
		}
		res.Body.Close()
		if res.StatusCode != want {
			t.Errorf("GET %s => %d, want %d", page, res.StatusCode, want)
		}
	}
	// Even a console account with the old user's name has an independent identity.
	if err = a.SetConsoleAccount(context.Background(), "owner", "console-only-password"); err != nil {
		t.Fatal(err)
	}
	h.send(newClient(), "login", obj{"name": "owner", "password": "console-only-password"}, 401)
	h.send(c, "admin/login", obj{"name": "owner", "password": "console-only-password"}, 200)
	h.send(c, "admin/manage", obj{"op": "instance.detail"}, 200)
}
