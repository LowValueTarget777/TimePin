package client

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime/multipart"
	"net/http"
	"os"
)

const apkLimit = 64 << 20

type apkUpload struct {
	file        *os.File
	reader      io.Reader
	contentType string
}

func newAPKUpload(args map[string]any) (apkUpload, error) {
	path, _ := args["file"].(string)
	notes, _ := args["notes"].(string)
	if path == "" || len(notes) > 4096 {
		return apkUpload{}, errors.New("supply an APK file and at most 4096 bytes of release notes")
	}
	var framing bytes.Buffer
	m := multipart.NewWriter(&framing)
	if e := m.WriteField("notes", notes); e != nil {
		return apkUpload{}, e
	}
	if _, e := m.CreateFormFile("apk", "TimePin.apk"); e != nil {
		return apkUpload{}, e
	}
	header := framing.Len()
	if e := m.Close(); e != nil {
		return apkUpload{}, e
	}
	f, e := os.Open(path)
	if e != nil {
		return apkUpload{}, e
	}
	st, e := f.Stat()
	if e != nil || !st.Mode().IsRegular() || st.Size() > apkLimit || st.Size() == 0 {
		f.Close()
		return apkUpload{}, errors.New("expected a regular APK file up to 64 MiB")
	}
	b := framing.Bytes()
	return apkUpload{f, io.MultiReader(bytes.NewReader(b[:header]), io.LimitReader(f, st.Size()), bytes.NewReader(b[header:])), m.FormDataContentType()}, nil
}

func saveAPK(resp *http.Response, dest string) (Reply, error) {
	if resp.ContentLength <= 0 || resp.ContentLength > apkLimit || resp.Header.Get("Content-Type") != "application/vnd.android.package-archive" {
		return Reply{}, errors.New("unexpected APK response")
	}
	f, e := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if e != nil {
		return Reply{}, e
	}
	h := sha256.New()
	n, e := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, resp.ContentLength+1))
	closeErr := f.Close()
	if e != nil || closeErr != nil || n != resp.ContentLength {
		os.Remove(dest)
		return Reply{}, errors.New("incomplete APK download")
	}
	body, e := json.Marshal(map[string]any{"file": dest, "sha256": hex.EncodeToString(h.Sum(nil)), "bytes": n})
	return Reply{Body: body}, e
}
