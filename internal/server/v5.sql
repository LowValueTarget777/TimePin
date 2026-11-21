CREATE TABLE instance_admins(user_id TEXT PRIMARY KEY REFERENCES users(id));
CREATE TABLE disabled_users(user_id TEXT PRIMARY KEY REFERENCES users(id));
CREATE TABLE instance_policy(id INTEGER PRIMARY KEY CHECK(id=1), registration INTEGER, model_enabled INTEGER, base TEXT, model TEXT, api_key TEXT);
INSERT INTO instance_policy(id) VALUES(1);
PRAGMA user_version=5;
