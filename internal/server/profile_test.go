package server

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestPersonalProfile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile.db")
	db, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	s := httptest.NewServer(NewApp(db, true, false).Handler())
	h := apiTest{s.URL, t}
	alice, bob := newClient(), newClient()
	for c, n := range map[*http.Client]string{alice: "Alice", bob: "Bob"} {
		h.send(c, "register", obj{"name": n, "password": "profile-test-password"}, 200)
	}
	// Reopen an existing v9 installation with live sessions.
	s.Close()
	if _, e = db.Exec("DROP TABLE profiles; PRAGMA user_version=9"); e != nil {
		t.Fatal(e)
	}
	db.Close()
	db, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	s = httptest.NewServer(NewApp(db, true, false).Handler())
	defer s.Close()
	h.url = s.URL
	read := func(c *http.Client) obj { return h.send(c, "manage", obj{"op": "profile.detail"}, 200) }
	if p := read(alice); p["loginName"] != "Alice" || p["displayName"] != "Alice" || p["revision"] != "0" {
		t.Fatal(p)
	}
	var pic bytes.Buffer
	if e = jpeg.Encode(&pic, image.NewGray(image.Rect(0, 0, 16, 16)), nil); e != nil {
		t.Fatal(e)
	}
	photo := base64.StdEncoding.EncodeToString(pic.Bytes())
	args := obj{"revision": "0", "displayName": "班表用户", "avatar": photo, "operationId": "profile-save"}
	update := h.manage(alice, "profile.update", args, 200)
	if update["avatar"] != nil || update["avatarVersion"] == "" {
		t.Fatal(update)
	}
	if again := h.manage(alice, "profile.update", args, 200); again["revision"] != update["revision"] {
		t.Fatal(again)
	}
	if p := read(alice); p["avatar"] == nil || p["displayName"] != "班表用户" {
		t.Fatal("missing own photo or name")
	}
	if p := h.send(alice, "state", nil, 200)["profile"].(obj); p["avatar"] != nil || p["avatarVersion"] != update["avatarVersion"] {
		t.Fatal(p)
	}
	if p := read(bob); p["displayName"] != "Bob" || p["avatar"] != nil {
		t.Fatal(p)
	}
	login := h.send(newClient(), "login", obj{"name": "Alice", "password": "profile-test-password"}, 200)
	if p := login["profile"].(obj); p["displayName"] != "班表用户" || p["avatar"] == nil {
		t.Fatal("login lacks saved profile")
	}
	h.manage(alice, "profile.update", obj{"revision": "0", "displayName": "旧表单"}, 409)
	h.manage(alice, "profile.update", obj{"revision": update["revision"], "avatar": "bad"}, 400)
	h.manage(alice, "profile.update", obj{"revision": update["revision"], "target": "other", "displayName": "越权"}, 400)
	h.manage(alice, "profile.update", obj{"revision": update["revision"], "avatar": ""}, 200)
	if p := read(alice); p["displayName"] != "班表用户" || p["avatarVersion"] != "" || p["avatar"] != nil {
		t.Fatal(p)
	}
	h.send(newClient(), "manage", obj{"op": "profile.detail"}, 401)
	token := strings.Repeat("b", 64)
	book := h.send(alice, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	h.manage(alice, "token.create", obj{"name": "日历令牌", "target": book, "role": "manager", "token": token}, 200)
	for _, payload := range []string{`{"op":"profile.detail"}`, `{"op":"profile.update","operationId":"denied","revision":"0","displayName":"test"}`} {
		req, err := http.NewRequest(http.MethodPost, s.URL+"/api/v1/manage", strings.NewReader(payload))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatal(resp.Status)
		}
	}
}
