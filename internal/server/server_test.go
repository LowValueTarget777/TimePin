package server

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"timepin/calendar"
)

func TestSQLPortableCases(t *testing.T) {
	files, _ := filepath.Glob("../../contract/v1/cases/*.json")
	if len(files) == 0 {
		t.Fatal("no fixtures")
	}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			var s struct {
				Calendar calendar.Calendar
				Actors   map[string]calendar.Principal
				Steps    []struct {
					As      string
					Command json.RawMessage
				}
			}
			raw, e := os.ReadFile(file)
			if e != nil {
				t.Fatal(e)
			}
			if e = json.Unmarshal(raw, &s); e != nil {
				t.Fatal(e)
			}
			memory, e := calendar.New(s.Calendar)
			if e != nil {
				t.Fatal(e)
			}
			path := filepath.Join(t.TempDir(), "timepin.db")
			db, e := Open(path)
			if e != nil {
				t.Fatal(e)
			}
			_, e = db.Exec("INSERT INTO teams VALUES(?,?);", s.Calendar.OwnerID, "Team")
			if e != nil {
				t.Fatal(e)
			}
			_, e = db.Exec("INSERT INTO calendars VALUES(?,?,?,NULL,?)", s.Calendar.ID, "Calendar", s.Calendar.Color, s.Calendar.OwnerID)
			if e != nil {
				t.Fatal(e)
			}
			db.Close()
			for i, step := range s.Steps {
				db, e = Open(path)
				if e != nil {
					t.Fatal(e)
				}
				p := s.Actors[step.As]
				want, e := memory.Execute(p, step.Command)
				if e != nil {
					t.Fatal(e)
				}
				v, e := transaction(context.Background(), db, func(tx *sql.Tx) (any, error) {
					return calendar.ExecuteSQL(context.Background(), tx, s.Calendar, p, step.Command)
				})
				db.Close()
				if e != nil {
					t.Fatalf("step %d: %v", i, e)
				}
				if !bytes.Equal(want, v.([]byte)) {
					t.Fatalf("step %d: want %s, got %s", i, want, v)
				}
			}
		})
	}
}

type obj = map[string]any
type apiTest struct {
	url string
	t   *testing.T
}

