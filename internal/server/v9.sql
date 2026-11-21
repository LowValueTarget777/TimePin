-- Reserve retired IDs so old mobile replicas cannot republish a removed calendar.
CREATE TABLE deleted_calendars (
 id TEXT PRIMARY KEY, confirmed_by TEXT, chat_operation TEXT,
 UNIQUE(confirmed_by,chat_operation)
);
PRAGMA user_version=9;
