-- One change position per event, including deletions. Event payloads stay in events.
ALTER TABLE events ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0;
WITH numbered AS MATERIALIZED (
 SELECT rowid AS rid,row_number() OVER (PARTITION BY calendar_id ORDER BY event_id) AS seq FROM events
)
UPDATE events SET sync_seq=numbered.seq FROM numbered WHERE events.rowid=numbered.rid;
CREATE INDEX event_sync ON events(calendar_id,sync_seq);
CREATE TABLE sync_state (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), secret BLOB NOT NULL
);
INSERT INTO sync_state VALUES(1,randomblob(32));
CREATE TRIGGER sync_insert AFTER INSERT ON events BEGIN
 UPDATE events SET sync_seq=(SELECT coalesce(max(sync_seq),0)+1 FROM events WHERE calendar_id=NEW.calendar_id)
 WHERE rowid=NEW.rowid;
END;
-- Source-only import bookkeeping and receipt replay do not change the public record.
CREATE TRIGGER sync_update AFTER UPDATE OF record ON events WHEN NEW.record <> OLD.record BEGIN
 UPDATE events SET sync_seq=(SELECT max(sync_seq)+1 FROM events WHERE calendar_id=NEW.calendar_id)
 WHERE rowid=NEW.rowid;
END;
PRAGMA user_version=3;
