#!/usr/bin/env python3
"""Serve synthetic-only recall evidence for native Jev UI acceptance; no database is opened."""
import argparse
import html
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18767)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).resolve().parents[1] / "fixtures/jev-recall-trace.json")
    args = parser.parse_args()
    fixtures = json.loads(args.fixture.read_text())
    fixture = next(item for item in fixtures if item["name"] == "verified_fix")
    anchors, later = fixture["anchors"], fixture["later"]
    captures = anchors + later

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            parsed = urlparse(self.path)
            query = parse_qs(parsed.query)
            if parsed.path == "/api/recall":
                scope = query.get("scope", [""])[0]
                items = captures if scope == anchors[0]["repo"] else anchors
                self.respond(200, "application/json", json.dumps({"items": items, "count": len(items)}))
            elif parsed.path == "/":
                event = query.get("event", [""])[0]
                item = next((item for item in captures if item["eventId"] == event), None)
                text = item["headline"] if item else "Synthetic recall UI acceptance fixture. No private Black Box data is served here."
                self.respond(200, "text/html; charset=utf-8", "<!doctype html><title>Black Box synthetic trace</title><h1>Synthetic capture</h1><p>" + html.escape(text) + "</p>")
            else:
                self.respond(404, "text/plain", "Fixture route not found")

        def respond(self, status, content_type, text):
            data = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Synthetic recall fixture at http://127.0.0.1:{args.port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
