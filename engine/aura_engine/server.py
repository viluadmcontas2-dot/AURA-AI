"""Authenticated, loopback-only HTTP wire API for an authorised Android client."""
import hmac
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import adapters, chat_http, cli_setup, reform_http, sessions_http, setup_http, triage
from .config import ConfigError, VersionConflict
from .security import workspace_name


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

        def drain_body(self):
            """Le e descarta o corpo ainda nao lido (ate 1 MB) ANTES de responder. Fechar o socket com bytes
            nao lidos manda um RST e o app recebe IOException em vez da resposta (401, 404, 400...)."""
            if getattr(self, "_drained", False):
                return
            self._drained = True
            raw_size = self.headers.get("Content-Length", "")
            if not raw_size.isdigit():
                return
            left = min(int(raw_size), 1000000)
            try:
                self.connection.settimeout(5)
                while left > 0:
                    chunk = self.rfile.read(min(left, 65536))
                    if not chunk:
                        break
                    left -= len(chunk)
            except (OSError, ValueError):
                pass

        def send(self, code, data):
            self.drain_body()
            raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def no_content(self):
            # 204 nao leva corpo nem Content-Length (RFC 9110).
            self.drain_body()
            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()

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
            raw_body = self.rfile.read(int(raw_size))
            self._drained = True
            data = json.loads(raw_body)
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
                routed = triage.http(engine, method, path, self.body)
                if routed is not None:
                    return self.send(*routed)
                routed = setup_http.http(engine, method, path, self.body, parsed.query)
                if routed is not None:
                    return self.send(*routed)
                routed = reform_http.http(engine, method, path, self.body, parsed.query)
                if routed is not None:
                    return self.send(*routed)
                routed = sessions_http.http(engine, method, path, self.body, parsed.query)
                if routed is not None:
                    return self.send(*routed)
                routed = chat_http.http(engine, method, path, self.body, parsed.query)
                if routed is not None:
                    return self.send(*routed)
                if method == "DELETE":
                    # So o caminho CRU "/v1/memory" apaga tudo: "/v1/memory/" (id vazio de um cliente com
                    # defeito) e "/v1/memory;x" (urlparse separa ";params") caem no 404.
                    if self.path.partition("?")[0] == "/v1/memory":
                        query = parse_qs(parsed.query, keep_blank_values=True)
                        if set(query) - {"workspace"}:
                            raise ValueError("unknown query parameter")
                        scope = workspace_name(query["workspace"][0]) if "workspace" in query else None
                        return self.send(200, {"deleted": engine.store.forget_all(scope)})
                    if len(parts) == 4 and parts[:3] == ["", "v1", "memory"]:
                        if engine.store.forget(parts[3]):
                            return self.no_content()
                        return self.send(404, {"error": {
                            "code": "not_found",
                            "message": "Lembrança não encontrada. Talvez já tenha sido esquecida."}})
                    return self.send(404, {"error": {"code": "not_found", "message": "Unknown endpoint"}})
                setting = {"/v1/settings/autonomy": "autonomy", "/v1/settings/cost_lock": "cost_lock",
                           "/v1/network": "network"}.get(path)
                if setting and method in ("GET", "POST"):
                    if method == "POST":
                        data = self.body()
                        if setting == "network":
                            engine.config.set_setting("network", {k: data.get(k) for k in ("metered", "transport")}
                                                      if set(data) >= {"metered", "transport"} else data)
                        else:
                            engine.config.set_setting(setting, data.get(setting))
                    current = engine.config.settings()[setting]
                    return self.send(200, current if setting == "network" else {setting: current})
                if method == "GET":
                    if path == "/v1/health":
                        return self.send(200, {"service": "aura-engine", "version": 1,
                                                  "engine_build": engine.build})
                    if path == "/v1/capabilities":
                        return self.send(200, engine.capabilities())
                    if path == "/v1/jobs":
                        return self.send(200, {"jobs": engine.store.jobs(params.get("limit", [50])[0])})
                    if path == "/v1/events":
                        return self.send(200, {"events": engine.store.events(params.get("since", [0])[0])})
                    if path == "/v1/skills":
                        return self.send(200, {"skills": adapters.scan_skills(engine.home)})
                    if path == "/v1/usage":
                        return self.send(200, engine.store.usage())
                    if path == "/v1/auth/status":
                        return self.send(200, engine.auth.status())
                    if path == "/v1/policy":
                        return self.send(200, engine.config.policy())
                    if path == "/v1/policy/history":
                        return self.send(200, engine.config.policy_history(
                            params.get("limit", [50])[0]))
                    if path == "/v1/models":
                        return self.send(200, engine.config.models())
                    if path == "/v1/spend":
                        return self.send(200, engine.store.spend())
                    if path == "/v1/memory":
                        return self.send(200, {"entries": engine.store.recall(
                            params.get("workspace", ["default"])[0],
                            params.get("q", [""])[0])})
                    if path == "/v1/schedules":
                        return self.send(200, {"schedules": engine.store.schedules()})
                    if path == "/v1/chats":
                        return self.send(200, {"chats": engine.store.chats(params.get("limit", [50])[0])})
                    if len(parts) == 5 and parts[:3] == ["", "v1", "chats"] and parts[4] == "messages":
                        if engine.store.chat_conversation(parts[3]) is None:
                            return self.send(404, {"error": {"code": "not_found", "message": "Conversa não encontrada"}})
                        return self.send(200, {"messages": engine.store.chat_messages(parts[3])})
                    if len(parts) == 4 and parts[:3] == ["", "v1", "chat"]:
                        state = engine.chat.state(parts[3])
                        if state is None:
                            return self.send(404, {"error": {"code": "not_found", "message": "Chat não encontrado"}})
                        return self.send(200, state)
                    if len(parts) == 4 and parts[:3] == ["", "v1", "jobs"]:
                        job = engine.store.job(parts[3])
                        return self.send(200, job) if job else self.send(404, {"error": "job not found"})
                else:
                    if method == "POST" and path == "/v1/auth/start":
                        return self.send(200, engine.auth.start(self.body().get("provider")))
                    if method == "POST" and path == "/v1/auth/code":
                        data = self.body()
                        return self.send(200, engine.auth.submit_code(data.get("provider"), data.get("code")))
                    if method == "POST" and path == "/v1/auth/token":
                        data = self.body()
                        return self.send(200, engine.auth.save_token(data.get("provider"), data.get("token")))
                    if method == "POST" and path == "/v1/tools/install":
                        data = self.body()
                        if data.get("provider") not in cli_setup.EXE:
                            raise ValueError("provider must be one of: " + ", ".join(cli_setup.PROVIDERS))
                        job = engine.submit({"kind": "tools", "action": "install", "provider": data["provider"],
                                             "workspace": data.get("workspace", "default")},
                                            request_id=data.get("request_id"))
                        return self.send(202, job)
                    if method == "POST" and path == "/v1/policy/undo":
                        return self.send(200, engine.config.undo_policy(self.body().get("to_version")))
                    if method == "PUT":
                        if path == "/v1/policy":
                            return self.send(200, engine.config.update("policy", self.body()))
                        if path == "/v1/models":
                            return self.send(200, engine.config.update("models", self.body()))
                        return self.send(404, {"error": {"code": "not_found", "message": "Unknown endpoint"}})
                    if path == "/v1/jobs":
                        data = self.body()
                        request = data.get("request")
                        if not isinstance(request, dict):
                            raise ValueError("request is required")
                        job = engine.submit(request, request_id=data.get("request_id"))
                        return self.send(202, job)
                    if path == "/v1/chat":
                        data = self.body()
                        return self.send(202, engine.chat.start(
                            data.get("text"), session_id=data.get("session_id"),
                            workspace=data.get("workspace", "default"), executor=data.get("executor")))
                    if len(parts) == 5 and parts[:3] == ["", "v1", "chat"] and parts[4] == "stop":
                        if not engine.chat.stop(parts[3]):
                            state = engine.store.chat_run(parts[3])
                            if state is None:
                                return self.send(404, {"error": {"code": "not_found", "message": "Chat não encontrado"}})
                            return self.send(200, dict(state, stopped=False))
                        return self.send(200, {"chat_id": parts[3], "stopped": True})
                    if path == "/v1/intent":
                        data = self.body()
                        return self.send(200, engine.intent(data.get("text"), workspace=data.get("workspace", "default")))
                    if path == "/v1/route":
                        data = self.body()
                        return self.send(200, engine.route(
                            data.get("text"), workspace=data.get("workspace", "default"),
                            preferred_provider=data.get("preferred_provider", "auto")))
                    if path == "/v1/memory":
                        data = self.body()
                        ws = workspace_name(data.get("workspace", "default"))
                        key = data.get("key")
                        value = data.get("value")
                        if not isinstance(key, str) or len(key) > 120 or not key:
                            raise ValueError("invalid memory key")
                        if not isinstance(value, str) or len(value) > 50000:
                            raise ValueError("invalid memory value")
                        engine.store.remember(ws, key, value, data.get("kind"), data.get("source"))
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
            except VersionConflict as error:
                return self.send(409, {"error": {"code": "conflict", "message": str(error)[:300]}})
            except ConfigError as error:
                return self.send(400, {"error": {"code": "invalid_request", "message": str(error)[:300]}})
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

        def do_PUT(self):
            self.perform("PUT")

        def do_DELETE(self):
            self.perform("DELETE")

    return LocalOnlyHTTP((host, int(port)), Handler)
