package calendar_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"timepin/calendar"
)

type scenario struct {
	Calendar calendar.Calendar             `json:"calendar"`
	Actors   map[string]calendar.Principal `json:"actors"`
	Steps    []struct {
		As      string          `json:"as"`
		Command json.RawMessage `json:"command"`
		Expect  any             `json:"expect"`
		SameAs  *int            `json:"sameAs"`
	} `json:"steps"`
}

func newBook(t testing.TB, info calendar.Calendar) *calendar.Book {
	t.Helper()
	b, err := calendar.New(info)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
func execute(t testing.TB, b *calendar.Book, p calendar.Principal, raw []byte) []byte {
	t.Helper()
	body, err := b.Execute(p, raw)
	if err != nil {
		t.Fatal(err)
	}
	return body
}
func decode(t testing.TB, raw []byte) map[string]any {
	t.Helper()
	var value map[string]any
	if err := json.Unmarshal(raw, &value); err != nil {
		t.Fatal(err)
	}
	return value
}

// assertMatch uses exact ordered arrays and a recursive object projection.
// Cases stay independent of Go and can be run by a Kotlin implementation.
func assertMatch(t *testing.T, path string, want, got any) {
	t.Helper()
	switch w := want.(type) {
	case map[string]any:
		g, ok := got.(map[string]any)
		if !ok {
			t.Fatalf("%s: expected object, got %v", path, got)
		}
		for k, v := range w {
			field, found := g[k]
			if !found {
				t.Fatalf("%s.%s: missing field in %v", path, k, got)
			}
			assertMatch(t, path+"."+k, v, field)
		}
	case []any:
		g, ok := got.([]any)
		if !ok || len(w) != len(g) {
			t.Fatalf("%s: expected %d entries, got %v", path, len(w), got)
		}
		for i := range w {
			assertMatch(t, fmt.Sprintf("%s[%d]", path, i), w[i], g[i])
		}
	default:
		if !reflect.DeepEqual(want, got) {
			t.Fatalf("%s: want %v, got %v", path, want, got)
		}
	}
}
func TestPortableContract(t *testing.T) {
	schemaRaw, err := os.ReadFile("../contract/v1/schema.json")
	if err != nil {
		t.Fatal(err)
	}
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(schemaRaw))
	if err != nil {
		t.Fatal(err)
	}
	compiler := jsonschema.NewCompiler()
	const uri = "urn:timepin:calendar:v1"
	if err = compiler.AddResource(uri, doc); err != nil {
		t.Fatal(err)
	}
	replySchema, err := compiler.Compile(uri + "#/$defs/reply")
	if err != nil {
		t.Fatal(err)
	}
	files, err := filepath.Glob("../contract/v1/cases/*.json")
	if err != nil || len(files) == 0 {
		t.Fatalf("find portable cases: %v, %v", files, err)
	}
	for _, file := range files {
		t.Run(strings.TrimSuffix(filepath.Base(file), ".json"), func(t *testing.T) {
			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			var s scenario
			if err = json.Unmarshal(raw, &s); err != nil {
				t.Fatal(err)
			}
			book := newBook(t, s.Calendar)
			replies := make([][]byte, 0, len(s.Steps))
			for i, step := range s.Steps {
				p, ok := s.Actors[step.As]
				if !ok {
					t.Fatalf("unknown fixture actor %q", step.As)
				}
				got := execute(t, book, p, step.Command)
				value := decode(t, got)
				if err := replySchema.Validate(value); err != nil {
					t.Fatalf("step %d: invalid reply: %v", i, err)
				}
				assertMatch(t, fmt.Sprintf("step %d %s", i, step.Command), step.Expect, value)
				if step.SameAs != nil {
					if *step.SameAs < 0 || *step.SameAs >= len(replies) || !bytes.Equal(got, replies[*step.SameAs]) {
						t.Fatalf("step %d: replay receipt differs", i)
					}
				}
				replies = append(replies, got)
			}
		})
	}
}

var info = calendar.Calendar{ID: "work", OwnerKind: "person", OwnerID: "alice", Color: "#336699"}
var editor = calendar.Principal{ID: "alice", Role: "editor"}

