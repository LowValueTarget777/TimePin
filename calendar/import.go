package calendar

import (
	"crypto/sha256"
	"fmt"
	"reflect"
	"strconv"
	"strings"
)

type batch struct {
	Source string      `json:"source"`
	Rows   []importRow `json:"rows"`
}
type importRow struct {
	ExternalID    string `json:"externalId,omitempty"`
	BaseVersion   *int64 `json:"baseVersion,omitempty"`
	AcceptCurrent bool   `json:"acceptCurrent,omitempty"`
	Event         Event  `json:"event"`
}
type rowResult struct {
	BaseVersion int64  `json:"baseVersion"`
	Index       int    `json:"index"`
	EventID     string `json:"eventId"`
	Version     int64  `json:"version,omitempty"`
	Outcome     string `json:"outcome"`
}

func derivedID(prefix string, parts ...string) string {
	// Contract identifiers cannot contain NUL, so tuple boundaries are unique.
	sum := sha256.Sum256([]byte(strings.Join(parts, "\x00")))
	return fmt.Sprintf("%s%x", prefix, sum[:16])
}
func importID(calendarID, actor string, c command, i int, external string) string {
	if external != "" {
		return derivedID("imp-", "source", calendarID, c.Batch.Source, external)
	}
	batchID := derivedID("batch-", calendarID, actor, c.OperationID)
	return derivedID("imp-", "operation", batchID, strconv.Itoa(i))
}

func (b *Book) applyBatch(p Principal, c command, write bool) (reply, map[string]bool) {
	batchID := derivedID("batch-", b.info.ID, p.ID, c.OperationID)
	out := reply{Code: "ok", Rows: make([]rowResult, 0, len(c.Batch.Rows))}
	if write {
		out.BatchID = batchID
	}
	creators := make(map[string]bool)
	changed := make([]Record, 0)
	seen := make(map[string]bool)
	for i, row := range c.Batch.Rows {
		if err := row.Event.normalize(); err != nil {
			return fail("invalid", fmt.Sprintf("row %d: %v", i, err)), nil
		}
		id := importID(b.info.ID, p.ID, c, i, row.ExternalID)
		if row.ExternalID != "" {
			if seen[row.ExternalID] {
				return fail("invalid", fmt.Sprintf("duplicate external ID at row %d", i)), nil
			}
			seen[row.ExternalID] = true
		}
		if row.AcceptCurrent && (row.ExternalID == "" || row.BaseVersion == nil || *row.BaseVersion == 0) {
			return fail("invalid", "acceptCurrent requires an externalId and a positive baseVersion"), nil
		}
		old, exists := b.events[id]
		result := rowResult{Index: i, EventID: id, BaseVersion: old.Version, Version: old.Version}
		switch {
		case exists && !p.canEdit(old.CreatorID):
			result.Outcome = "forbidden"
		case row.BaseVersion != nil && *row.BaseVersion != old.Version:
			result.Outcome = "conflict"
		case exists && old.Deleted && b.importedVersions[id] == undoneCreation:
			old.Deleted = false
			old.Version++
			old.Event = row.Event
			changed = append(changed, old)
			result.Outcome = "created"
			result.Version = old.Version
		case exists && (old.Deleted || (old.Version != b.importedVersions[id] && !row.AcceptCurrent)):
			result.Outcome = "conflict"
		case exists && reflect.DeepEqual(old.Event, row.Event):
			result.Outcome = "unchanged"
		default:
			if exists {
				result.Outcome = "updated"
			} else {
				old = Record{ID: id, CalendarID: b.info.ID, CreatorID: p.ID}
				result.Outcome = "created"
			}
			old.Version++
			old.Event = row.Event
			changed = append(changed, old)
			result.Version = old.Version
		}
		if old.CreatorID != "" && result.Outcome != "forbidden" {
			creators[old.CreatorID] = true
		}
		out.Rows = append(out.Rows, result)
	}
	// Semantic errors abort before any writes. Conflicts and denials are row
	// outcomes; authorized independent rows commit with the batch receipt.
	if write {
		history := make(map[string]beforeImport, len(changed))
		for _, record := range changed {
			history[record.ID] = beforeImport{b.events[record.ID], b.importedVersions[record.ID], record.Version}
			b.events[record.ID] = record
		}
		b.history[batchID] = history
		for _, row := range out.Rows {
			switch row.Outcome {
			case "created", "updated", "unchanged":
				b.importedVersions[row.EventID] = row.Version
			}
		}
	}
	return out, creators
}
