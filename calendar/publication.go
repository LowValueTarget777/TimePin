package calendar

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
)

var ErrPublication = errors.New("invalid calendar snapshot")

// PublishedEvent carries live state only. The authenticated owner becomes its creator.
type PublishedEvent struct {
	ID            string `json:"id"`
	Version       int64  `json:"version"`
	SourceVersion int64  `json:"sourceVersion"`
	Event         Event  `json:"event"`
}

func (p PublishedEvent) Insert(tx *sql.Tx, cal, owner string) error {
	if !validID.MatchString(p.ID) || p.Version < 1 || p.Version >= 1<<53-1 || p.SourceVersion < 0 || p.SourceVersion > p.Version {
		return ErrPublication
	}
	if e := p.Event.normalize(); e != nil {
		return fmt.Errorf("%w: %s", ErrPublication, e)
	}
	r := Record{ID: p.ID, CalendarID: cal, CreatorID: owner, Version: p.Version, Event: p.Event}
	data, e := json.Marshal(r)
	if e != nil {
		return e
	}
	first, last := indexDays(r.Event)
	res, e := tx.Exec("INSERT INTO events(calendar_id,event_id,record,source_version,live,first_day,last_day) VALUES(?,?,?,?,1,?,?) ON CONFLICT(calendar_id,event_id) DO NOTHING", cal, p.ID, data, p.SourceVersion, first, last)
	if e != nil {
		return e
	}
	n, e := res.RowsAffected()
	if n == 0 && e == nil {
		return fmt.Errorf("%w: duplicate event ID", ErrPublication)
	}
	return e
}
