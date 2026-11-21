package calendar

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	contract "timepin/contract/v1"
)

const windowCandidates = `SELECT record FROM events WHERE calendar_id=? AND live=1 AND last_day>=? AND first_day<=?`

// ExecuteSQL runs inside the same transaction in which the caller resolved
// the current principal. Commit both the changes and receipt, or roll back both.
func ExecuteSQL(ctx context.Context, tx *sql.Tx, info Calendar, p Principal, raw []byte) ([]byte, error) {
	if !validID.MatchString(p.ID) || !p.canRead() {
		return json.Marshal(fail("forbidden", "calendar access required"))
	}
	var cmd command
	if err := contract.DecodeRequest(raw, &cmd); err != nil {
		return json.Marshal(fail("invalid", err.Error()))
	}
	if cmd.CalendarID != info.ID {
		return json.Marshal(fail("not_found", "unknown calendar"))
	}
	book, err := New(info)
	if err != nil {
		return nil, err
	}
	if cmd.Op == "event.query" {
		w, err := cmd.Window.parse()
		if err != nil {
			return json.Marshal(fail("invalid", err.Error()))
		}
		// Across two IANA zones a civil date can differ by two days. The
		// domain query applies exact half-open bounds to these SQL candidates.
		first, last := w.first.AddDate(0, 0, -2), w.limit.AddDate(0, 0, 2)
		low, high := "0001-01-01", "9999-12-31"
		if first.Year() >= 1 {
			low = first.Format(time.DateOnly)
		}
		if last.Year() <= 9999 {
			high = last.Format(time.DateOnly)
		}
		rows, err := tx.QueryContext(ctx, windowCandidates, info.ID, low, high)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		var scanErr error
		result := query(*cmd.Window, info.Color, func(yield func(Record) bool) {
			for rows.Next() {
				var raw []byte
				var r Record
				if scanErr = rows.Scan(&raw); scanErr != nil {
					return
				}
				if scanErr = json.Unmarshal(raw, &r); scanErr != nil {
					return
				}
				if !yield(r) {
					return
				}
			}
		})
		if scanErr != nil {
			return nil, scanErr
		}
		if err = rows.Err(); err != nil {
			return nil, err
		}
		return json.Marshal(result)
	}
	k := key{p.ID, cmd.OperationID}
	if cmd.OperationID != "" && cmd.Op != "import.preview" {
		var fingerprint, owners, body []byte
		err = tx.QueryRowContext(ctx, "SELECT fingerprint,creators,body FROM receipts WHERE calendar_id=? AND actor=? AND operation_id=?", info.ID, p.ID, cmd.OperationID).Scan(&fingerprint, &owners, &body)
		if err == nil {
			var old receipt
			if len(fingerprint) != len(old.fingerprint) {
				return nil, fmt.Errorf("invalid stored fingerprint")
			}
			copy(old.fingerprint[:], fingerprint)
			old.body = body
			if err = json.Unmarshal(owners, &old.creators); err != nil {
				return nil, err
			}
			book.receipts[k] = old
			return book.execute(p, cmd)
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
	}

	ids := []string{cmd.EventID}
	if cmd.BatchID != "" {
		var exists bool
		if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM import_batches WHERE calendar_id=? AND id=?)", info.ID, cmd.BatchID).Scan(&exists); err != nil {
			return nil, err
		}
		if exists {
			history := make(map[string]beforeImport)
			ids = nil
			rows, err := tx.QueryContext(ctx, "SELECT event_id,previous,source_version,after_version FROM import_history WHERE calendar_id=? AND batch_id=?", info.ID, cmd.BatchID)
			if err != nil {
				return nil, err
			}
			for rows.Next() {
				var id string
				var prev []byte
				var entry beforeImport
				if err = rows.Scan(&id, &prev, &entry.SourceVersion, &entry.AfterVersion); err == nil && prev != nil {
					err = json.Unmarshal(prev, &entry.Previous)
				}
				if err != nil {
					rows.Close()
					return nil, err
				}
				ids = append(ids, id)
				history[id] = entry
			}
			err = rows.Err()
			rows.Close()
			if err != nil {
				return nil, err
			}
			book.history[cmd.BatchID] = history
		}
	}
	if cmd.Batch != nil {
		ids = make([]string, len(cmd.Batch.Rows))
		for i, row := range cmd.Batch.Rows {
			ids[i] = importID(info.ID, p.ID, cmd, i, row.ExternalID)
		}
	}
	keys, err := json.Marshal(ids)
	if err != nil {
		return nil, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT record,source_version FROM events WHERE calendar_id=? AND event_id IN (SELECT value FROM json_each(?))", info.ID, string(keys))
	if err != nil {
		return nil, err
	}
	before := make(map[string]Record)
	oldSource := make(map[string]int64)
	for rows.Next() {
		var data []byte
		var source int64
		var r Record
		if err = rows.Scan(&data, &source); err == nil {
			err = json.Unmarshal(data, &r)
		}
		if err != nil {
			rows.Close()
			return nil, err
		}
		before[r.ID] = r
		book.events[r.ID] = r
		book.importedVersions[r.ID] = source
		oldSource[r.ID] = source
	}
	if err = rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	if err = rows.Close(); err != nil {
		return nil, err
	}
	body, err := book.execute(p, cmd)
	if err != nil {
		return nil, err
	}
	saved, exists := book.receipts[k]
	if !exists {
		return body, nil
	}
	if cmd.Op == "import.commit" {
		if _, err = tx.ExecContext(ctx, "INSERT INTO import_batches(calendar_id,id) VALUES(?,?)", info.ID, derivedID("batch-", info.ID, p.ID, cmd.OperationID)); err != nil {
			return nil, err
		}
	}
	owners, err := json.Marshal(saved.creators)
	if err != nil {
		return nil, err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO receipts VALUES(?,?,?,?,?,?)", info.ID, p.ID, cmd.OperationID, saved.fingerprint[:], owners, body); err != nil {
		return nil, err
	}
	for id, r := range book.events {
		prev := before[id]
		if prev.Version == r.Version {
			if oldSource[id] != book.importedVersions[id] {
				_, err = tx.ExecContext(ctx, "UPDATE events SET source_version=? WHERE calendar_id=? AND event_id=?", book.importedVersions[id], info.ID, id)
				if err != nil {
					return nil, err
				}
			}
			continue
		}
		data, err := json.Marshal(r)
		if err != nil {
			return nil, err
		}
		first, last := indexDays(r.Event)
		_, err = tx.ExecContext(ctx, `INSERT INTO events(calendar_id,event_id,record,source_version,live,first_day,last_day) VALUES(?,?,?,?,?,?,?)
     ON CONFLICT(calendar_id,event_id) DO UPDATE SET record=excluded.record,source_version=excluded.source_version,live=excluded.live,first_day=excluded.first_day,last_day=excluded.last_day`, info.ID, id, data, book.importedVersions[id], !r.Deleted, first, last)
		if err != nil {
			return nil, err
		}
		if cmd.Op == "import.commit" {
			var previous any
			if prev.Version > 0 {
				previous, err = json.Marshal(prev)
				if err != nil {
					return nil, err
				}
			}
			_, err = tx.ExecContext(ctx, "INSERT INTO import_history VALUES(?,?,?,?,?,?)", info.ID, derivedID("batch-", info.ID, p.ID, cmd.OperationID), id, previous, oldSource[id], r.Version)
			if err != nil {
				return nil, err
			}
		}
	}
	return body, nil
}
func indexDays(e Event) (string, string) {
	first, last := e.Span.Start[:10], e.Span.End[:10]
	if e.Repeat != nil {
		last = "9999-12-31"
		if e.Repeat.Until != "" {
			b, _ := e.Span.bounds()
			until, _ := date(e.Repeat.Until)
			end := until.AddDate(0, 0, days(b.first, b.last))
			if end.Year() <= 9999 {
				last = end.Format(time.DateOnly)
			}
		}
		for _, ex := range e.Exceptions {
			if ex.Move != nil {
				first = min(first, ex.Move.Start[:10])
				last = max(last, ex.Move.End[:10])
			}
		}
	}
	return first, last
}
