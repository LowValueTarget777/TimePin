package calendar

import (
	"maps"
	"slices"
)

type beforeImport struct {
	Previous      Record
	SourceVersion int64
	AfterVersion  int64
}

// Only an explicitly undone import creation can be re-imported over its tombstone.
const undoneCreation int64 = -1

type undoResult struct {
	EventID string `json:"eventId"`
	Outcome string `json:"outcome"`
	Version int64  `json:"version"`
}

func (b *Book) undo(p Principal, cmd command, write bool) (reply, map[string]bool) {
	history, ok := b.history[cmd.BatchID]
	if !ok {
		return fail("not_found", "unknown import batch"), nil
	}
	owners := make(map[string]bool)
	rows := make([]undoResult, 0, len(history))
	for _, id := range slices.Sorted(maps.Keys(history)) {
		old := history[id]
		current := b.events[id]
		row := undoResult{EventID: id, Version: current.Version}
		if p.canEdit(current.CreatorID) {
			owners[current.CreatorID] = true
		}
		switch {
		case !p.canEdit(current.CreatorID):
			row.Outcome = "forbidden"
		case current.Version != old.AfterVersion:
			row.Outcome = "changed"
		default:
			row.Outcome = "restored"
			priorSource := old.SourceVersion
			if old.Previous.Version == 0 || old.Previous.Deleted {
				current.Deleted = true
				if old.Previous.Deleted {
					current.Event = old.Previous.Event
				}
				priorSource = undoneCreation
				row.Outcome = "removed"
			} else {
				current.Event = old.Previous.Event
				// Keep manual edits unacknowledged, and advance a previously clean source.
				if priorSource == old.Previous.Version {
					priorSource = current.Version + 1
				}
			}
			row.Version++
			if write {
				current.Version = row.Version
				b.events[id] = current
				b.importedVersions[id] = priorSource
			}
		}
		rows = append(rows, row)
	}
	return reply{Code: "ok", BatchID: cmd.BatchID, Undo: &rows}, owners
}
