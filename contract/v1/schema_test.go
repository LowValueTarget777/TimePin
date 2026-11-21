package contract

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

// Production selects one schema by op. Keep that shortcut equivalent to the
// public schema that other implementations consume.
func TestDispatchMatchesPublishedSchema(t *testing.T) {
	data, err := jsonschema.UnmarshalJSON(bytes.NewReader(document))
	if err != nil {
		t.Fatal(err)
	}
	c := jsonschema.NewCompiler()
	const uri = "urn:timepin:calendar:v1"
	if err := c.AddResource(uri, data); err != nil {
		t.Fatal(err)
	}
	root, err := c.Compile(uri)
	if err != nil {
		t.Fatal(err)
	}
	inputs := []json.RawMessage{json.RawMessage("null"), json.RawMessage("[]"), json.RawMessage(`{"op":"other","v":1}`)}
	files, err := filepath.Glob("cases/*.json")
	if err != nil || len(files) == 0 {
		t.Fatalf("missing fixtures: %v", err)
	}
	for _, p := range files {
		raw, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		var s struct {
			Steps []struct {
				Command json.RawMessage `json:"command"`
			} `json:"steps"`
		}
		if err := json.Unmarshal(raw, &s); err != nil {
			t.Fatal(err)
		}
		for _, x := range s.Steps {
			inputs = append(inputs, x.Command)
		}
	}
	for _, raw := range inputs {
		value, err := jsonschema.UnmarshalJSON(bytes.NewReader(raw))
		if err != nil {
			t.Fatal(err)
		}
		if (ValidateRequest(raw) == nil) != (root.Validate(value) == nil) {
			t.Fatalf("schema dispatch disagrees: %s", raw)
		}
	}
}
