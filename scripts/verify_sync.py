"""Run real HTTP/1.1 replica acceptance against a disposable TimePin instance."""

import argparse
import http.client
import json
import secrets
import sqlite3
import tempfile
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.cookies import SimpleCookie
from pathlib import Path
from threading import Barrier
from urllib.parse import urlsplit

from sync_client import HTTPFailure, Replica


class MeteredReader:
    """Count HTTP status, headers, chunk framing and entity bytes consumed by http.client."""

    def __init__(self, stream, counts):
        self.stream = stream
        self.counts = counts

    def read(self, size=-1):
        data = self.stream.read(size)
        self.counts["down"] += len(data)
        return data

    def readline(self, size=-1):
        data = self.stream.readline(size)
        self.counts["down"] += len(data)
        return data

    def close(self):
        self.stream.close()

    def flush(self):
        self.stream.flush()


class Wire:
    def __init__(self, url):
        self.url = url
        self.addr = urlsplit(url)
        if self.addr.scheme != "http" or self.addr.hostname not in (
            "localhost",
            "127.0.0.1",
            "::1",
        ):
            raise ValueError("run on the test host against its loopback HTTP listener")
        self.cookie = ""
        self.online = True
        self.drop_reply = False

    def request(self, path, data=None):
        if not self.online:
            raise ConnectionError("simulated offline device")
        body = (
            json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
            if data is not None
            else None
        )
        counts = {"up": 0, "down": 0, "requestBody": len(body or b"")}
        self.last = counts

        class Connection(http.client.HTTPConnection):
            def send(self, block):
                super().send(block)
                counts["up"] += len(block)

        class Response(http.client.HTTPResponse):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.fp = MeteredReader(self.fp, counts)

        conn = Connection(self.addr.hostname, self.addr.port, timeout=90)
        conn.response_class = Response
        try:
            headers = {"Content-Type": "application/json", "Connection": "close"}
            if self.cookie:
                headers["Cookie"] = self.cookie
            conn.request(
                "POST" if body is not None else "GET", "/api/v1/" + path, body, headers
            )
            with conn.getresponse() as response:
                if self.drop_reply:
                    self.drop_reply = False
                    raise ConnectionResetError(
                        "connection closed before the client read the receipt"
                    )
                raw = response.read()
                counts["responseBody"] = len(raw)
                if response.status != 200:
                    raise HTTPFailure(response.status)
                cookie = SimpleCookie(response.getheader("Set-Cookie", ""))
                if "timepin_session" in cookie:
                    self.cookie = cookie["timepin_session"].OutputString(attrs=[])
                return json.loads(raw)
        finally:
            conn.close()

    def manage(self, operation, **args):
        return self.request(
            "manage", dict(args, op=operation, operationId=uuid.uuid4().hex)
        )

    def command(self, cal, name, **args):
        return self.request(
            "commands",
            dict(args, v=1, op=name, calendarId=cal, operationId=uuid.uuid4().hex),
        )


def shift(hour, day="2026-11-19"):
    return {
        "title": "交接班",
        "span": {
            "kind": "timed",
            "zone": "Asia/Shanghai",
            "start": f"{day}T{hour:02}:00:00+08:00",
            "end": f"{day}T{hour + 6:02}:00:00+08:00",
        },
    }


def measure(owner, cal, device, event_id):
    cached = device.record(cal, event_id)
    assert cached and not cached["deleted"]
    start = cached["event"]["span"]["start"]
    hour = 9 if start[11:13] == "10" else 10
    result = owner.command(
        cal,
        "event.replace",
        eventId=event_id,
        baseVersion=cached["version"],
        event=shift(hour, start[:10]),
    )
    assert result["code"] == "ok"
    page = device.pull(cal)
    counts = dict(device.api.last)
    assert (
        page["changes"] == [result["record"]]
        and not page["more"]
        and "calendar" not in page
    )
    assert device.pull(cal)["changes"] == []
    return dict(counts, records=1)


