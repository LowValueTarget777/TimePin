package server

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestReleaseDistribution(t *testing.T) {
	db, err := Open(filepath.Join(t.TempDir(), "qa.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	a := NewApp(db, true, false)
	if err = a.SetConsoleAccount(context.Background(), "operator", "release-test-password"); err != nil {
		t.Fatal(err)
	}
	s := httptest.NewServer(a.Handler())
	defer s.Close()
	h := apiTest{s.URL, t}
	admin, member := newClient(), newClient()
	h.send(admin, "admin/login", obj{"name": "operator", "password": "release-test-password"}, 200)
	h.send(member, "register", obj{"name": "reader", "password": "release-test-password"}, 200)
	const latest = "releases/org.timepin.app/latest"
	const file = "releases/org.timepin.app/2/apk"
	const withdraw = "admin/releases/org.timepin.app/2/withdraw"
	h.send(admin, latest, nil, 401)
	h.send(member, "admin/releases", nil, 401)
	h.send(member, withdraw, obj{}, 401)
	if h.send(member, latest, nil, 200)["release"] != nil {
		t.Fatal("expected empty catalogue")
	}
	upload := func(c *http.Client, apk []byte, notes string, want int) {
		t.Helper()
		var body bytes.Buffer
		form := multipart.NewWriter(&body)
		out, e := form.CreateFormFile("apk", "app.apk")
		if e != nil {
			t.Fatal(e)
		}
		out.Write(apk)
		form.WriteField("notes", notes)
		form.Close()
		res, e := c.Post(s.URL+"/api/v1/admin/releases", form.FormDataContentType(), &body)
		if e != nil {
			t.Fatal(e)
		}
		defer res.Body.Close()
		if res.StatusCode != want {
			b, _ := io.ReadAll(res.Body)
			t.Fatalf("publish %d %s", res.StatusCode, b)
		}
	}
	create := func(pkg string, code int) []byte {
		var body bytes.Buffer
		z := zip.NewWriter(&body)
		// Use a stored multi-chunk entry so download exercises chunk boundaries.
		for _, name := range []string{"AndroidManifest.xml", "classes.dex", "assets/timepin-release.json"} {
			w, e := z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Store})
			if e != nil {
				t.Fatal(e)
			}
			if name == "assets/timepin-release.json" {
				fmt.Fprintf(w, `{"packageName":%q,"versionCode":%d,"versionName":"1.%d"}`, pkg, code, code)
			} else {
				w.Write(make([]byte, releaseChunk))
			}
		}
		if e := z.Close(); e != nil {
			t.Fatal(e)
		}
		return body.Bytes()
	}
	pkg := create("org.timepin.app", 2)
	hash := sha256.Sum256(pkg)
	upload(member, pkg, "", 401)
	upload(admin, []byte("invalid"), "", 400)
	upload(admin, create("org.timepin.app", 1), "", 200)
	upload(admin, pkg, "新版", 200)
	upload(admin, pkg, "新版", 200)
	upload(admin, pkg, "异文", 409)
	r := h.send(member, latest, nil, 200)["release"].(obj)
	if r["sha256"] != hex.EncodeToString(hash[:]) || r["versionCode"] != 2.0 {
		t.Fatal(r)
	}
	h.send(newClient(), file, nil, 401)
	h.send(admin, file, nil, 401)
	res, e := member.Get(s.URL + "/api/v1/" + file)
	if e != nil {
		t.Fatal(e)
	}
	b, e := io.ReadAll(res.Body)
	res.Body.Close()
	if e != nil || !bytes.Equal(b, pkg) {
		t.Fatal("invalid APK download", e)
	}
	for range 2 {
		h.send(admin, withdraw, obj{}, 200)
	}
	upload(admin, pkg, "新版", 200)
	h.send(member, file, nil, 404)
	if r := h.send(member, latest, nil, 200)["release"].(obj); r["versionCode"] != 1.0 {
		t.Fatal(r)
	}
	var n int
	if err = db.QueryRow("SELECT count(*) FROM console_audit WHERE action LIKE 'instance.release%'").Scan(&n); err != nil || n != 3 {
		t.Fatal("duplicate audit", n, err)
	}
	const community = "org.timepin.app.community"
	communityAPK := create(community, 2)
	upload(admin, create("org.example.unsupported", 3), "", 400)
	upload(admin, communityAPK, "community", 200)
	if r := h.send(member, "releases/"+community+"/latest", nil, 200)["release"].(obj); r["packageName"] != community || r["versionCode"] != 2.0 {
		t.Fatal(r)
	}
	if r := h.send(member, latest, nil, 200)["release"].(obj); r["versionCode"] != 1.0 {
		t.Fatal("community publication changed the official channel", r)
	}
	res, err = member.Get(s.URL + "/api/v1/releases/" + community + "/2/apk")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	got, err := io.ReadAll(res.Body)
	if err != nil || res.StatusCode != 200 || !bytes.Equal(got, communityAPK) ||
		res.Header.Get("Content-Disposition") != `attachment; filename="TimePin-community-2.apk"` {
		t.Fatal("community download", err)
	}
}
