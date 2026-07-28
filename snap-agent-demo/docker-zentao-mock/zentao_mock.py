#!/usr/bin/env python3
"""
Minimal Zentao REST API v1 mock for SnapAgent issue-closure testing.
Implements only the endpoints called by ZentaoIssueTracker:
  - POST /api.php/v1/products/{id}/bugs        (create bug)
  - POST /api.php/v1/bugs/{id}/resolve          (resolve bug)
  - POST /api.php/v1/bugs/{id}/close            (close bug)
  - POST /api.php/v1/bugs/{id}/activate         (activate bug)
  - POST /api.php/v1/bugs/{id}/comments         (add comment)
  - GET  /bug-view-{id}.html                    (web view redirect)
  - GET  /api.php/v1/products                    (list products)
"""
import json
import re
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler

# In-memory bug storage
bugs = {}
bug_counter = [0]
lock = threading.Lock()

TOKEN = "test-pat-token"
PRODUCT_ID = 1
PROJECT_ID = 0


class ZentaoMockHandler(BaseHTTPRequestHandler):

    def _send_json(self, code, body):
        data = json.dumps(body).encode("utf-8") if body else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _send_html(self, code, html):
        data = html.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def _check_auth(self):
        # Accept token from either 'Token' header (Zentao native) or
        # 'Authorization: Token xxx' header (as sent by AbstractHttpIssueTracker)
        token = self.headers.get("Token", "")
        if not token:
            auth = self.headers.get("Authorization", "")
            if auth.startswith("Token "):
                token = auth[6:]
        if token != TOKEN:
            self._send_json(401, {"error": "invalid token"})
            return False
        return True

    def do_GET(self):
        path = self.path
        # List products
        if path == "/api.php/v1/products":
            self._send_json(200, {"products": [
                {"id": PRODUCT_ID, "name": "Test Product"}
            ]})
            return
        # Bug web view
        m = re.match(r"/bug-view-(\d+)\.html", path)
        if m:
            bug_id = int(m.group(1))
            bug = bugs.get(bug_id, {})
            html = f"<html><body><h1>Bug #{bug_id}</h1><p>{bug.get('title','')}</p><p>Status: {bug.get('status','n/a')}</p></body></html>"
            self._send_html(200, html)
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if not self._check_auth():
            return
        path = self.path
        body = self._read_body()

        # Create bug: /api.php/v1/products/{id}/bugs
        m = re.match(r"/api\.php/v1/products/(\d+)/bugs$", path)
        if m:
            with lock:
                bug_counter[0] += 1
                bug_id = bug_counter[0]
                bugs[bug_id] = {
                    "id": bug_id,
                    "title": body.get("title", ""),
                    "desc": body.get("desc", ""),
                    "status": "active",
                    "severity": body.get("severity", 3),
                    "pri": body.get("pri", 3),
                    "type": body.get("type", "codeerror"),
                    "assignedTo": body.get("assignedTo", ""),
                    "comments": []
                }
            print(f"[ZentaoMock] Created bug #{bug_id}: {bugs[bug_id]['title']}")
            self._send_json(201, {"id": bug_id})
            return

        # Resolve: /api.php/v1/bugs/{id}/resolve
        m = re.match(r"/api\.php/v1/bugs/(\d+)/resolve$", path)
        if m:
            bug_id = int(m.group(1))
            if bug_id in bugs:
                bugs[bug_id]["status"] = "resolved"
                print(f"[ZentaoMock] Resolved bug #{bug_id}")
            self._send_json(200, {"id": bug_id, "status": "resolved"})
            return

        # Close: /api.php/v1/bugs/{id}/close
        m = re.match(r"/api\.php/v1/bugs/(\d+)/close$", path)
        if m:
            bug_id = int(m.group(1))
            if bug_id in bugs:
                bugs[bug_id]["status"] = "closed"
                print(f"[ZentaoMock] Closed bug #{bug_id}")
            self._send_json(200, {"id": bug_id, "status": "closed"})
            return

        # Activate: /api.php/v1/bugs/{id}/activate
        m = re.match(r"/api\.php/v1/bugs/(\d+)/activate$", path)
        if m:
            bug_id = int(m.group(1))
            if bug_id in bugs:
                bugs[bug_id]["status"] = "active"
                print(f"[ZentaoMock] Activated bug #{bug_id}")
            self._send_json(200, {"id": bug_id, "status": "active"})
            return

        # Add comment: /api.php/v1/bugs/{id}/comments
        m = re.match(r"/api\.php/v1/bugs/(\d+)/comments$", path)
        if m:
            bug_id = int(m.group(1))
            comment = body.get("comment", "")
            if bug_id in bugs:
                bugs[bug_id]["comments"].append(comment)
                print(f"[ZentaoMock] Added comment to bug #{bug_id}: {comment[:80]}")
            self._send_json(201, {"id": len(bugs.get(bug_id, {}).get("comments", []))})
            return

        self._send_json(404, {"error": f"no route for {path}"})

    def log_message(self, format, *args):
        print(f"[ZentaoMock] {args[0]}")


if __name__ == "__main__":
    port = 8081
    server = HTTPServer(("0.0.0.0", port), ZentaoMockHandler)
    print(f"[ZentaoMock] Server started on port {port}")
    print(f"[ZentaoMock] Token: {TOKEN}")
    print(f"[ZentaoMock] Product ID: {PRODUCT_ID}")
    server.serve_forever()
