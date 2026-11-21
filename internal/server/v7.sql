CREATE TABLE console_audit (
 seq INTEGER PRIMARY KEY, at INTEGER NOT NULL, actor TEXT NOT NULL,
 action TEXT NOT NULL, target TEXT NOT NULL, subject TEXT NOT NULL, setting TEXT NOT NULL
);
CREATE TABLE account_recovery (
 user_id TEXT PRIMARY KEY REFERENCES users, digest BLOB NOT NULL UNIQUE, expires INTEGER NOT NULL
);
CREATE INDEX sessions_by_user ON sessions(user_id,expires);
CREATE INDEX calendars_by_team ON calendars(team);
CREATE INDEX calendars_by_person ON calendars(person);
CREATE TABLE console_backups (id TEXT PRIMARY KEY, file TEXT NOT NULL, at INTEGER NOT NULL, bytes INTEGER NOT NULL);
PRAGMA user_version=7;
