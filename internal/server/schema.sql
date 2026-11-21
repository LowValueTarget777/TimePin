CREATE TABLE users (
 id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE, salt BLOB NOT NULL,
 password_hash BLOB NOT NULL
);
CREATE TABLE sessions (digest BLOB PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users, expires INTEGER NOT NULL);
CREATE INDEX sessions_expiry ON sessions(expires);
CREATE TABLE teams (id TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE members (team TEXT NOT NULL REFERENCES teams, user_id TEXT NOT NULL REFERENCES users, role TEXT NOT NULL CHECK(role IN ('admin','member')), PRIMARY KEY(team,user_id));
CREATE INDEX members_user ON members(user_id,team);
CREATE TABLE calendars (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL,
 person TEXT REFERENCES users, team TEXT REFERENCES teams,
 CHECK((person IS NULL) <> (team IS NULL))
);
CREATE TABLE grants (calendar_id TEXT NOT NULL REFERENCES calendars, subject_kind TEXT NOT NULL CHECK(subject_kind IN ('user','group')), subject TEXT NOT NULL, rank INTEGER NOT NULL CHECK(rank BETWEEN 1 AND 4), PRIMARY KEY(calendar_id,subject_kind,subject));
CREATE INDEX grants_subject ON grants(subject_kind,subject);
CREATE VIEW access_paths AS
 SELECT id calendar_id, person user_id, 4 rank FROM calendars WHERE person IS NOT NULL
 UNION ALL SELECT c.id,m.user_id,CASE m.role WHEN 'admin' THEN 4 ELSE 1 END FROM calendars c JOIN members m ON c.team=m.team
 UNION ALL SELECT calendar_id,subject,rank FROM grants WHERE subject_kind='user'
 UNION ALL SELECT g.calendar_id,m.user_id,g.rank FROM grants g JOIN members m ON g.subject=m.team WHERE g.subject_kind='group';
CREATE TABLE hidden_calendars (user_id TEXT NOT NULL REFERENCES users, calendar_id TEXT NOT NULL REFERENCES calendars, PRIMARY KEY(user_id,calendar_id));
CREATE TABLE invites (id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, kind TEXT NOT NULL, target TEXT NOT NULL, role TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE INDEX invite_target ON invites(kind,target);
CREATE TABLE shares (calendar_id TEXT PRIMARY KEY REFERENCES calendars, token TEXT NOT NULL UNIQUE);
CREATE TABLE admin_receipts (actor TEXT NOT NULL REFERENCES users, operation_id TEXT NOT NULL, fingerprint BLOB NOT NULL, body BLOB NOT NULL, PRIMARY KEY(actor,operation_id));
CREATE TABLE events (calendar_id TEXT NOT NULL REFERENCES calendars, event_id TEXT NOT NULL, record BLOB NOT NULL, source_version INTEGER NOT NULL, live INTEGER NOT NULL, first_day TEXT NOT NULL,last_day TEXT NOT NULL, PRIMARY KEY(calendar_id,event_id));
CREATE INDEX event_window ON events(calendar_id,last_day,first_day) WHERE live=1;
CREATE TABLE receipts (calendar_id TEXT NOT NULL REFERENCES calendars, actor TEXT NOT NULL, operation_id TEXT NOT NULL, fingerprint BLOB NOT NULL, creators BLOB NOT NULL, body BLOB NOT NULL, PRIMARY KEY(calendar_id,actor,operation_id));
CREATE TABLE import_history (calendar_id TEXT NOT NULL REFERENCES calendars, batch_id TEXT NOT NULL, event_id TEXT NOT NULL, previous BLOB, source_version INTEGER NOT NULL, after_version INTEGER NOT NULL, PRIMARY KEY(calendar_id,batch_id,event_id));
PRAGMA user_version=1;