const create = `{"v":1,"op":"event.create","calendarId":"work","operationId":"seed","eventId":"night","event":{"title":"night","span":{"kind":"date","start":"2026-11-01","end":"2026-11-02"}}}`

func TestConcurrentCommands(t *testing.T) {
	book := newBook(t, info)
	const workers = 16
	results := make(chan string, workers)
	var group sync.WaitGroup
	start := make(chan struct{})
	for range workers {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			body, err := book.Execute(editor, []byte(create))
			if err != nil {
				t.Error(err)
			}
			results <- string(body)
		}()
	}
	close(start)
	group.Wait()
	close(results)
	original := ""
	for body := range results {
		if original == "" {
			original = body
		}
		if body != original || decode(t, []byte(body))["code"] != "ok" {
			t.Fatalf("duplicate create: %s", body)
		}
	}

	results = make(chan string, workers)
	for i := range workers {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			raw := fmt.Sprintf(`{"v":1,"op":"event.delete","calendarId":"work","operationId":"del-%d","eventId":"night","baseVersion":1}`, i)
			body, err := book.Execute(editor, []byte(raw))
			if err != nil {
				t.Error(err)
			}
			results <- string(body)
		}(i)
	}
	group.Wait()
	close(results)
	count := map[any]int{}
	for body := range results {
		count[decode(t, []byte(body))["code"]]++
	}
	if count["ok"] != 1 || count["conflict"] != workers-1 {
		t.Fatalf("parallel version checks: %v", count)
	}
}

func TestRejectInvalidInput(t *testing.T) {
	b := newBook(t, info)
	for _, raw := range []string{
		create + "{}", strings.Replace(create, `"v":1`, `"v":2`, 1),
		strings.Replace(create, `"seed"`, `""`, 1),
		strings.Replace(create, "2026-11-02", "2026-11-01", 1),
		strings.Replace(create, `"title":"night"`, `"title":"night","creatorId":"bob"`, 1),
		strings.Replace(create, `"title":"night"`, `"title":"  "`, 1),
		strings.Replace(create, `"title":"night"`, `"title":"night","color":"green"`, 1),
		`{"v":1,"op":"event.query","calendarId":"work","window":{"start":"2026-01-01","end":"2030-01-01","zone":"UTC"}}`,
		`{"v":1,"op":"event.query","calendarId":"work","window":{"start":"2026-01-01","end":"2026-02-01","zone":"Local"}}`,
	} {
		if code := decode(t, execute(t, b, editor, []byte(raw)))["code"]; code != "invalid" {
			t.Fatalf("accepted %s (%v)", raw, code)
		}
	}
	if _, err := calendar.New(calendar.Calendar{ID: "work"}); err == nil {
		t.Fatal("accepted missing calendar owner and color")
	}
}

func TestEquivalentJSONNumbersReplay(t *testing.T) {
	book := newBook(t, info)
	initial := execute(t, book, editor, []byte(create))
	alternate := strings.Replace(create, `"v":1`, `"v":1.00e0`, 1)
	replay := execute(t, book, editor, []byte(alternate))
	if decode(t, initial)["code"] != "ok" || !bytes.Equal(initial, replay) {
		t.Fatalf("equivalent JSON numbers changed receipt: %s", replay)
	}
}

func TestSkippedCivilDate(t *testing.T) {
	book := newBook(t, info)
	// Apia advanced directly from December 29 to December 31 in 2011.
	raw := strings.Replace(create, `{"kind":"date","start":"2026-11-01","end":"2026-11-02"}`,
		`{"kind":"timed","start":"2011-12-29T20:00:00-10:00","end":"2011-12-31T05:00:00+14:00","zone":"Pacific/Apia"}`, 1)
	if v := decode(t, execute(t, book, editor, []byte(raw))); v["code"] != "ok" {
		t.Fatal(v)
	}
	query := []byte(`{"v":1,"op":"event.query","calendarId":"work","window":{"start":"2011-12-30","end":"2011-12-31","zone":"Pacific/Apia"}}`)
	assertMatch(t, "skipped date", map[string]any{"code": "ok", "occurrences": []any{}}, decode(t, execute(t, book, editor, query)))
}

