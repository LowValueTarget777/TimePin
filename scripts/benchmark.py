"""Reproducible phase-2 HTTP benchmark for a new, dedicated TimePin instance."""

import argparse
import datetime as dt
import http.cookiejar
import json
import math
import os
from pathlib import Path
import secrets
import statistics
import time
import urllib.request


class Client:
    def __init__(self, url):
        self.url = url.rstrip("/") + "/api/v1/"
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )

    def request(self, path, payload=None):
        data = (
            None
            if payload is None
            else json.dumps(payload, separators=(",", ":")).encode()
        )
        req = urllib.request.Request(
            self.url + path, data, {"Content-Type": "application/json"}
        )
        with self.opener.open(req, timeout=90) as res:
            return json.load(res)


def importer(client, calendar, operation, rows):
    reply = client.request(
        "commands",
        {
            "v": 1,
            "op": "import.commit",
            "operationId": operation,
            "calendarId": calendar,
            "batch": {"source": "phase2-benchmark", "rows": rows},
        },
    )
    if reply.get("code") != "ok" or len(reply["rows"]) != len(rows):
        raise ValueError("unexpected import reply")
    return reply


def seed(url, state_path):
    admin = Client(url)
    if state_path.exists() or not admin.request("state").get("firstAccount"):
        raise ValueError("seed needs an empty disposable instance and a new state file")
    password = secrets.token_urlsafe(24)
    admin.request("register", {"name": "benchmark-00", "password": password})
    calendar = admin.request("state")["calendars"][0]["id"]
    for index in range(1, 20):
        invite = admin.request(
            "manage",
            {
                "op": "calendar.invite",
                "target": calendar,
                "operationId": f"invite-{index}",
                "role": "reader",
            },
        )
        Client(url).request(
            "register",
            {
                "name": f"benchmark-{index:02}",
                "password": secrets.token_urlsafe(24),
                "invite": invite["token"],
            },
        )
    start = dt.date(2011, 1, 1)
    for offset in range(0, 100_000, 1000):
        rows = []
        for i in range(offset, offset + 1000):
            date = start + dt.timedelta(days=i % 5000)
            rows.append(
                {
                    "externalId": f"history-{i}",
                    "event": {
                        "title": f"历史排班 {i}",
                        "span": {
                            "kind": "date",
                            "start": date.isoformat(),
                            "end": (date + dt.timedelta(days=1)).isoformat(),
                        },
                    },
                }
            )
        reply = importer(admin, calendar, f"history-{offset}", rows)
        if any(x["outcome"] != "created" for x in reply["rows"]):
            raise ValueError("unexpected history count")
    with os.fdopen(
        os.open(state_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w"
    ) as f:
        json.dump({"calendar": calendar, "password": password, "url": url}, f)
    return {"users": 20, "historyEvents": 100_000}


def measure(url, state_path):
    state = json.loads(state_path.read_text())
    if url != state["url"]:
        raise ValueError("use the instance that was seeded")
    client = Client(url)
    client.request("login", {"name": "benchmark-00", "password": state["password"]})
    calendar = state["calendar"]
    window = {
        "v": 1,
        "op": "event.query",
        "calendarId": calendar,
        "window": {"start": "2026-11-01", "end": "2026-12-01", "zone": "Asia/Shanghai"},
    }
    before = client.request("commands", window)
    if before.get("occurrences") != []:
        raise ValueError(
            "measure needs a freshly seeded dataset; existing November rows would replay old receipts"
        )
    rows = []
    for i in range(30):
        date = dt.date(2026, 11, i + 1)
        rows.append(
            {
                "externalId": f"nov-{i}",
                "event": {
                    "title": "交接班",
                    "span": {
                        "kind": "timed",
                        "zone": "Asia/Shanghai",
                        "start": f"{date}T09:00:00+08:00",
                        "end": f"{date}T17:00:00+08:00",
                    },
                },
            }
        )
    rows[0]["event"]["span"].update(
        start="2026-10-31T22:00:00+08:00", end="2026-11-01T06:00:00+08:00"
    )
    meeting = rows[1]["event"]
    meeting["span"].update(
        start="2026-10-02T09:00:00+08:00", end="2026-10-02T17:00:00+08:00"
    )
    meeting["repeat"] = {"frequency": "monthly", "interval": 1, "until": "2026-11-02"}
    meeting["exceptions"] = [
        {
            "on": "2026-11-02",
            "move": dict(
                meeting["span"],
                start="2026-11-03T09:00:00+08:00",
                end="2026-11-03T17:00:00+08:00",
            ),
        }
    ]

    def timed(op):
        begin = time.perf_counter()
        result = importer(client, calendar, op, rows)
        return result, round((time.perf_counter() - begin) * 1000, 3)

    created, create_ms = timed("november-first")
    rows[8]["event"]["title"] = "修订班次"
    updated, update_ms = timed("november-change")
    replayed, replay_ms = timed("november-change")
    if (
        sum(x["outcome"] == "created" for x in created["rows"]) != 30
        or sum(x["outcome"] == "updated" for x in updated["rows"]) != 1
        or sum(x["outcome"] == "unchanged" for x in updated["rows"]) != 29
        or updated != replayed
    ):
        raise ValueError("incorrect import or replay")
    samples = []
    for _ in range(101):
        begin = time.perf_counter()
        view = client.request("commands", window)
        samples.append((time.perf_counter() - begin) * 1000)
        if len(view.get("occurrences", [])) != 30:
            raise ValueError("month query lost events")
    warm = sorted(samples[1:])
    undo = {"v": 1, "calendarId": calendar, "op": "import.undo",
            "operationId": "undo-november-change", "batchId": updated["batchId"]}
    started = time.perf_counter()
    restored = client.request("commands", undo)
    undo_ms = (time.perf_counter() - started) * 1000
    if ([r["outcome"] for r in restored.get("undo", [])] != ["restored"]
            or client.request("commands", undo) != restored):
        raise ValueError("incorrect undo or replay")
    return {
        "users": 20,
        "historyEvents": 100_000,
        "visibleOccurrences": 30,
        "samples": len(warm),
        "firstQueryMs": round(samples[0], 3),
        "medianMs": round(statistics.median(warm), 3),
        "p95Ms": round(warm[math.ceil(0.95 * len(warm)) - 1], 3),
        "create30Ms": create_ms,
        "revise1Skip29Ms": update_ms,
        "retryMs": replay_ms,
        "undoOneMs": round(undo_ms, 3),
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("action", choices=["seed", "measure"])
    p.add_argument("--url", required=True)
    p.add_argument("--state", type=Path, required=True)
    args = p.parse_args()
    result = (seed if args.action == "seed" else measure)(args.url, args.state)
    print(json.dumps(result))
