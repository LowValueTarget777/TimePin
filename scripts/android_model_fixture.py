"""Deterministic Chat Completions tool-call fixture for the Android acceptance suite."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/chat/completions":
            self.send_error(404)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        last = request["messages"][-1]
        prompt = next(x["content"] for x in reversed(request["messages"]) if x["role"] == "user")
        if prompt.startswith("接受测试邀请"):
            assert "[本次邀请]" in prompt
            message = self.call("invite_accept", {})
        elif prompt.startswith("操作验收 "):
            action = json.loads(prompt.removeprefix("操作验收 "))
            name = action.pop("op").replace(".", "_")
            assert any(t["function"]["name"] == name for t in request["tools"])
            message = self.call(name, action)
        elif "多选" in prompt:
            if last["role"] != "tool":
                message = self.call("event_query", {"window": {"start":"2026-11-20","end":"2026-11-22","zone":"UTC"}})
            else:
                scope = json.loads(last["content"])
                groups = scope if isinstance(scope,list) else scope["calendars"]
                assert len(groups) == 2
                assert all(len(g["result"]["occurrences"]) == 1 for g in groups)
                if "改班" in prompt:
                    target = next(g for g in groups if g["name"] == "QA乙")
                    message = self.call("event_replace", {"calendarId":target["calendarId"],"eventId":"shift","baseVersion":1,
                        "event":{"title":"改为晚班","span":{"kind":"date","start":"2026-11-21","end":"2026-11-22"}}})
                else:
                    message = {"role":"assistant","content":"已查询两本日历"}
        else:
            if last["role"] == "tool":
                record = json.loads(last["content"])["record"]
                prompt = next(
                    x["content"]
                    for x in reversed(request["messages"])
                    if x["role"] == "user"
                )
                if "查询" in prompt:
                    message = {"role": "assistant", "content": record["event"]["title"]}
                else:
                    event = dict(record["event"], span=dict(record["event"]["span"]))
                    event["title"] = "模型调整后的晚班"
                    hour = 14 if "实例" in prompt else 12
                    span = event["span"]
                    span["start"] = span["start"][:10] + f"T{hour:02d}:00:00+08:00"
                    span["end"] = span["end"][:10] + f"T{hour + 8:02d}:00:00+08:00"
                    message = self.call(
                        "event_replace",
                        {
                            "eventId": record["id"],
                            "baseVersion": record["version"],
                            "event": event,
                        },
                    )
            else:
                message = self.call("event_get", {"eventId": "shift-qa"})
        if request.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type","text/event-stream")
            self.end_headers()
            def packet(delta, finish=None):
                content = json.dumps({"choices":[{"index":0,"delta":delta,"finish_reason":finish}]},ensure_ascii=False)
                self.wfile.write(("data: "+content+"\n\n").encode())
                self.wfile.flush()
            if message.get("tool_calls"):
                call = message["tool_calls"][0]
                args = call["function"]["arguments"]
                cut = len(args)//2
                packet({"tool_calls":[dict(call,index=0,function=dict(call["function"],arguments=args[:cut]))]})
                packet({"tool_calls":[{"index":0,"function":{"arguments":args[cut:]}}]},"tool_calls")
            else:
                answer = message["content"]
                packet({"content":answer[:2]})
                time.sleep(.1)
                packet({"content":answer[2:]},"stop")
            self.wfile.write(b"data: [DONE]\n\n")
            return
        data = json.dumps(
            {"choices": [{"message": message}]}, ensure_ascii=False
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    @staticmethod
    def call(name, args):
        return {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": "fixture-" + name,
                    "type": "function",
                    "function": {
                        "name": name,
                        "arguments": json.dumps(args, ensure_ascii=False),
                    },
                }
            ],
        }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=28472)
    args = parser.parse_args()
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
