DROP TABLE instance_admins;
CREATE TABLE console_account (
    id INTEGER PRIMARY KEY CHECK (id=1),
    name TEXT NOT NULL, salt BLOB NOT NULL, password_hash BLOB NOT NULL
);
CREATE TABLE console_sessions (digest BLOB PRIMARY KEY, expires INTEGER NOT NULL);
CREATE INDEX console_session_expiry ON console_sessions(expires);
CREATE TABLE console_receipts (
    actor TEXT NOT NULL, operation_id TEXT NOT NULL, fingerprint BLOB NOT NULL, body BLOB NOT NULL,
    PRIMARY KEY(actor, operation_id)
);
PRAGMA user_version=6;
