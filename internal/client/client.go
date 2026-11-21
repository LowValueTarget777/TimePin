package client

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
	contract "timepin/contract/v1"
)

type Client struct {
	base, token, file string
	http              *http.Client
}
type Reply struct {
	Body   json.RawMessage
	Failed bool
}

func New(base, token, file string) (*Client, error) {
	base = strings.TrimRight(base, "/")
	u, e := url.Parse(base)
	if e != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("invalid TimePin instance URL")
	}
	if file == "" {
		dir, e := os.UserConfigDir()
		if e != nil {
			return nil, e
		}
		file = filepath.Join(dir, "timepin", fmt.Sprintf("session-%x", sha256.Sum256([]byte(base))))
	}
	return &Client{base, token, file, &http.Client{Timeout: 60 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

// Session files are bound to an origin even when callers select their own file.
type saved struct {
	Base    string `json:"base"`
	Session string `json:"session"`
}

func (c *Client) save(file, session string) error {
	if e := os.MkdirAll(filepath.Dir(file), 0700); e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(file), ".session-*")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	e = json.NewEncoder(f).Encode(saved{c.base, session})
	closeErr := f.Close()
	if e != nil {
		return e
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), file)
}
func (c *Client) Call(ctx context.Context, name string, args map[string]any) (Reply, error) {
	if args == nil {
		args = map[string]any{}
	} else {
		args = maps.Clone(args)
	}
	route := name
	if t, ok := contract.Find(name); ok {
		route = t.Route
		if route == "commands" || route == "manage" || route == "admin.manage" {
			args["op"] = t.Op
			if route == "commands" {
				args["v"] = 1
			}
		}
	}
	op, ok := contract.Route(route)
	if !ok {
		return Reply{}, fmt.Errorf("unknown operation %q", name)
	}
	path := op.Path
	for k, v := range args {
		if strings.Contains(path, "{"+k+"}") {
			// One argument names one path segment, including for POST routes.
			segment := fmt.Sprint(v)
			if segment == "." || segment == ".." || strings.ContainsAny(segment, "/\\\\") {
				return Reply{}, errors.New("invalid route parameter")
			}
			path = strings.ReplaceAll(path, "{"+k+"}", url.PathEscape(segment))
			delete(args, k)
		}
	}
	if strings.Contains(path, "{") {
		return Reply{}, errors.New("missing route parameter")
	}
	var destination string
	if name == "android.download" {
		destination, _ = args["file"].(string)
		if destination == "" {
			return Reply{}, errors.New("supply a new local output file")
		}
		delete(args, "file")
	}
	var body io.Reader
	contentType := "application/json"
	if op.Method == "GET" {
		q := url.Values{}
		for k, v := range args {
			q.Set(k, fmt.Sprint(v))
		}
		if q.Encode() != "" {
			path += "?" + q.Encode()
		}
	} else if name == "admin.releasePublish" {
		upload, e := newAPKUpload(args)
		if e != nil {
			return Reply{}, e
		}
		defer upload.file.Close()
		body, contentType = upload.reader, upload.contentType
	} else {
		raw, e := json.Marshal(args)
		body = bytes.NewReader(raw)
		if e != nil {
			return Reply{}, e
		}
	}
	req, e := http.NewRequestWithContext(ctx, op.Method, c.base+"/api/v1/"+path, body)
	if e != nil {
		return Reply{}, e
	}
	req.Header.Set("Content-Type", contentType)
	cookieName, file := "timepin_session", c.file
	if strings.HasPrefix(path, "admin/") {
		cookieName, file = "timepin_console", c.file+".console"
	}
	if name != "login" && name != "register" && name != "admin.login" {
		if c.token != "" {
			req.Header.Set("Authorization", "Bearer "+c.token)
		} else {
			data, e := os.ReadFile(file)
			if e != nil && !os.IsNotExist(e) {
				return Reply{}, e
			}
			if e == nil {
				var s saved
				if json.Unmarshal(data, &s) != nil || s.Base != c.base {
					return Reply{}, errors.New("session file belongs to a different instance or is invalid")
				}
				req.AddCookie(&http.Cookie{Name: cookieName, Value: s.Session})
			}
		}
	}
	resp, e := c.http.Do(req)
	if e != nil {
		return Reply{}, errors.New("cannot reach TimePin instance")
	}
	defer resp.Body.Close()
	if destination != "" && resp.StatusCode == 200 {
		return saveAPK(resp, destination)
	}
	data, e := io.ReadAll(io.LimitReader(resp.Body, (32<<20)+1))
	if e != nil {
		return Reply{}, e
	}
	if len(data) > 32<<20 || !json.Valid(data) {
		return Reply{}, fmt.Errorf("invalid API response (HTTP %d)", resp.StatusCode)
	}
	bad := resp.StatusCode < 200 || resp.StatusCode >= 300
	if !bad {
		for _, cookie := range resp.Cookies() {
			if cookie.Name == cookieName {
				if cookie.MaxAge < 0 {
					if c.token == "" {
						e = os.Remove(file)
						if os.IsNotExist(e) {
							e = nil
						}
					}
				} else {
					e = c.save(file, cookie.Value)
				}
				if e != nil {
					return Reply{}, e
				}
			}
		}
		var status struct{ Code string }
		json.Unmarshal(data, &status)
		bad = status.Code != "" && status.Code != "ok"
	}
	return Reply{bytes.TrimSpace(data), bad}, nil
}
