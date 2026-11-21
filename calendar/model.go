// Package calendar implements the shared TimePin v1 calendar behavior.
package calendar

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
	"sync/atomic"
	"time"
	_ "time/tzdata" // Windows and minimal containers also need IANA timezone rules.
)

type Calendar struct {
	ID        string `json:"id"`
	OwnerKind string `json:"ownerKind"`
	OwnerID   string `json:"ownerId"`
	Color     string `json:"color"`
}

// Principal is resolved by the host for this calendar on every call.
// Role and identity are never taken from the command JSON.
type Principal struct {
	ID   string `json:"id"`
	Role string `json:"role"`
}

func (p Principal) canRead() bool {
	return p.canCreate() || p.Role == "reader"
}
func (p Principal) canCreate() bool {
	return p.Role == "manager" || p.Role == "editor" || p.Role == "contributor"
}
func (p Principal) canEdit(creator string) bool {
	return p.Role == "manager" || p.Role == "editor" || (p.Role == "contributor" && p.ID == creator)
}

type Span struct {
	Kind  string `json:"kind"`
	Start string `json:"start"`
	End   string `json:"end"`
	Zone  string `json:"zone,omitempty"`
}
type Repeat struct {
	Frequency string `json:"frequency"`
	Interval  int    `json:"interval"`
	Until     string `json:"until,omitempty"`
}
type Exception struct {
	On     string `json:"on"`
	Cancel bool   `json:"cancel,omitempty"`
	Move   *Span  `json:"move,omitempty"`
}
type Event struct {
	Title        string      `json:"title"`
	Span         Span        `json:"span"`
	Color        string      `json:"color,omitempty"`
	Participants []string    `json:"participants,omitempty"`
	Repeat       *Repeat     `json:"repeat,omitempty"`
	Exceptions   []Exception `json:"exceptions,omitempty"`
}
type Record struct {
	ID         string `json:"id"`
	CalendarID string `json:"calendarId"`
	CreatorID  string `json:"creatorId"`
	Version    int64  `json:"version"`
	Deleted    bool   `json:"deleted"`
	Event      Event  `json:"event"`
}
type Window struct {
	ParticipantID string `json:"participantId,omitempty"`
	Start         string `json:"start"`
	End           string `json:"end"`
	Zone          string `json:"zone"`
}
type Occurrence struct {
	EventID string `json:"eventId"`
	On      string `json:"on"`
	Title   string `json:"title"`
	Color   string `json:"color"`
	Span    Span   `json:"span"`
	Version int64  `json:"version"`
}

var validID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`)
var validColor = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// A batch typically uses one zone for all rows. Keep only its last lookup.
var lastZone atomic.Pointer[time.Location]

func location(name string) (*time.Location, error) {
	if name == "" || name == "Local" {
		return nil, fmt.Errorf("use an explicit IANA timezone")
	}
	if name == "UTC" {
		return time.UTC, nil
	}
	if cached := lastZone.Load(); cached != nil && cached.String() == name {
		return cached, nil
	}
	loc, err := time.LoadLocation(name)
	if err == nil {
		lastZone.Store(loc)
	}
	return loc, err
}
func date(s string) (time.Time, error) {
	t, err := time.Parse(time.DateOnly, s)
	if err != nil || t.Year() < 1 {
		return time.Time{}, fmt.Errorf("invalid calendar date %q", s)
	}
	return t, nil
}

// civil uses UTC only as a coordinate system for Gregorian dates.
func civil(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}
func days(a, b time.Time) int {
	return int((b.Unix() - a.Unix()) / 86400)
}

type bounds struct {
	start, end  time.Time
	first, last time.Time
}

func (s Span) bounds() (bounds, error) {
	var a, b time.Time
	var err error
	switch s.Kind {
	case "date":
		a, err = date(s.Start)
		if err == nil {
			b, err = date(s.End)
		}
	case "timed":
		loc, e := location(s.Zone)
		if e != nil {
			return bounds{}, e
		}
		parse := func(value string) (time.Time, error) {
			t, e := time.Parse(time.RFC3339, value)
			if e != nil {
				return time.Time{}, e
			}
			local := t.In(loc)
			_, off := t.Zone()
			_, actual := local.Zone()
			if off != actual || local.Year() < 1 || local.Year() > 9999 {
				return time.Time{}, fmt.Errorf("timestamp %q does not match zone %s", value, s.Zone)
			}
			return local, nil
		}
		a, err = parse(s.Start)
		if err == nil {
			b, err = parse(s.End)
		}
	default:
		return bounds{}, fmt.Errorf("unknown span kind")
	}
	if err != nil {
		return bounds{}, err
	}
	if !a.Before(b) {
		return bounds{}, fmt.Errorf("end must follow start")
	}
	return bounds{a, b, civil(a), civil(b)}, nil
}

// normalize runs after wire validation and before storing or comparing content.
func (e *Event) normalize() error {
	if strings.TrimSpace(e.Title) == "" {
		return fmt.Errorf("event title is blank")
	}
	b, err := e.Span.bounds()
	if err != nil {
		return err
	}
	if e.Span.Kind == "timed" {
		e.Span.Start = b.start.Format(time.RFC3339)
		e.Span.End = b.end.Format(time.RFC3339)
	}
	e.Color = strings.ToUpper(e.Color)
	slices.Sort(e.Participants)
	if len(e.Participants) == 0 {
		e.Participants = nil
	}
	if len(e.Exceptions) == 0 {
		e.Exceptions = nil
	}
	if e.Repeat == nil {
		if len(e.Exceptions) > 0 {
			return fmt.Errorf("exceptions require a repeat rule")
		}
		return nil
	}
	if e.Repeat.Until != "" {
		end, err := date(e.Repeat.Until)
		if err != nil || end.Before(b.first) {
			return fmt.Errorf("invalid recurrence end")
		}
	}
	slices.SortFunc(e.Exceptions, func(a, b Exception) int { return strings.Compare(a.On, b.On) })
	for i, x := range e.Exceptions {
		d, err := date(x.On)
		if err != nil || (i > 0 && x.On == e.Exceptions[i-1].On) || d.Before(b.first) ||
			(e.Repeat.Until != "" && x.On > e.Repeat.Until) {
			return fmt.Errorf("invalid or duplicate exception date %q", x.On)
		}
		slot, ok := e.Repeat.slot(b.first, e.Repeat.index(b.first, d))
		if !ok || !slot.Equal(d) {
			return fmt.Errorf("exception does not identify an occurrence")
		}
		if _, ok := e.Span.at(b, d); !ok {
			return fmt.Errorf("exception refers to a skipped wall time")
		}
		if x.Move != nil {
			moved, err := x.Move.bounds()
			if err != nil {
				return err
			}
			if x.Move.Kind == "timed" {
				x.Move.Start = moved.start.Format(time.RFC3339)
				x.Move.End = moved.end.Format(time.RFC3339)
			}
		}
	}
	return nil
}
