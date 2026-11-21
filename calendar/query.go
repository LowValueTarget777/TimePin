package calendar

import (
	"cmp"
	"fmt"
	"iter"
	"slices"
	"time"
)

// index seeks near the visible window, independently of the age of the series.
func (r Repeat) index(first, d time.Time) int {
	if d.Before(first) {
		return 0
	}
	switch r.Frequency {
	case "weekly":
		return days(first, d) / (7 * r.Interval)
	case "monthly":
		return ((d.Year()-first.Year())*12 + int(d.Month()-first.Month())) / r.Interval
	case "yearly":
		return (d.Year() - first.Year()) / r.Interval
	default:
		return days(first, d) / r.Interval
	}
}

// slot returns the target month even when its requested day does not exist.
func (r Repeat) slot(first time.Time, index int) (time.Time, bool) {
	n := index * r.Interval
	switch r.Frequency {
	case "daily":
		return first.AddDate(0, 0, n), true
	case "weekly":
		return first.AddDate(0, 0, 7*n), true
	}
	months := n
	if r.Frequency == "yearly" {
		months *= 12
	}
	target := time.Date(first.Year(), first.Month()+time.Month(months), 1, 0, 0, 0, 0, time.UTC)
	d := target.AddDate(0, 0, first.Day()-1)
	if d.Month() != target.Month() {
		return target, false
	}
	return d, true
}

// wallOn skips gaps and prefers the original UTC offset during a fold.
// Checking both neighboring offsets also works for non-hour DST changes.
func wallOn(d, template time.Time) (time.Time, bool) {
	loc := template.Location()
	wall := time.Date(d.Year(), d.Month(), d.Day(), template.Hour(), template.Minute(), template.Second(), 0, time.UTC)
	guess := time.Date(d.Year(), d.Month(), d.Day(), template.Hour(), template.Minute(), template.Second(), 0, loc)
	_, preferred := template.Zone()
	var best time.Time
	found := false
	for _, candidate := range []time.Time{guess, guess.Add(-24 * time.Hour), guess.Add(24 * time.Hour)} {
		_, off := candidate.Zone()
		t := wall.Add(-time.Duration(off) * time.Second).In(loc)
		_, actual := t.Zone()
		if actual != off {
			continue
		}
		if off == preferred {
			return t, true
		}
		if !found || t.Before(best) {
			best, found = t, true
		}
	}
	return best, found
}
func (s Span) at(b bounds, d time.Time) (Span, bool) {
	if d.Equal(b.first) {
		return s, true
	}
	endDay := d.AddDate(0, 0, days(b.first, b.last))
	if endDay.Year() > 9999 || endDay.Year() < 1 {
		return Span{}, false
	}
	if s.Kind == "date" {
		return Span{Kind: "date", Start: d.Format(time.DateOnly), End: endDay.Format(time.DateOnly)}, true
	}
	a, ok := wallOn(d, b.start)
	if !ok {
		return Span{}, false
	}
	end, ok := wallOn(endDay, b.end)
	if !ok || !end.After(a) {
		return Span{}, false
	}
	return Span{Kind: "timed", Start: a.Format(time.RFC3339), End: end.Format(time.RFC3339), Zone: s.Zone}, true
}

type dateWindow struct {
	first, limit time.Time
	start, end   time.Time
	loc          *time.Location
}

func midnight(d time.Time, loc *time.Location) time.Time {
	// Choose the first instant of the requested date when midnight repeats.
	t := time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, loc)
	if civil(t).Before(d) {
		_, next := t.ZoneBounds()
		if !next.IsZero() {
			return next
		}
	}
	_, old := t.Add(-24 * time.Hour).Zone()
	_, now := t.Zone()
	alt := t.Add(time.Duration(now-old) * time.Second)
	if alt.Before(t) && civil(alt).Equal(d) && alt.Hour() == 0 && alt.Minute() == 0 && alt.Second() == 0 {
		return alt
	}
	return t
}
func (w Window) parse() (dateWindow, error) {
	a, err := date(w.Start)
	if err != nil {
		return dateWindow{}, err
	}
	b, err := date(w.End)
	if err != nil {
		return dateWindow{}, err
	}
	if days(a, b) < 1 || days(a, b) > 366 {
		return dateWindow{}, fmt.Errorf("query window must cover 1 to 366 dates")
	}
	loc, err := location(w.Zone)
	if err != nil {
		return dateWindow{}, err
	}
	return dateWindow{a, b, midnight(a, loc), midnight(b, loc), loc}, nil
}

