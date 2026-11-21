CREATE TABLE month_preferences (user_id TEXT PRIMARY KEY REFERENCES users(id), value TEXT NOT NULL);
PRAGMA user_version=4;
