CREATE TABLE profiles (
 user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 display_name TEXT NOT NULL, avatar_version TEXT NOT NULL,
 revision TEXT NOT NULL, avatar BLOB
);
PRAGMA user_version=10;
