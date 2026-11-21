package server

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"testing"
)

func TestDeviceBackgroundCapability(t *testing.T) {
	legacy, err := deviceSet()
	if err != nil {
		t.Fatal(err)
	}
	preview, err := backdropSet()
	if err != nil {
		t.Fatal(err)
	}
	if legacy.inputs["background_update"] != nil || preview.inputs["background_photo"] != nil {
		t.Fatal("unexpected model tool")
	}
	shape := preview.inputs["background_update"]
	if shape == nil || shape.Validate(map[string]any{"mode": "solid", "color": "#294658"}) != nil {
		t.Fatal("valid background option missing")
	}
	if shape.Validate(map[string]any{"mode": "photo", "image": "private"}) == nil {
		t.Fatal("model accepted image data")
	}
}

func TestAccountAssistantWithoutCalendars(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "actions.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	model := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Tools    []any
			Messages []modelMessage
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Tools) != 3 {
			t.Error("account catalog", err)
		}
		call := obj{"type": "function", "id": "proposal", "function": obj{"name": "group_create", "arguments": `{"name":"轮班组"}`}}
		json.NewEncoder(w).Encode(obj{"choices": []any{obj{"message": obj{"role": "assistant", "tool_calls": []any{call}}}}})
	}))
	defer model.Close()
	app := NewApp(db, true, false)
	app.Model = ModelConfig{BaseURL: model.URL, Name: "fixture", active: true}
	server := httptest.NewServer(app.Handler())
	defer server.Close()
	h := apiTest{url: server.URL, t: t}
	a, b := newClient(), newClient()
	h.send(a, "register", obj{"name": "Owner", "password": "account-test-password"}, 200)
	h.send(b, "register", obj{"name": "Other", "password": "account-test-password"}, 200)
	state := func() obj { return h.send(a, "state", nil, 200) }
	initial := state()
	user := initial["user"].(obj)["id"].(string)
	h.manage(a, "calendar.delete", obj{"target": initial["calendars"].([]any)[0].(obj)["id"]}, 200)
	h.send(a, "ai/tools", obj{"calendarIds": []string{}}, 400)
	request := obj{"scope": "account", "message": "创建轮班组", "context": obj{}}
	h.send(a, "ai/actions", request, 403)
	grant := func(on bool) {
		q := "DELETE FROM ai_members WHERE user_id=?"
		if on {
			q = "INSERT INTO ai_members VALUES(?)"
		}
		if _, err := db.Exec(q, user); err != nil {
			t.Fatal(err)
		}
	}
	grant(true)
	proposal := h.send(a, "ai/actions", request, 200)["proposal"].(obj)
	if len(state()["groups"].([]any)) != 0 {
		t.Fatal("planning changed the account")
	}
	proposal["operationId"] = "create-group"
	body := obj{"userId": user, "source": "server", "command": proposal}
	h.send(b, "ai/account/confirm", body, 403)
	grant(false)
	h.send(a, "ai/account/confirm", body, 403)
	grant(true)
	group := h.send(a, "ai/account/confirm", body, 200)
	if !reflect.DeepEqual(group, h.send(a, "ai/account/confirm", body, 200)) {
		t.Fatal("group replay")
	}
	body["source"] = "personal"
	body["command"] = obj{"op": "calendar.create", "name": "排班", "color": "#336655", "team": group["id"], "operationId": "create-calendar"}
	h.send(a, "ai/account/confirm", body, 200)
	other := h.send(b, "state", nil, 200)["calendars"].([]any)[0].(obj)["id"]
	invite := h.manage(b, "calendar.invite", obj{"target": other, "role": "reader"}, 200)
	body["command"] = obj{"op": "invite.accept", "token": invite["token"], "operationId": "join"}
	joined := h.send(a, "ai/account/confirm", body, 200)
	if !reflect.DeepEqual(joined, h.send(a, "ai/account/confirm", body, 200)) || joined["id"] != other {
		t.Fatal("invite replay")
	}
	if len(state()["calendars"].([]any)) != 2 {
		t.Fatal("expected team calendar and joined calendar")
	}
	body["command"] = obj{"op": "group.transfer", "operationId": "out-of-scope", "target": group["id"], "subject": "Other"}
	h.send(a, "ai/account/confirm", body, 400)
}