type ranked struct {
	occurrence Occurrence
	day        string
	instant    int64
}

func (w dateWindow) rank(o Occurrence) (ranked, bool) {
	if o.Span.Kind == "date" {
		return ranked{o, o.Span.Start, 0}, o.Span.Start < w.limit.Format(time.DateOnly) && o.Span.End > w.first.Format(time.DateOnly)
	}
	a, _ := time.Parse(time.RFC3339, o.Span.Start)
	b, _ := time.Parse(time.RFC3339, o.Span.End)
	return ranked{o, a.In(w.loc).Format(time.DateOnly), a.Unix()}, w.start.Before(w.end) && a.Before(w.end) && b.After(w.start)
}

const maxOccurrences = 10000

// The in-memory executor supplies its calendar's records. An indexed storage
// adapter can supply only plausible candidates and reuse the same expansion.
func query(window Window, defaultColor string, candidates iter.Seq[Record]) reply {
	w, err := window.parse()
	if err != nil {
		return fail("invalid", err.Error())
	}
	list := make([]ranked, 0)
	for record := range candidates {
		if record.Deleted || (window.ParticipantID != "" && !slices.Contains(record.Event.Participants, window.ParticipantID)) {
			continue
		}
		e := record.Event
		color := e.Color
		if color == "" {
			color = defaultColor
		}
		add := func(on string, span Span) bool {
			item, visible := w.rank(Occurrence{record.ID, on, e.Title, color, span, record.Version})
			if visible {
				list = append(list, item)
			}
			return len(list) <= maxOccurrences
		}
		if e.Repeat == nil {
			if !add(e.Span.Start[:10], e.Span) {
				return fail("window_too_dense", "narrow the requested window")
			}
			continue
		}
		excluded := make(map[string]bool, len(e.Exceptions))
		for _, x := range e.Exceptions {
			excluded[x.On] = true
			// A moved occurrence can enter the view from outside the original series window.
			if x.Move != nil && !add(x.On, *x.Move) {
				return fail("window_too_dense", "narrow the requested window")
			}
		}
		b, _ := e.Span.bounds()
		low, high := w.first, w.limit
		if e.Span.Kind == "timed" {
			low, high = civil(w.start.In(b.start.Location())), civil(w.end.In(b.start.Location())).AddDate(0, 0, 1)
		}
		low = low.AddDate(0, 0, -days(b.first, b.last))
		for i := e.Repeat.index(b.first, low); ; i++ {
			d, exists := e.Repeat.slot(b.first, i)
			if !d.Before(high) || d.Year() > 9999 {
				break
			}
			on := d.Format(time.DateOnly)
			if e.Repeat.Until != "" && on > e.Repeat.Until {
				break
			}
			if !exists || excluded[on] {
				continue
			}
			if s, ok := e.Span.at(b, d); ok && !add(on, s) {
				return fail("window_too_dense", "narrow the requested window")
			}
		}
	}
	slices.SortFunc(list, func(a, b ranked) int {
		if n := cmp.Compare(a.day, b.day); n != 0 {
			return n
		}
		if n := cmp.Compare(a.occurrence.Span.Kind, b.occurrence.Span.Kind); n != 0 {
			return n
		}
		if n := cmp.Compare(a.instant, b.instant); n != 0 {
			return n
		}
		if n := cmp.Compare(a.occurrence.EventID, b.occurrence.EventID); n != 0 {
			return n
		}
		return cmp.Compare(a.occurrence.On, b.occurrence.On)
	})
	out := make([]Occurrence, len(list))
	for i, item := range list {
		out[i] = item.occurrence
	}
	return reply{Code: "ok", Occurrences: &out}
}
