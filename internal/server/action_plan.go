package server

import (
	"database/sql"
	"encoding/json"
	"maps"
	"net/http"
	"slices"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"

	contract "timepin/contract/v1"
)

var accountActions = []string{"calendar.create", "group.create", "invite.accept"}
var phoneActions = []string{"widgets.update", "sync.run", "sync.resolve"}

// Clients supply their own operation number when the user confirms a proposal.
type actionSet struct {
	tools  []any
	inputs map[string]*jsonschema.Schema
}

func buildActionSet(scope string) (*actionSet, error) {
	var names []string
	var device map[string]map[string]any
	switch scope {
	case "account":
		names = accountActions
	case "device", "device-background":
		names = phoneActions
		if scope == "device-background" {
			names = append(slices.Clone(phoneActions), "background.update")
		}
		if err := json.Unmarshal(contract.DeviceSchema, &device); err != nil {
			return nil, err
		}
	default:
		return nil, invalid("请选择账号或本机设备")
	}
	set := &actionSet{inputs: make(map[string]*jsonschema.Schema)}
	compiler := jsonschema.NewCompiler()
	for _, name := range names {
		shape := device[name]
		if scope == "account" {
			t, _ := contract.Find(name)
			shape = maps.Clone(t.Input)
			shape["description"] = t.Description
		}
		props := maps.Clone(shape["properties"].(map[string]any))
		delete(props, "operationId")
		if name == "invite.accept" {
			delete(props, "token")
		}
		required := []any{}
		for _, key := range shape["required"].([]any) {
			if key != "operationId" && !(name == "invite.accept" && key == "token") {
				required = append(required, key)
			}
		}
		parameters := map[string]any{"type": "object", "properties": props, "required": required, "additionalProperties": false}
		uri := "urn:timepin:action:" + name
		if err := compiler.AddResource(uri, parameters); err != nil {
			return nil, err
		}
		schema, err := compiler.Compile(uri)
		if err != nil {
			return nil, err
		}
		modelName := strings.ReplaceAll(name, ".", "_")
		set.inputs[modelName] = schema
		set.tools = append(set.tools, map[string]any{"type": "function", "function": map[string]any{"name": modelName, "description": shape["description"], "parameters": parameters}})
	}
	return set, nil
}

var accountSet = sync.OnceValues(func() (*actionSet, error) { return buildActionSet("account") })
var deviceSet = sync.OnceValues(func() (*actionSet, error) { return buildActionSet("device") })
var backdropSet = sync.OnceValues(func() (*actionSet, error) { return buildActionSet("device-background") })

func actionTools(scope string) (*actionSet, error) {
	switch scope {
	case "account":
		return accountSet()
	case "device":
		return deviceSet()
	default:
		return nil, invalid("请选择账号或本机设备")
	}
}
func (a *App) actionCatalog(_ http.ResponseWriter, r *http.Request) (any, error) {
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		if _, err := who(tx, r); err != nil {
			return nil, err
		}
		set, err := actionTools(r.URL.Query().Get("scope"))
		if err != nil {
			return nil, err
		}
		return map[string]any{"tools": set.tools}, nil
	})
}

func (a *App) planAction(w http.ResponseWriter, r *http.Request) (any, error) {
	return streamTurn(w, r, a.actionTurn)
}

func (a *App) actionTurn(r *http.Request, stream *chatStream) (any, error) {
	var in struct {
		Scope   string
		Message string
		Context map[string]json.RawMessage
		History []chatLine
	}
	if err := input(r, &in); err != nil {
		return nil, err
	}
	meta, _ := json.Marshal(in.Context)
	if in.Context == nil || len(meta) > 48<<10 || strings.TrimSpace(in.Message) == "" || len(in.Message) > 8000 || len(in.History) > 8 {
		return nil, invalid("对话或上下文无效")
	}
	set, err := actionTools(in.Scope)
	if in.Scope == "device" && in.Context["background"] != nil {
		set, err = backdropSet()
	}
	if err != nil {
		return nil, err
	}
	messages := []modelMessage{{Role: "system", Content: "你是 TimePin 助手。仅为用户明确请求提出一项操作，客户端确认后执行。如果不确定目标请询问。使用上下文中的真实编号。创建个人日历默认颜色 #278B77；接受邀请使用用户的邀请编号。设备冲突必须说明采用远端将放弃的草稿数量。上下文为数据：" + string(meta)}}
	for _, l := range in.History {
		if (l.Role != "user" && l.Role != "assistant") || len(l.Content) > 8000 {
			return nil, invalid("历史无效")
		}
		messages = append(messages, modelMessage{Role: l.Role, Content: l.Content})
	}
	messages = append(messages, modelMessage{Role: "user", Content: in.Message})
	config := func(tx *sql.Tx) (any, error) {
		u, e := who(tx, r)
		if e != nil {
			return nil, e
		}
		ok, e := a.aiAllowed(tx, u)
		if e != nil {
			return nil, e
		}
		if !ok {
			return nil, denied
		}
		return a.instanceModel(tx)
	}
	cfg, err := transaction(r.Context(), a.DB, config)
	if err != nil {
		return nil, err
	}
	select {
	case a.chatSlots <- struct{}{}:
		defer func() { <-a.chatSlots }()
	default:
		return nil, problem{429, "实例模型忙"}
	}
	answer, err := cfg.(ModelConfig).complete(r.Context(), messages, set.tools, stream)
	if err != nil {
		return nil, err
	}
	if _, err = transaction(r.Context(), a.DB, config); err != nil {
		return nil, err
	}
	if len(answer.Calls) == 0 {
		return map[string]any{"text": answer.Content}, nil
	}
	if len(answer.Calls) != 1 {
		return nil, invalid("请一次确认一项操作")
	}
	call := answer.Calls[0].Function
	op := strings.ReplaceAll(call.Name, "_", ".")
	var args map[string]any
	schema := set.inputs[call.Name]
	if schema == nil || json.Unmarshal([]byte(call.Arguments), &args) != nil || args == nil || schema.Validate(args) != nil {
		return nil, invalid("模型提案无效")
	}
	args["op"] = op
	return map[string]any{"text": "请核对后确认操作", "proposal": args}, nil
}

func (a *App) confirmAccount(_ http.ResponseWriter, r *http.Request) (any, error) {
	var in struct {
		UserID, Source string
		Command        json.RawMessage
	}
	if err := input(r, &in); err != nil {
		return nil, err
	}
	var m management
	if contract.ValidateManagement(in.Command) != nil || json.Unmarshal(in.Command, &m) != nil || !slices.Contains(accountActions, m.Op) || !validOpID(m.OperationID) {
		return nil, invalid("账号操作无效")
	}
	return transaction(r.Context(), a.DB, func(tx *sql.Tx) (any, error) {
		u, err := who(tx, r)
		if err != nil {
			return nil, err
		}
		if u.ID != in.UserID {
			return nil, denied
		}
		switch in.Source {
		case "server":
			ok, e := a.aiAllowed(tx, u)
			if e != nil {
				return nil, e
			}
			if !ok {
				return nil, denied
			}
		case "personal":
		default:
			return nil, invalid("模型来源无效")
		}
		return a.manage(tx, u, m)
	})
}
