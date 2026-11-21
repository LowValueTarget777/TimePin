// Package contract embeds the transport-independent TimePin v1 operation contract.
package contract

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

//go:embed schema.json
var document []byte

var compile = sync.OnceValues(func() (map[string]*jsonschema.Schema, error) {
	data, err := jsonschema.UnmarshalJSON(bytes.NewReader(document))
	if err != nil {
		return nil, err
	}
	c := jsonschema.NewCompiler()
	const uri = "urn:timepin:calendar:v1"
	if err := c.AddResource(uri, data); err != nil {
		return nil, err
	}
	// The discriminator selects the same branch as the public oneOf. A
	// large import is validated once, independent of the number of commands.
	shapes := make(map[string]*jsonschema.Schema)
	for name := range data.(map[string]any)["$defs"].(map[string]any) {
		if op, ok := strings.CutPrefix(name, "op."); ok {
			schema, err := c.Compile(uri + "#/$defs/" + name)
			if err != nil {
				return nil, err
			}
			shapes[op] = schema
		}
	}
	return shapes, nil
})

// ValidateRequest checks the exact wire shape before a request reaches domain logic.
func ValidateRequest(raw []byte) error {
	if !utf8.Valid(raw) {
		return fmt.Errorf("request must be UTF-8")
	}
	shapes, err := compile()
	if err != nil {
		return err
	}
	value, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
	if err != nil {
		return err
	}
	object, _ := value.(map[string]any)
	op, _ := object["op"].(string)
	schema, ok := shapes[op]
	if !ok {
		return fmt.Errorf("unknown calendar operation")
	}
	return schema.Validate(value)
}

// DecodeRequest also accepts integral JSON numbers written as 1.0 or 1e0.
// Every numeric field in v1 is an integer in the interoperable 53-bit range.
func DecodeRequest(raw []byte, dst any) error {
	if err := ValidateRequest(raw); err != nil {
		return err
	}
	if err := json.Unmarshal(raw, dst); err == nil {
		return nil
	}
	// encoding/json requires integer literals for Go ints. Canonicalize only
	// on the uncommon path, after lossless schema validation of the input.
	var normalized any
	if err := json.Unmarshal(raw, &normalized); err != nil {
		return err
	}
	canonical, err := json.Marshal(normalized)
	if err != nil {
		return err
	}
	return json.Unmarshal(canonical, dst)
}