func TestDenseWindowReturnsExplicitError(t *testing.T) {
	book := newBook(t, info)
	longSeries := strings.Replace(create, `"span":`, `"repeat":{"frequency":"daily","interval":1},"span":`, 1)
	longSeries = strings.Replace(longSeries, "2026-11-01", "1970-01-01", 1)
	if v := decode(t, execute(t, book, editor, []byte(longSeries))); v["code"] != "ok" {
		t.Fatal(v)
	}
	query := []byte(`{"v":1,"op":"event.query","calendarId":"work","window":{"start":"2026-11-01","end":"2026-11-02","zone":"UTC"}}`)
	if v := decode(t, execute(t, book, editor, query)); v["code"] != "window_too_dense" {
		t.Fatalf("expected dense window, got %v", v["code"])
	}
}

func batchOf30(timed bool) string {
	rows := make([]string, 30)
	for i := range rows {
		span := fmt.Sprintf(`{"kind":"date","start":"2026-11-%02d","end":"2026-12-01"}`, i+1)
		if timed {
			span = fmt.Sprintf(`{"kind":"timed","start":"2026-11-%02dT09:00:00+08:00","end":"2026-11-%02dT17:00:00+08:00","zone":"Asia/Shanghai"}`, i+1, i+1)
		}
		rows[i] = fmt.Sprintf(`{"externalId":"day-%d","event":{"title":"day","span":%s}}`, i, span)
	}
	return `{"v":1,"op":"import.commit","calendarId":"work","operationId":"batch1","batch":{"source":"test","rows":[` + strings.Join(rows, ",") + `]}}`
}
func TestReviseOneOfThirty(t *testing.T) {
	b := newBook(t, info)
	raw := batchOf30(true)
	initial := execute(t, b, editor, []byte(raw))
	revised := strings.Replace(strings.Replace(raw, "batch1", "batch2", 1), `"title":"day"`, `"title":"changed"`, 1)
	counts := func(body []byte) map[string]int {
		result := decode(t, body)
		if result["code"] != "ok" {
			t.Fatal(result)
		}
		tally := map[string]int{}
		for _, item := range result["rows"].([]any) {
			tally[item.(map[string]any)["outcome"].(string)]++
		}
		return tally
	}
	if tally := counts(initial); tally["created"] != 30 {
		t.Fatal(tally)
	}
	res := execute(t, b, editor, []byte(revised))
	if tally := counts(res); tally["updated"] != 1 || tally["unchanged"] != 29 {
		t.Fatal(tally)
	}
	if replay := execute(t, b, editor, []byte(revised)); !bytes.Equal(replay, res) {
		t.Fatal("replayed batch response differs")
	}
}

func BenchmarkWindow(b *testing.B) {
	book := newBook(b, info)
	series := strings.Replace(create, `"span":`, `"repeat":{"frequency":"daily","interval":1},"span":`, 1)
	if v := decode(b, execute(b, book, editor, []byte(series))); v["code"] != "ok" {
		b.Fatal(v)
	}
	for _, year := range []int{2026, 2226} {
		b.Run(fmt.Sprint(year), func(b *testing.B) {
			raw := []byte(fmt.Sprintf(`{"v":1,"op":"event.query","calendarId":"work","window":{"start":"%d-11-01","end":"%d-12-01","zone":"UTC"}}`, year, year))
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				if _, err := book.Execute(editor, raw); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
func BenchmarkBatchPreview30(b *testing.B) {
	for _, kind := range []string{"date", "timed"} {
		b.Run(kind, func(b *testing.B) {
			book := newBook(b, info)
			raw := []byte(strings.Replace(batchOf30(kind == "timed"), "import.commit", "import.preview", 1))
			if v := decode(b, execute(b, book, editor, raw)); v["code"] != "ok" {
				b.Fatal(v)
			}
			b.ResetTimer()
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				if _, err := book.Execute(editor, raw); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
