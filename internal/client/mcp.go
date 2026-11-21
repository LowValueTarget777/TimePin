package client

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	contract "timepin/contract/v1"
)

const protocolVersion = "2025-11-25"

type rpcRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}
type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func ServeMCP(ctx context.Context, c *Client, in io.Reader, out io.Writer) error {
	return serveTools(ctx, c, in, out, false)
}

// ServeConsoleMCP is explicitly selected by the deployment administrator.
func ServeConsoleMCP(ctx context.Context, c *Client, in io.Reader, out io.Writer) error {
	return serveTools(ctx, c, in, out, true)
}
func serveTools(ctx context.Context, c *Client, in io.Reader, out io.Writer, console bool) error {
	s := bufio.NewScanner(in)
	s.Buffer(make([]byte, 4096), 17<<20)
	enc := json.NewEncoder(out)
	initialized := false
	for s.Scan() {
		var req rpcRequest
		var issue *rpcError
		var result any
		if !json.Valid(s.Bytes()) {
			issue = &rpcError{-32700, "Parse error"}
		} else if json.Unmarshal(s.Bytes(), &req) != nil || req.JSONRPC != "2.0" || req.Method == "" {
			issue = &rpcError{-32600, "Invalid request"}
		} else if len(req.ID) == 0 {
			continue
		} else {
			switch req.Method {
			case "initialize":
				var p struct{ ProtocolVersion string }
				if json.Unmarshal(req.Params, &p) != nil || p.ProtocolVersion == "" {
					issue = &rpcError{-32602, "protocolVersion required"}
					break
				}
				version := protocolVersion
				switch p.ProtocolVersion {
				case "2024-11-05", "2025-03-26", "2025-06-18":
					version = p.ProtocolVersion
				}
				initialized = true
				result = map[string]any{"protocolVersion": version, "serverInfo": map[string]string{"name": "timepin", "version": contract.Build.Name}, "capabilities": map[string]any{"tools": map[string]any{}}}
			case "ping":
				result = map[string]any{}
			case "tools/list", "tools/call":
				if !initialized {
					issue = &rpcError{-32600, "Initialize first"}
					break
				}
				if req.Method == "tools/list" {
					tools := []contract.Tool{}
					for _, t := range contract.Tools() {
						if (t.Route == "admin.manage") == console {
							tools = append(tools, t)
						}
					}
					result = map[string]any{"tools": tools}
					break
				}
				var p struct {
					Name      string
					Arguments map[string]any
				}
				d := json.NewDecoder(bytes.NewReader(req.Params))
				d.UseNumber()
				if d.Decode(&p) != nil {
					issue = &rpcError{-32602, "Invalid tool parameters"}
					break
				}
				t, ok := contract.Find(p.Name)
				if !ok || (t.Route == "admin.manage") != console {
					issue = &rpcError{-32602, "Unknown tool"}
					break
				}
				r, e := c.Call(ctx, t.Op, p.Arguments)
				content := string(r.Body)
				bad := r.Failed
				if e != nil {
					content = e.Error()
					bad = true
				}
				result = map[string]any{"content": []any{map[string]string{"type": "text", "text": content}}, "isError": bad}
			default:
				issue = &rpcError{-32601, "Method not found"}
			}
		}
		msg := map[string]any{"jsonrpc": "2.0", "id": req.ID}
		if issue != nil {
			msg["error"] = issue
		} else {
			msg["result"] = result
		}
		if e := enc.Encode(msg); e != nil {
			return e
		}
	}
	return s.Err()
}