func (h *apiTest) send(c *http.Client, path string, data any, status int) obj {
	h.t.Helper()
	method := "GET"
	if data != nil {
		method = "POST"
	}
	raw, _ := json.Marshal(data)
	req, e := http.NewRequest(method, h.url+"/api/v1/"+path, bytes.NewReader(raw))
	if e != nil {
		h.t.Fatal(e)
	}
	req.Header.Set("Content-Type", "application/json")
	res, e := c.Do(req)
	if e != nil {
		h.t.Fatal(e)
	}
	defer res.Body.Close()
	var reply obj
	if e = json.NewDecoder(res.Body).Decode(&reply); e != nil {
		h.t.Fatal(e)
	}
	if res.StatusCode != status {
		h.t.Fatalf("%s HTTP %d: %v", path, res.StatusCode, reply["error"])
	}
	return reply
}
func (h *apiTest) manage(c *http.Client, op string, data obj, status int) obj {
	data["op"] = op
	if data["operationId"] == nil {
		data["operationId"] = id("test")
	}
	return h.send(c, "manage", data, status)
}
func newClient() *http.Client { jar, _ := cookiejar.New(nil); return &http.Client{Jar: jar} }
func TestSharingAndTransactions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "timepin.db")
	db, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	srv := httptest.NewServer(NewApp(db, false, false).Handler())
	defer srv.Close()
	h := apiTest{url: srv.URL, t: t}
	alice, bob, guest := newClient(), newClient(), newClient()
	const pw = "test-only-passphrase"
	h.send(alice, "register", obj{"name": "Alice", "password": pw}, 200)
	h.send(guest, "register", obj{"name": "Denied", "password": pw}, 403)
	personal := h.send(alice, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	newTeam := obj{"name": "排班组", "operationId": "group-retry"}
	team := h.manage(alice, "group.create", newTeam, 200)["id"]
	if replay := h.manage(alice, "group.create", newTeam, 200)["id"]; replay != team {
		t.Fatal("group creation was repeated")
	}
	h.manage(alice, "group.create", obj{"name": "不同团组", "operationId": "group-retry"}, 409)
	shared := h.manage(alice, "calendar.create", obj{"name": "科室值班", "color": "#335577", "team": team}, 200)["id"]
	invite := h.manage(alice, "group.invite", obj{"target": team, "role": "member"}, 200)["token"]
	h.send(bob, "register", obj{"name": "Bob", "password": pw, "invite": invite}, 200)
	h.manage(bob, "invite.accept", obj{"token": invite}, 404)
	h.manage(alice, "calendar.grant", obj{"target": shared, "subjectKind": "user", "subject": "Bob", "role": "contributor"}, 200)
	event := obj{"title": "夜班", "span": obj{"kind": "date", "start": "2026-11-02", "end": "2026-11-03"}, "color": "#AA4488"}
	create := obj{"v": 1, "op": "event.create", "calendarId": shared, "operationId": "op1", "eventId": "night", "event": event}
	checkCode := func(v obj, w string) {
		t.Helper()
		if v["code"] != w {
			t.Fatalf("expected %s, got %v", w, v)
		}
	}
	checkCode(h.send(alice, "commands", create, 200), "ok")
	upd := obj{"v": 1, "op": "event.replace", "calendarId": shared, "operationId": "other", "eventId": "night", "baseVersion": 1, "event": event}
	checkCode(h.send(bob, "commands", upd, 200), "forbidden")
	create["operationId"] = "op2"
	create["eventId"] = "bobs"
	checkCode(h.send(bob, "commands", create, 200), "ok")
	h.manage(alice, "calendar.grant", obj{"target": shared, "subjectKind": "user", "subject": "Bob", "role": "remove"}, 200)
	checkCode(h.send(bob, "commands", create, 200), "forbidden")
	share := h.manage(alice, "calendar.share", obj{"target": shared, "enabled": true}, 200)["token"].(string)
	pathQuery := "public/" + share + "?start=2026-11-01&end=2026-12-01&zone=UTC"
	public := h.send(guest, pathQuery, nil, 200)
	if len(public["result"].(obj)["occurrences"].([]any)) != 2 || public["ownerId"] != nil {
		t.Fatal("public projection")
	}
	h.manage(alice, "calendar.share", obj{"target": shared, "enabled": false}, 200)
	h.send(guest, pathQuery, nil, 404)
	h.manage(alice, "calendar.grant", obj{"target": personal, "subjectKind": "group", "subject": team, "role": "reader"}, 200)
	h.manage(bob, "calendar.visible", obj{"target": personal, "enabled": false}, 200)
	h.manage(alice, "group.transfer", obj{"target": team, "subject": "Bob"}, 200)
	info, e := transaction(context.Background(), db, func(tx *sql.Tx) (any, error) { u, _ := userID(tx, "Bob"); return allowed(tx, u, shared.(string)) })
	if e != nil {
		t.Fatal(e)
	}
	owned := info.(calendarView)
	if owned.OwnerID != team || owned.Role != "manager" {
		t.Fatal(owned)
	}
	h.manage(alice, "calendar.detail", obj{"target": shared}, 403)
	h.manage(bob, "group.member", obj{"target": team, "subject": "Bob", "role": "remove"}, 400)
	h.send(bob, "logout", obj{}, 200)
	h.send(bob, "commands", create, 401)
	h.send(bob, "login", obj{"name": "Bob", "password": pw}, 200)
	another := newClient()
	h.send(another, "login", obj{"name": "Bob", "password": pw}, 200)
	h.send(bob, "password", obj{"old": pw, "new": "new-test-only-passphrase"}, 200)
	h.send(another, "commands", create, 401)
}
func TestMigrationAndAtomicRollback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "db")
	db, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("INSERT INTO teams VALUES('t','Team'); INSERT INTO calendars VALUES('work','Work','#336699',NULL,'t'); CREATE TRIGGER reject_write BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT,'simulated failure'); END;"); e != nil {
		t.Fatal(e)
	}
	info := calendar.Calendar{ID: "work", OwnerKind: "group", OwnerID: "t", Color: "#336699"}
	raw := []byte(`{"v":1,"op":"event.create","calendarId":"work","operationId":"one","eventId":"n","event":{"title":"day","span":{"kind":"date","start":"2026-11-01","end":"2026-11-02"}}}`)
	execute := func() (any, error) {
		return transaction(context.Background(), db, func(tx *sql.Tx) (any, error) {
			return calendar.ExecuteSQL(context.Background(), tx, info, calendar.Principal{ID: "writer", Role: "editor"}, raw)
		})
	}
	if _, e = execute(); e == nil {
		t.Fatal("expected rollback")
	}
	var receipts int
	if e = db.QueryRow("SELECT count(*) FROM receipts").Scan(&receipts); e != nil || receipts != 0 {
		t.Fatal("partial receipt")
	}
	if _, e = db.Exec("DROP TRIGGER reject_write"); e != nil {
		t.Fatal(e)
	}
	if _, e = execute(); e != nil {
		t.Fatal(e)
	}
	db.Close()
	db, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = execute(); e != nil {
		t.Fatal(e)
	}
	if _, e = db.Exec("PRAGMA user_version=999"); e != nil {
		t.Fatal(e)
	}
	db.Close()
	if db, e = Open(path); e == nil {
		db.Close()
		t.Fatal("accepted unknown schema")
	}
}
