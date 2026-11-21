package contract

import (
	"encoding/json"
	"sort"
	"strings"
	"sync"
)
import _ "embed"

//go:embed openapi.json
var OpenAPI []byte

//go:embed device.json
var DeviceSchema []byte

func CalendarSchema() []byte { return document }

type Tool struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Input       map[string]any `json:"inputSchema"`
	Op          string         `json:"-"`
	Route       string         `json:"-"`
}
type Operation struct {
	ID     string
	Method string
	Path   string
}
type catalog struct {
	tools  []Tool
	routes map[string]Operation
}

var published = sync.OnceValue(func() catalog {
	var event, api map[string]any
	if err := json.Unmarshal(document, &event); err != nil {
		panic(err)
	}
	if err := json.Unmarshal(OpenAPI, &api); err != nil {
		panic(err)
	}
	defs := event["$defs"].(map[string]any)
	host := api["components"].(map[string]any)["schemas"].(map[string]any)
	var expand func(any) any
	expand = func(v any) any {
		switch x := v.(type) {
		case map[string]any:
			if ref, ok := x["$ref"].(string); ok {
				parts := strings.Split(ref, "/")
				key := parts[len(parts)-1]
				if strings.Contains(ref, "/components/") {
					return expand(host[key])
				}
				return expand(defs[key])
			}
			out := map[string]any{}
			for k, val := range x {
				if !strings.HasPrefix(k, "x-") {
					out[k] = expand(val)
				}
			}
			return out
		case []any:
			out := make([]any, len(x))
			for i, val := range x {
				out[i] = expand(val)
			}
			return out
		default:
			return v
		}
	}
	c := catalog{routes: map[string]Operation{}}
	add := func(op, route string, shape map[string]any) {
		input := expand(shape).(map[string]any)
		desc, _ := input["description"].(string)
		delete(input, "description")
		props := input["properties"].(map[string]any)
		delete(props, "v")
		delete(props, "op")
		req := []any{}
		for _, key := range input["required"].([]any) {
			if key != "v" && key != "op" {
				req = append(req, key)
			}
		}
		input["required"] = req
		c.tools = append(c.tools, Tool{strings.ReplaceAll(op, ".", "_"), desc, input, op, route})
	}
	for k, v := range defs {
		if op, ok := strings.CutPrefix(k, "op."); ok {
			add(op, "commands", v.(map[string]any))
		}
	}
	for schema, route := range map[string]string{"Management": "manage", "ConsoleManagement": "admin.manage"} {
		for _, alt := range host[schema].(map[string]any)["oneOf"].([]any) {
			ref := alt.(map[string]any)["$ref"].(string)
			shape := host[strings.TrimPrefix(ref, "#/components/schemas/")].(map[string]any)
			op := shape["properties"].(map[string]any)["op"].(map[string]any)["const"].(string)
			add(op, route, shape)
		}
	}
	for path, methods := range api["paths"].(map[string]any) {
		for method, val := range methods.(map[string]any) {
			id := val.(map[string]any)["operationId"].(string)
			c.routes[id] = Operation{id, strings.ToUpper(method), strings.TrimPrefix(path, "/")}
		}
	}
	add("state", "state", map[string]any{"type": "object", "description": "读取当前身份与已授权的日历", "properties": map[string]any{}, "required": []any{}, "additionalProperties": false})
	add("batches", "batches", map[string]any{"type": "object", "description": "列出日历最近的导入批次，可用 before 游标翻页", "properties": map[string]any{"calendarId": map[string]any{"type": "string"}, "before": map[string]any{"type": "integer"}}, "required": []any{"calendarId"}, "additionalProperties": false})
	sort.Slice(c.tools, func(i, j int) bool { return c.tools[i].Name < c.tools[j].Name })
	return c
})

func Tools() []Tool { return published().tools }
func Find(name string) (Tool, bool) {
	for _, t := range Tools() {
		if t.Name == name || t.Op == name {
			return t, true
		}
	}
	return Tool{}, false
}
func Route(id string) (Operation, bool) { op, ok := published().routes[id]; return op, ok }
