package server

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestConsoleLAN(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	app := NewApp(db, true, false)
	const nas = "10.77.8.10"
	if err := app.SetConsoleLAN("10.77.8.0/24"); err == nil {
		t.Fatal("expected an interface address")
	}
	if err := app.SetConsoleLAN(nas + "/24"); err != nil {
		t.Fatal(err)
	}
	handler := app.Handler()
	check := func(peer, destination, method, path string, want int) {
		t.Helper()
		req := httptest.NewRequest(method, path, nil)
		req.RemoteAddr = net.JoinHostPort(peer, "50001")
		local := &net.TCPAddr{IP: net.ParseIP(destination), Port: 18760}
		req = req.WithContext(context.WithValue(req.Context(), http.LocalAddrContextKey, local))
		res := httptest.NewRecorder()
		handler.ServeHTTP(res, req)
		if res.Code != want {
			t.Errorf("%s -> %s %s %s: got %d, want %d", peer, destination, method, path, res.Code, want)
		}
	}
	check("10.77.8.42", nas, "GET", "/admin/", 200)
	check("10.77.8.42", nas, "GET", "/api/v1/admin/state", 401)
	for _, pair := range [][2]string{{"127.0.0.1", "127.0.0.1"}, {"203.0.113.7", nas}, {nas, nas}} {
		check(pair[0], pair[1], "GET", "/admin", 403)
		check(pair[0], pair[1], "GET", "/admin/panel.js", 403)
		check(pair[0], pair[1], "POST", "/api/v1/admin/login", 403)
		check(pair[0], pair[1], "GET", "/api/v1/admin/releases", 403)
		check(pair[0], pair[1], "GET", "/api/v1/instance", 200)
		check(pair[0], pair[1], "GET", "/api/v1/releases/org.timepin.app/latest", 401)
	}
}
