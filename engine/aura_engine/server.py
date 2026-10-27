"""Authenticated, loopback-only HTTP wire API for an authorised Android client."""
import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import adapters


class LocalOnlyHTTP(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def make_server(engine, host="127.0.0.1", port=8765):
    if host not in ("127.0.0.1", "::1", "localhost"):
        raise ValueError("AURA refuses non-loopback binding")

    class Handler(BaseHTTPRequestHandler):
        server_version = "AURA/1"
        sys_version = ""

        def log_message(self, fmt, *args):
            # HTTP headers, bearer tokens and prompt paths never enter web logs.
            pass

        def send(self, code, data):
            raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def authenticate(self):
            expected = "Bearer " + engine.token
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode(), expected.encode()):
                self.send(401, {"error": {"code": "unauthorized", "message": "Bearer token required"}})
                return False
            return True

        def body(self):
            raw_size = self.headers.get("Content-Length", "")
            if not raw_size.isdigit() or not 0 <= int(raw_size) <= 1000000:
                raise ValueError("missing or oversized JSON body")
            data = json.loads(self.rfile.read(int(raw_size)))
            if not isinstance(data, dict):
                raise ValueError("JSON object expected")
            return data

        def perform(self, method):
            if not self.authenticate():
                return
            parsed = urlparse(self.path)
            path = parsed.path.rstrip("/") or "/"
            parts = path.split("/")
            params = parse_qs(parsed.query, keep_blank_values=False)
            try:
                if method == "GET":
                    if path == "/v1/health":
                        return self.send(200, {"service": "aura-engine", "version": 1})
                    if path == "/v1/capabilities":
                        return self.send(200, engine.capabilities())
                    if path == "/v1/jobs":
                        return self.send(200, {"jobs": engine.store.jobs(params.get("limit", [50])[0])})
                    if path == "/v1/events":
                        return self.send(200, {"events": engine.store.events(params.get("since", [0])[0])})
                    if path == "/v1/skills":
                        return self.send(200, {"skills": adapters.scan_skills(engine.home)})
                    if path == "/v1/memory":
                        return self.send(200, {"entries": engine.store.recall(
                            params.get("workspace", ["default"])[0],
                            params.get("q", [""])[0])})
                    if path == "/v1/schedules":
                        return self.send(200, {"schedules": engine.store.schedules()})
                    if len(parts) == 4 and parts[:3] == ["", "v1", "jobs"]:
                        job = engine.store.job(parts[3])
                        return self.send(200, job) if job else self.send(404, {"error": "job not found"})
                else:
                    if path == "/v1/jobs":
                        data = self.body()
                        request = data.get("request")
                        if not isinstance(request, dict):
                            raise ValueError("request is required")
                        job = engine.submit(request, request_id=data.get("request_id"))
                        return self.send(202, job)
                    if path == "/v1/route":
                        data = self.body()
                        return self.send(200, engine.route(
                            data.get("text"), workspace=data.get("workspace", "default"),
                            preferred_provider=data.get("preferred_provider", "auto")))
                    if path == "/v1/memory":
                        data = self.body()
                        from .security import workspace_name
                        ws = workspace_name(data.get("workspace", "default"))
                        key = data.get("key")
                        value = data.get("value")
                        if not isinstance(key, str) or len(key) > 120 or not key:
                            raise ValueError("invalid memory key")
                        if not isinstance(value, str) or len(value) > 50000:
                            raise ValueError("invalid memory value")
                        engine.store.remember(ws, key, value)
                        return self.send(200, {"stored": True})
                    if path == "/v1/schedules":
                        data = self.body()
                        return self.send(201, engine.add_schedule(data))
                    if len(parts) == 5 and parts[:3] == ["", "v1", "jobs"]:
                        job_id, action = parts[3:]
                        if engine.store.job(job_id) is None:
                            return self.send(404, {"error": "job not found"})
                        if action == "approve":
                            return self.send(200, engine.approve(job_id))
                        if action == "cancel":
                            return self.send(200, engine.cancel(job_id))
                    if len(parts) == 5 and parts[:3] == ["", "v1", "schedules"] and parts[4] == "trigger":
                        return self.send(202, engine.trigger_schedule(parts[3]))
                return self.send(404, {"error": {"code": "not_found", "message": "Unknown endpoint"}})
            except (ValueError, KeyError) as error:
                conflict = "idempotency key reused" in str(error)
                return self.send(409 if conflict else 400, {"error": {
                    "code": "conflict" if conflict else "invalid_request",
                    "message": str(error)[:300]}})
            except Exception:
                return self.send(500, {"error": {"code": "internal", "message": "Unexpected engine fault"}})

        def do_GET(self):
            self.perform("GET")

        def do_POST(self):
            self.perform("POST")

    return LocalOnlyHTTP((host, int(port)), Handler)
