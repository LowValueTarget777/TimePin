"""Reference protocol replica for the phase-4 tests. One SQLite file per account and instance."""

import json
import sqlite3
import uuid


class HTTPFailure(Exception):
    def __init__(self, status):
        super().__init__(f"HTTP {status}")
        self.status = status


class Replica:
    def __init__(self, path, transport, user):
        self.api = transport
        self.db = sqlite3.connect(path)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS identity(origin, user, singleton PRIMARY KEY CHECK(singleton=1));
            CREATE TABLE IF NOT EXISTS books(cal PRIMARY KEY, cursor, metadata);
            CREATE TABLE IF NOT EXISTS records(cal, id, version INTEGER, body, PRIMARY KEY(cal,id));
            CREATE TABLE IF NOT EXISTS drafts(op PRIMARY KEY, cal, id, body, status, UNIQUE(cal,id));
        """)
        with self.db:
            self.db.execute(
                "INSERT OR IGNORE INTO identity VALUES(?,?,1)", (transport.url, user)
            )
        if self.db.execute("SELECT origin,user FROM identity").fetchone() != (
            transport.url,
            user,
        ):
            self.db.close()
            raise ValueError("replica belongs to another instance or account")

    def forget(self, cal, reason):
        with self.db:
            self.db.execute("DELETE FROM records WHERE cal=?", (cal,))
            self.db.execute("DELETE FROM books WHERE cal=?", (cal,))
            self.db.execute("UPDATE drafts SET status=? WHERE cal=?", (reason, cal))

    def save_record(self, cal, record):
        if record["deleted"]:
            record = {"id": record["id"], "version": record["version"], "deleted": True}
        self.db.execute(
            """INSERT INTO records VALUES(?,?,?,?) ON CONFLICT(cal,id)
            DO UPDATE SET version=excluded.version,body=excluded.body
            WHERE excluded.version >= records.version""",
            (cal, record["id"], record["version"], json.dumps(record)),
        )

    def pull(self, cal, limit=128):
        """Fetch and atomically apply one page. A crash before commit leaves the old cursor."""
        row = self.db.execute("SELECT cursor FROM books WHERE cal=?", (cal,)).fetchone()
        request = {"calendarId": cal, "limit": limit}
        if row:
            request["cursor"] = row[0]
        try:
            page = self.api.request("sync/pull", request)
        except HTTPFailure as error:
            if error.status == 403:
                self.forget(cal, "revoked")
            elif error.status == 409 and row:
                self.forget(cal, "needs_review")
                return self.pull(cal, limit)
            raise
        if page["status"] == "revoked":
            self.forget(cal, "revoked")
            return page
        with self.db:
            for record in page["changes"]:
                self.save_record(cal, record)
            if "calendar" in page:
                self.db.execute(
                    "INSERT OR REPLACE INTO books VALUES(?,?,?)",
                    (cal, page["cursor"], json.dumps(page["calendar"])),
                )
            else:
                self.db.execute(
                    "UPDATE books SET cursor=? WHERE cal=?", (page["cursor"], cal)
                )
        return page

    def catch_up(self, cal, limit=512):
        count = 0
        while True:
            page = self.pull(cal, limit)
            count += len(page.get("changes", []))
            if not page.get("more"):
                return count

    def record(self, cal, event_id):
        row = self.db.execute(
            "SELECT body FROM records WHERE cal=? AND id=?", (cal, event_id)
        ).fetchone()
        return json.loads(row[0]) if row else None

    def draft(self, cal, event_id):
        row = self.db.execute(
            "SELECT status,body FROM drafts WHERE cal=? AND id=?", (cal, event_id)
        ).fetchone()
        return (row[0], json.loads(row[1])) if row else (None, None)

    def edit(self, cal, event_id, event, resolve=False):
        """The stored command is also the local edit overlay; no network write until push."""
        current = self.record(cal, event_id)
        if not current or current["deleted"]:
            raise ValueError("event unavailable")
        command = {
            "v": 1,
            "op": "event.replace",
            "calendarId": cal,
            "eventId": event_id,
            "operationId": uuid.uuid4().hex,
            "baseVersion": current["version"],
            "event": event,
        }
        with self.db:
            if resolve:
                self.db.execute(
                    "DELETE FROM drafts WHERE cal=? AND id=? AND status<>'pending'",
                    (cal, event_id),
                )
            self.db.execute(
                "INSERT INTO drafts VALUES(?,?,?,?,'pending')",
                (command["operationId"], cal, event_id, json.dumps(command)),
            )
        return command

    def push(self, cal, event_id):
        status, command = self.draft(cal, event_id)
        if status != "pending":
            raise ValueError("no pending edit")
        try:
            reply = self.api.request("commands", command)
        except HTTPFailure as error:
            if error.status == 403:
                self.forget(cal, "revoked")
            raise
        code = reply["code"]
        with self.db:
            if code == "ok":
                self.save_record(cal, reply["record"])
                self.db.execute(
                    "DELETE FROM drafts WHERE op=?", (command["operationId"],)
                )
            else:
                if "current" in reply:
                    self.save_record(cal, reply["current"])
                self.db.execute(
                    "UPDATE drafts SET status=? WHERE op=?",
                    (code, command["operationId"]),
                )
        return reply
