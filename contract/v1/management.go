package contract

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/santhosh-tekuri/jsonschema/v6"
	"strings"
	"sync"
)

var managementSchemas = sync.OnceValues(func() (map[string]*jsonschema.Schema, error) {
	c := jsonschema.NewCompiler()
	result := make(map[string]*jsonschema.Schema)
	for _, t := range Tools() {
		if t.Route != "manage" && t.Route != "admin.manage" {
			continue
		}
		// The tool schema omits the dispatcher-owned operation name.
		uri := "urn:timepin:manage:" + t.Name
		if e := c.AddResource(uri, t.Input); e != nil {
			return nil, e
		}
		s, e := c.Compile(uri)
		if e != nil {
			return nil, e
		}
		result[t.Op] = s
	}
	return result, nil
})

func ValidateManagement(raw json.RawMessage) error {
	value, e := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if e != nil {
		return e
	}
	args, ok := value.(map[string]any)
	if !ok {
		return fmt.Errorf("expected object")
	}
	op, _ := args["op"].(string)
	delete(args, "op")
	shapes, e := managementSchemas()
	if e != nil {
		return e
	}
	s := shapes[op]
	if s == nil {
		return fmt.Errorf("unknown operation %s", strings.TrimSpace(op))
	}
	return s.Validate(args)
}
