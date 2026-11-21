"""Loopback-only NAS outage fixture for DeviceTest.testConnectedAutoSync."""

import argparse
import json
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Event, Lock, Thread


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-port", type=int, required=True, help="isolated TimePin test instance")
    parser.add_argument("--port", type=int, default=19682)
    parser.add_argument("--control-port", type=int, default=19683)
    args = parser.parse_args()
    unavailable = Event()
    counts = {"checks": 0, "pulls": 0, "directories": 0, "revisionBytes": 0}
    lock = Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def serve(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if self.server.server_port == args.control_port:
                if self.command == "GET" and self.path == "/stats":
                    with lock:
                        result = dict(counts)
                elif self.command == "POST" and self.path in ("/up", "/down"):
                    if self.path == "/up":
                        unavailable.clear()
                    else:
                        unavailable.set()
                    result = {"available": not unavailable.is_set()}
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps(result).encode())
                return
            if unavailable.is_set():
                self.send_error(503)
                return
            upstream = HTTPConnection("127.0.0.1", args.server_port, timeout=15)
            try:
                headers = {key: self.headers[key] for key in ("Content-Type", "Cookie") if key in self.headers}
                upstream.request(self.command, self.path, body or None, headers)
                response = upstream.getresponse()
                data = response.read()
                with lock:
                    if self.path == "/api/v1/changes":
                        counts["checks"] += 1
                        counts["revisionBytes"] += len(data)
                    elif self.path == "/api/v1/sync/pull":
                        counts["pulls"] += 1
                    elif self.path == "/api/v1/state":
                        counts["directories"] += 1
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() in ("set-cookie", "content-type"):
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            finally:
                upstream.close()

        do_GET = do_POST = serve

    with ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as proxy:
        with ThreadingHTTPServer(("127.0.0.1", args.control_port), Handler) as control:
            Thread(target=control.serve_forever, daemon=True).start()
            try:
                proxy.serve_forever()
            finally:
                control.shutdown()


if __name__ == "__main__":
    main()
