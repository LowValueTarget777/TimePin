package calendar

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"maps"
	"strings"
	"sync"

	contract "timepin/contract/v1"
)

type command struct {
	V           int     `json:"v"`
	Op          string  `json:"op"`
	CalendarID  string  `json:"calendarId"`
	OperationID string  `json:"operationId,omitempty"`
	EventID     string  `json:"eventId,omitempty"`
	BaseVersion int64   `json:"baseVersion,omitempty"`
	Event       *Event  `json:"event,omitempty"`
	Window      *Window `json:"window,omitempty"`
	Batch       *batch  `json:"batch,omitempty"`
	BatchID     string  `json:"batchId,omitempty"`
}
type reply struct {
	Code        string        `json:"code"`
	Detail      string        `json:"detail,omitempty"`
	Record      *Record       `json:"record,omitempty"`
	Current     *Record       `json:"current,omitempty"`
	Occurrences *[]Occurrence `json:"occurrences,omitempty"`
	Rows        []rowResult   `json:"rows,omitempty"`
	BatchID     string        `json:"batchId,omitempty"`
	Undo        *[]undoResult `json:"undo,omitempty"`
}

func fail(code, detail string) reply { return reply{Code: code, Detail: detail} }
func conflict(current Record, reason string) reply {
	return reply{Code: "conflict", Detail: reason, Current: &current}
}

type key struct{ actor, operation string }
type receipt struct {
	fingerprint [32]byte
	creators    map[string]bool
	body        []byte
}

// Book is the phase-one in-memory command executor for one calendar. Its lock
// is the reference transaction boundary for a later persistent adapter.
type Book struct {
	info             Calendar
	mu               sync.RWMutex
	events           map[string]Record
	receipts         map[key]receipt
	importedVersions map[string]int64
	history          map[string]map[string]beforeImport
}

func New(info Calendar) (*Book, error) {
	if !validID.MatchString(info.ID) || !validID.MatchString(info.OwnerID) ||
		(info.OwnerKind != "person" && info.OwnerKind != "group") || !validColor.MatchString(info.Color) {
		return nil, fmt.Errorf("invalid calendar identity, owner or color")
	}
	info.Color = strings.ToUpper(info.Color)
	return &Book{info: info, events: make(map[string]Record), receipts: make(map[key]receipt), importedVersions: make(map[string]int64), history: make(map[string]map[string]beforeImport)}, nil
}

// Execute accepts the same JSON a future local or server adapter will pass in.
// Domain failures are v1 JSON replies. A Go error indicates an encoding failure.
func (b *Book) Execute(p Principal, input []byte) ([]byte, error) {
	if !validID.MatchString(p.ID) || !p.canRead() {
		return json.Marshal(fail("forbidden", "calendar access required"))
	}
	var cmd command
	if err := contract.DecodeRequest(input, &cmd); err != nil {
		return json.Marshal(fail("invalid", err.Error()))
	}
	return b.execute(p, cmd)
}

func (b *Book) execute(p Principal, cmd command) ([]byte, error) {
	if cmd.CalendarID != b.info.ID {
		return json.Marshal(fail("not_found", "unknown calendar"))
	}
	if cmd.Op == "event.get" {
		b.mu.RLock()
		defer b.mu.RUnlock()
		record, ok := b.events[cmd.EventID]
		if !ok {
			return json.Marshal(fail("not_found", "unknown event"))
		}
		return json.Marshal(reply{Code: "ok", Record: &record})
	}
	if cmd.Op == "event.query" {
		b.mu.RLock()
		defer b.mu.RUnlock()
		return json.Marshal(query(*cmd.Window, b.info.Color, maps.Values(b.events)))
	}
	if !p.canCreate() {
		return json.Marshal(fail("forbidden", "write access required"))
	}
	if cmd.Op == "import.preview" {
		b.mu.RLock()
		defer b.mu.RUnlock()
		res, _ := b.applyBatch(p, cmd, false)
		return json.Marshal(res)
	}
	if cmd.Op == "import.undoPreview" {
		b.mu.RLock()
		defer b.mu.RUnlock()
		res, _ := b.undo(p, cmd, false)
		return json.Marshal(res)
	}

	normalized, err := json.Marshal(cmd)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256(normalized)
	id := key{p.ID, cmd.OperationID}
	b.mu.Lock()
	defer b.mu.Unlock()
	if old, ok := b.receipts[id]; ok {
		if old.fingerprint != hash {
			return json.Marshal(fail("operation_reused", "operation ID already has different input"))
		}
		for creator := range old.creators {
			if !p.canEdit(creator) {
				return json.Marshal(fail("forbidden", "permission changed since original operation"))
			}
		}
		return bytes.Clone(old.body), nil
	}
	var res reply
	var creators map[string]bool
	switch cmd.Op {
	case "import.commit":
		res, creators = b.applyBatch(p, cmd, true)
	case "import.undo":
		res, creators = b.undo(p, cmd, true)
	case "event.create", "event.replace", "event.delete":
		res = b.mutateEvent(p, cmd)
		if res.Record != nil {
			creators = map[string]bool{res.Record.CreatorID: true}
		}
	default:
		return json.Marshal(fail("invalid", "unsupported calendar operation"))
	}
	body, err := json.Marshal(res)
	if err == nil && res.Code == "ok" {
		b.receipts[id] = receipt{hash, creators, bytes.Clone(body)}
	}
	return body, err
}

func (b *Book) mutateEvent(p Principal, c command) reply {
	old, found := b.events[c.EventID]
	if c.Op == "event.create" {
		if strings.HasPrefix(c.EventID, "imp-") {
			return fail("invalid", "imp- IDs are reserved for imports")
		}
		if found {
			return conflict(old, "event ID has already been used")
		}
		old = Record{ID: c.EventID, CalendarID: b.info.ID, CreatorID: p.ID}
	} else {
		if !found {
			return fail("not_found", "unknown event")
		}
		if !p.canEdit(old.CreatorID) {
			return fail("forbidden", "cannot edit another creator's event")
		}
		if old.Deleted || old.Version != c.BaseVersion {
			return conflict(old, "event deleted or base version is stale")
		}
	}
	if c.Op == "event.delete" {
		old.Deleted = true
	} else {
		if err := c.Event.normalize(); err != nil {
			return fail("invalid", err.Error())
		}
		old.Event = *c.Event
	}
	old.Version++
	b.events[c.EventID] = old
	return reply{Code: "ok", Record: &old}
}
