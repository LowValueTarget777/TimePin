CREATE TABLE import_batches (seq INTEGER PRIMARY KEY, calendar_id TEXT NOT NULL REFERENCES calendars, id TEXT NOT NULL, UNIQUE(calendar_id,id));
INSERT INTO import_batches(calendar_id,id) SELECT calendar_id,json_extract(CAST(body AS TEXT),'$.batchId')
 FROM receipts WHERE json_type(CAST(body AS TEXT),'$.batchId')='text' ORDER BY rowid;
CREATE TABLE api_tokens (id TEXT PRIMARY KEY, digest BLOB NOT NULL UNIQUE, user_id TEXT NOT NULL REFERENCES users,
 calendar_id TEXT NOT NULL REFERENCES calendars, label TEXT NOT NULL, role TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE INDEX tokens_owner ON api_tokens(user_id);
CREATE TABLE ai_members (user_id TEXT PRIMARY KEY REFERENCES users);
CREATE TABLE ai_turns (user_id TEXT NOT NULL REFERENCES users, op_id TEXT NOT NULL, fingerprint BLOB NOT NULL,
 calendar_id TEXT NOT NULL REFERENCES calendars, role TEXT NOT NULL, reply BLOB NOT NULL, pending BLOB, PRIMARY KEY(user_id,op_id));
PRAGMA user_version=2;
