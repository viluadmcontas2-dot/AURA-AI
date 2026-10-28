"""Local-first job orchestrator. Model providers do not own the runtime."""
import os
import pathlib
import secrets
import threading
import time

from . import adapters, routing
from .auth import AuthManager
from .config import ConfigStore
from .security import RiskPolicy, workspace_name, load_or_create_token
from .store import Store
from .triage import Triage


class Engine:
    def __init__(self, home=None, auto_worker=True, max_workers=2, auth_timeout=300):
        self.home = pathlib.Path(home or (pathlib.Path.home() / ".aura" / "engine"))
        self.token = load_or_create_token(self.home)
        self.store = Store(self.home / "engine.sqlite3")
        self.config = ConfigStore(self.home, self.store)
        self.policy = RiskPolicy(self.config)
        self.auth = AuthManager(self.home, self.store, timeout=auth_timeout)
        self.auth.load_token()
        self._keep_awake()
        self.stop_event = threading.Event()
        self.process_lock = threading.RLock()
        self.context = threading.local()
        self.processes = {}
        self.cancelled = set()
        self.workers = []
        self.triage = Triage(self)
        if auto_worker:
            for number in range(max(1, min(int(max_workers), 4))):
                worker = threading.Thread(target=self._loop, daemon=True,
                                          name="aura-worker-%d" % number)
                self.workers.append(worker)
                worker.start()

    @staticmethod
    def _keep_awake():
        """Termux: segura o celular acordado para o motor nao morrer. Best effort: sem o comando, segue."""
        import shutil
        import subprocess
        exe = shutil.which("termux-wake-lock")
        if exe:
            try:
                subprocess.run([exe], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=10)
            except (OSError, subprocess.SubprocessError):
                pass

    def close(self):
        self.stop_event.set()
        self.auth.close()
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
        risk = self.policy.classify(request)
        # policy.json e models.json sao relidos a cada job: mudar o arquivo vale no proximo pedido.
        try:
            routing.validate_request(request, self.config.models())
        except routing.ModelRefused as err:
            raise ValueError(str(err))
        decision, why, version = self.policy.decide(request, risk)
        status = "queued" if decision == "auto" else "awaiting_approval"
        created = {"policy_version": version, "decision": decision, "why": why}
        note, events = None, []
        for hit in self._caps_reached(request):
            note = note or hit["note"]
            events.append(("spend.cap_reached", {
                "scope": hit["scope"], "cap": hit["cap"], "spent": hit["spent"],
                "action": hit["action"], "phase": "antes", "kind": request.get("kind")}))
            if hit["action"] == "pausar":
                status = "awaiting_approval"
                created["decision"], created["why"] = "ask", "limite de gasto atingido (pausar)"
        if note and status != "awaiting_approval":
            note = None  # só avisar: o job segue, o aviso fica no evento
        return self.store.submit(request, risk, request_id, status=status, note=note,
                                 created=created, events=events)

    SCOPE_LABEL = {"per_job": "tarefa", "per_day": "dia", "per_month": "mês"}

    def _caps_reached(self, request):
        """Limites de gasto ja atingidos por este pedido. Nunca altera a politica."""
        if request.get("kind") not in self.store.SPEND_PROVIDERS:
            return []
        cap = self.config.policy().get("spend_cap", {})
        report = None
        hits = []
        action = cap.get("on_reach", "avisar")
        declared = request.get("max_cost_usd")
        if cap.get("per_job") is not None and type(declared) in (int, float) and declared > cap["per_job"]:
            hits.append(("per_job", cap["per_job"], declared))
        for scope, window in (("per_day", "today"), ("per_month", "month")):
            if cap.get(scope) is None:
                continue
            report = report or self.store.spend()
            total = report[window]["total_usd"]
            spent = total if type(total) in (int, float) else 0
            if spent >= cap[scope]:
                hits.append((scope, cap[scope], spent))
        return [{"scope": s, "cap": limit, "spent": spent, "action": action,
                 "note": "Limite por %s atingido: gasto %s de %s. Aprove só se quiser continuar; "
                         "o limite não muda sozinho." % (self.SCOPE_LABEL[s], spent, limit)}
                for s, limit, spent in hits]

    def _warn_after_finish(self, job):
        """Avisa quando este job fez o gasto cruzar um limite."""
        if job.get("status") != "completed" or job["request"].get("kind") not in self.store.SPEND_PROVIDERS:
            return
        cost = self.store.reported_cost(job.get("result"))
        cap = self.config.policy().get("spend_cap", {})
        if not cost:
            return
        action = cap.get("on_reach", "avisar")
        report = self.store.spend()
        crossed = []
        if cap.get("per_job") is not None and cost > cap["per_job"]:
            crossed.append(("per_job", cap["per_job"], cost))
        for scope, window in (("per_day", "today"), ("per_month", "month")):
            limit = cap.get(scope)
            total = report[window]["total_usd"]
            if limit is None or type(total) not in (int, float):
                continue
            if total - cost < limit <= total:
                crossed.append((scope, limit, total))
        for scope, limit, spent in crossed:
            self.store.emit("spend.cap_reached", {
                "scope": scope, "cap": limit, "spent": spent, "action": action,
                "phase": "depois", "job_id": job["id"]})

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
                request = job["request"]
                try:
                    injected, info = routing.resolve(request, self.config.models())
                except routing.ModelRefused as err:
                    raise adapters.ActionError("model_denied", str(err))
                if info is not None:
                    self.store.emit("job.model", dict(info, id=job["id"]))
                if injected:
                    request = dict(request, model=injected)
                outcome = adapters.dispatch(request, self)
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
            final = self.store.job(job["id"])
            try:
                self._warn_after_finish(final)
            except Exception:
                pass  # aviso de gasto nunca derruba o job
            try:
                self.triage.promote()
            except Exception:
                pass  # a fila nunca derruba o job
            return final
        finally:
            with self.process_lock:
                self.cancelled.discard(job["id"])
            self.context.job_id = None

    def _loop(self):
        while not self.stop_event.is_set():
            try:
                for _, req in self.store.due_schedules():
                    self.submit(req)
                self.triage.promote()
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
        valid = ("claude_cli", "gemini_cli", "agy_cli", "claude_api", "gemini_api")
        capabilities = self.capabilities()
        available = capabilities.get("providers", {})
        models = self.config.models()
        why_extra = None
        if preferred_provider in valid:
            provider = preferred_provider
            cli_twin = {"claude_api": ("claude_cli", "claude"), "gemini_api": ("gemini_cli", "gemini")}.get(provider)
            if (cli_twin and models.get("api_fallback") == "never" and models.get("prefer_subscription")
                    and available.get(cli_twin[1], {}).get("installed")):
                provider = cli_twin[0]
                why_extra = "assinatura primeiro: usei o CLI logado em vez da API paga (api_fallback=never)"
        else:
            provider = next((kind for kind, executable in (
                ("claude_cli", "claude"), ("gemini_cli", "gemini"), ("agy_cli", "agy"))
                if available.get(executable, {}).get("installed")), "claude_cli")
        executable = {"claude_cli": "claude", "gemini_cli": "gemini",
                      "agy_cli": "agy"}.get(provider)
        enabled = (available.get(executable, {}).get("installed", False) if executable else
                   capabilities.get("api", {}).get("claude" if provider == "claude_api" else "gemini", False))
        proposed = {"kind": provider, "prompt": text, "workspace": workspace}
        answer = {"request": proposed,
                  "reason": "model required; prefer installed authenticated CLI, no automatic API billing",
                  "provider_installed_or_configured": bool(enabled), "requires_model": True}
        try:
            picked, info = routing.resolve(proposed, models)
        except routing.ModelRefused:
            picked, info = None, None
        if info is not None:
            answer.update(info)
            if why_extra:
                answer["why"] = why_extra + "; " + info["why"]
        elif why_extra:
            answer["why"] = why_extra
        return answer

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