def scenario(url, owner, owner_id, root):
    cal = owner.manage("calendar.create", name="同步验收", color="#326899")["id"]
    invite = owner.manage("calendar.invite", target=cal, role="editor")["token"]
    peer = Wire(url)
    name = "sync-" + uuid.uuid4().hex[:8]
    peer_id = peer.request(
        "register",
        {"name": name, "password": secrets.token_urlsafe(18), "invite": invite},
    )["id"]
    a = Replica(root / "a.db", owner, owner_id)
    b = Replica(root / "b.db", peer, peer_id)
    try:
        # Match the 36-byte identifier produced by structured import in the large fixture.
        probe = "probe-" + "a" * 30
        for event_id in ("morning", "night", probe):
            assert (
                owner.command(cal, "event.create", eventId=event_id, event=shift(8))[
                    "code"
                ]
                == "ok"
            )
        assert a.catch_up(cal, 1) == 3
        first = b.pull(cal, 1)
        assert first["more"] and len(first["changes"]) == 1
        peer.drop_reply = True
        try:
            b.pull(cal, 1)
        except ConnectionResetError:
            pass
        else:
            raise AssertionError("expected lost snapshot page")
        b.db.close()
        b = Replica(root / "b.db", peer, peer_id)
        b.db.execute("""CREATE TEMP TRIGGER interrupt_page BEFORE UPDATE ON books
                     BEGIN SELECT RAISE(ABORT,'simulated interrupted page commit'); END""")
        try:
            b.pull(cal, 1)
        except sqlite3.IntegrityError:
            pass
        else:
            raise AssertionError("expected interrupted local transaction")
        b.db.execute("DROP TRIGGER interrupt_page")
        assert b.db.execute("SELECT count(*) FROM records").fetchone()[0] == 1
        assert b.db.execute("SELECT cursor FROM books").fetchone()[0] == first["cursor"]
        assert b.catch_up(cal, 1) == 2
        small = measure(owner, cal, b, probe)
        a.catch_up(cal)
        # Drop an acknowledged server response. Restart the device with the outstanding edit on disk.
        owner.online = False
        queued = a.edit(cal, "morning", shift(9))
        try:
            a.push(cal, "morning")
        except ConnectionError:
            pass
        else:
            raise AssertionError("offline push reached server")
        owner.online, owner.drop_reply = True, True
        try:
            a.push(cal, "morning")
        except ConnectionResetError:
            pass
        else:
            raise AssertionError("expected lost receipt")
        a.db.close()
        a = Replica(root / "a.db", owner, owner_id)
        assert a.draft(cal, "morning") == ("pending", queued)
        assert a.push(cal, "morning")["record"]["version"] == 2
        assert len(b.pull(cal)["changes"]) == 1 and b.pull(cal)["changes"] == []

        # Independently prepare the same base version; release both requests together.
        owner.online = peer.online = False
        drafts = [a.edit(cal, "night", shift(7)), b.edit(cal, "night", shift(12))]
        assert [cmd["baseVersion"] for cmd in drafts] == [1, 1]
        owner.online = peer.online = True
        gate = Barrier(2)

        def race(pair):
            wire, cmd = pair
            gate.wait(timeout=10)
            return wire.request("commands", cmd)

        with ThreadPoolExecutor(max_workers=2) as pool:
            replies = list(pool.map(race, zip((owner, peer), drafts)))
        assert sorted(r["code"] for r in replies) == ["conflict", "ok"]
        # Deliver the pending responses via each client's normal retry handler.
        winner = 0 if replies[0]["code"] == "ok" else 1
        loser = 1 - winner
        devices = [a, b]
        for i, dev in enumerate(devices):
            assert dev.push(cal, "night") == replies[i]
        other = devices[loser]
        assert other.draft(cal, "night") == ("conflict", drafts[loser])
        assert (
            other.record(cal, "night")
            == replies[winner]["record"]
            == replies[loser]["current"]
        )
        resolution = other.edit(cal, "night", shift(11), resolve=True)
        assert (
            resolution["baseVersion"] == 2
            and resolution["operationId"] != drafts[loser]["operationId"]
        )
        assert other.push(cal, "night")["record"]["version"] == 3
        a.catch_up(cal)
        b.catch_up(cal)
        assert a.record(cal, "night") == b.record(cal, "night")

        assert (
            owner.command(cal, "event.delete", eventId="morning", baseVersion=2)["code"]
            == "ok"
        )
        assert b.pull(cal)["changes"] == [
            {"id": "morning", "version": 3, "deleted": True}
        ]
        own = next(
            c["id"] for c in peer.request("state")["calendars"] if c["id"] != cal
        )
        peer.command(own, "event.create", eventId="mine", event=shift(8))
        b.catch_up(own)
        forbidden = b.edit(cal, "night", shift(13))
        owner.manage(
            "calendar.grant",
            target=cal,
            subjectKind="user",
            subject=name,
            role="remove",
        )
        try:
            peer.request("commands", forbidden)
        except HTTPFailure as e:
            assert e.status == 403
        else:
            raise AssertionError("revoked write accepted")
        assert b.pull(cal) == {"status": "revoked"}
        assert b.draft(cal, "night") == ("revoked", forbidden)
        assert not b.record(cal, "night") and b.record(own, "mine")
        assert a.record(cal, "night")["version"] == 3
        return small
    finally:
        a.db.close()
        b.db.close()


def run(url, state_path):
    if not __debug__:
        raise RuntimeError("run without -O so acceptance assertions are active")
    owner = Wire(url)
    results = {}
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        if state_path:
            seed = json.loads(state_path.read_text())
            if seed["url"] != url:
                raise ValueError("benchmark state belongs to another instance")
            actor = owner.request(
                "login", {"name": "benchmark-00", "password": seed["password"]}
            )
            reader = Wire(url)
            account = reader.request(
                "login", {"name": "benchmark-00", "password": seed["password"]}
            )
            device = Replica(root / "history.db", reader, account["id"])
            try:
                cal = seed["calendar"]
                assert device.catch_up(cal) == 100_000
                first = device.db.execute(
                    "SELECT id FROM records WHERE cal=? ORDER BY id LIMIT 1", (cal,)
                ).fetchone()[0]
                results["events100000"] = measure(owner, cal, device, first)
            finally:
                device.db.close()
        else:
            if not owner.request("state").get("firstAccount"):
                raise ValueError(
                    "use an empty disposable instance or dedicated benchmark state"
                )
            actor = owner.request(
                "register",
                {"name": "sync-owner", "password": secrets.token_urlsafe(18)},
            )
        results["events3"] = scenario(url, owner, actor["id"], root)
        if state_path:
            assert results["events100000"] == results["events3"], (
                "delta traffic grew with history"
            )
        return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--state", type=Path)
    args = parser.parse_args()
    print(json.dumps(run(args.url, args.state), sort_keys=True))
