-- A NULL calendar is an explicitly issued all-calendars token.
CREATE TABLE api_tokens_v8 (
 id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, user_id TEXT NOT NULL REFERENCES users,
 calendar_id TEXT REFERENCES calendars, label TEXT NOT NULL, role TEXT NOT NULL, expires INTEGER NOT NULL
);
INSERT INTO api_tokens_v8 SELECT id,digest,user_id,calendar_id,label,role,expires FROM api_tokens;
DROP TABLE api_tokens;
ALTER TABLE api_tokens_v8 RENAME TO api_tokens;
CREATE INDEX tokens_owner ON api_tokens(user_id);
PRAGMA user_version=8;
