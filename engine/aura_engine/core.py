"""Local-first job orchestrator. Model providers do not own the runtime."""
import os
import pathlib
import secrets
import threading
import time

from . import adapters
from .security import RiskPolicy, workspace_name
from .store import Store


class Engine:
    def __init__(self, home=None, auto_worker=True, max_workers=2):
        self.home = pathlib.Path(home or (pathlib.Path.home() / ".aura" / "engine"))
        self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.home, 0o700)
        token_file = self.home / "token"
        if not token_file.exists():
            with os.fdopen(os.open(str(token_file), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
                           "w") as f:
                f.write(secrets.token_urlsafe(32))
        os.chmod(token_file, 0o600)
        self.token = token_file.read_text("utf-8").strip()
        if len(self.token) < 32:
            raise RuntimeError("invalid token, refusing to start")
        self.store = Store(self.home / "engine.sqlite3")
        self.policy = RiskPolicy()
        self.stop_event = threading.Event()
        self.process_lock = threading.RLock()
        self.context = threading.local()
        self.processes = {}
        self.cancelled = set()
        self.workers = []
        if auto_worker:
            for number in range(max(1, min(int(max_workers), 4))):
                worker = threading.Thread(target=self._loop, daemon=True,
                                          name="aura-worker-%d" % number)
                self.workers.append(worker)
                worker.start()

    def close(self):
        self.stop_event.set()
        with self.process_lock:
            for proc in list(self.processes.values()):
                if proc.poll() is None:
                    proc.terminate()
        for worker in self.workers:
            if threading.current_thread() != worker:
                worker.join(timeout=3)
        self.store.close()

    def capabilities(self):
        return adapters.capabilities(self.home)

    def submit(self, request, request_id=None):
        if not isinstance(request, dict) or len(str(request)) > 110000:
            raise ValueError("request must be a bounded JSON object")
        if not isinstance(request.get("kind"), str):
            raise ValueError("kind is required")
        workspace_name(request.get("workspace", "default"))
        return self.store.submit(request, self.policy.classify(request), request_id)

    def approve(self, job_id):
        return self.store.approve(job_id)

    def cancel(self, job_id):
        current = self.store.job(job_id)
        if not current:
            raise KeyError("job not found")
        result = self.store.cancel(job_id)
        if current["status"] == "running":
            with self.process_lock:
                self.cancelled.add(job_id)
                proc = self.processes.get(job_id)
                if proc is not None and proc.poll() is None:
                    proc.terminate()
        return result

    def run_once(self):
        job = self.store.claim()
        if job is None:
            return None
        self.context.job_id = job["id"]
        try:
            if self.store.job(job["id"])["status"] != "running":
                return self.store.job(job["id"])
            try:
                with self.process_lock:
                    if job["id"] in self.cancelled:
                        raise adapters.ActionError("cancelled", "Execution cancelled")
                outcome = adapters.dispatch(job["request"], self)
            except adapters.ActionError as err:
                self.store.finish(job["id"], "failed", error={
                    "code": err.code, "message": adapters.redact(str(err))[:2000]})
            except (ValueError, FileNotFoundError, PermissionError, UnicodeError, OSError) as err:
                self.store.finish(job["id"], "failed", error={
                    "code": "invalid_or_unavailable", "message": adapters.redact(str(err))[:2000]})
            except Exception:
                self.store.finish(job["id"], "failed", error={
                    "code": "unexpected", "message": "Unexpected engine error; inspect local diagnostic logs"})
            else:
                self.store.finish(job["id"], "completed", result=outcome)
            return self.store.job(job["id"])
        finally:
            with self.process_lock:
                self.cancelled.discard(job["id"])
            self.context.job_id = None

    def _loop(self):
        while not self.stop_event.is_set():
            try:
                for _, req in self.store.due_schedules():
                    self.submit(req)
                handled = self.run_once()
                if handled is None:
                    self.stop_event.wait(0.3)
            except Exception:
                self.stop_event.wait(2)

    def route(self, text, workspace="default", preferred_provider="auto"):
        if not isinstance(text, str) or not text.strip() or len(text) > 100000:
            raise ValueError("empty or oversized utterance")
        workspace_name(workspace)
        lower = text.casefold()
        adb_words = ("dispositivos", "devices", "adb", "aparelhos conectados")
        if any(x in lower for x in adb_words) and any(
                x in lower for x in ("listar", "liste", "mostr", "quais", "conectad")):
            return {"request": {"kind": "adb", "action": "devices", "workspace": workspace},
                    "reason": "local deterministic device inventory", "requires_model": False}
        if any(x in lower for x in ("arquivos", "files", "pasta")) and any(
                x in lower for x in ("listar", "liste", "mostr")):
            return {"request": {"kind": "files", "action": "list", "path": ".",
                                "workspace": workspace},
                    "reason": "local deterministic file listing", "requires_model": False}
        if any(v in lower for v in ("organiza", "organize", "organizar")) and any(
                word in lower for word in ("arquivos", "pastas")):
            if any(item["id"] == "file-organization" for item in adapters.scan_skills(self.home)):
                return {"request": {"kind": "skills", "action": "run",
                                    "id": "file-organization", "prompt": text,
                                    "workspace": workspace},
                        "reason": "selected verified installed Agent Skill metadata",
                        "requires_model": True}
        provider = preferred_provider if preferred_provider in (
            "claude_cli", "gemini_cli", "claude_api", "gemini_api", "agy_cli") else "claude_cli"
        return {"request": {"kind": provider, "prompt": text, "workspace": workspace},
                "reason": "open-ended instruction requires a model", "requires_model": True}

    def add_schedule(self, request):
        if not isinstance(request, dict):
            raise ValueError("schedule must be object")
        body = request.get("request")
        if not isinstance(body, dict) or not isinstance(body.get("kind"), str):
            raise ValueError("scheduled task must have a kind")
        return self.store.add_schedule(request)

    def trigger_schedule(self, schedule_id):
        sched = next((x for x in self.store.schedules() if x["id"] == schedule_id), None)
        if sched is None:
            raise KeyError("schedule not found")
        return self.submit(sched["request"])
