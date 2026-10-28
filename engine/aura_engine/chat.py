"""Chat nativo: roda a CLI em streaming e traduz para eventos chat.* em portugues.

O app nunca ve JSON cru da CLI, fila de jobs ou pedido de aprovacao para conversar.
"""
import json
import os
import pathlib
import re
import signal
import subprocess
import threading
import time
import uuid

from . import adapters, routing
from .security import safe_argv, workspace_name

UUID_RE = re.compile(r"[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
EXE = {"claude_cli": "claude", "gemini_cli": "gemini", "agy_cli": "agy"}
NAME = {"claude_cli": "Claude", "gemini_cli": "Gemini", "agy_cli": "Antigravity"}
TIMEOUT_SECONDS = 900
EXECUTORS = ("auto", "claude", "claude_cli", "gemini", "gemini_cli", "agy", "agy_cli")

AUTH_HINTS = ("/login", "not logged in", "invalid api key", "authentication", "unauthorized",
              "401", "oauth", "please log in", "sign in", "credentials")
NET_HINTS = ("enotfound", "econnrefused", "econnreset", "network", "unable to connect",
             "fetch failed", "etimedout", "getaddrinfo", "offline")

TOOL_PHRASES = {
    "Read": "Lendo arquivos…", "Glob": "Procurando arquivos…", "Grep": "Procurando no código…",
    "LS": "Lendo arquivos…", "Bash": "Rodando comando…", "WebFetch": "Abrindo uma página…",
    "WebSearch": "Pesquisando na web…", "Task": "Delegando uma tarefa…", "Agent": "Delegando uma tarefa…",
    "TodoWrite": "Organizando os passos…",
}


def tool_summary(name, tool_input):
    """Frase curta em PT-BR. Nunca repete argumentos (so o nome do arquivo, quando editando)."""
    if name in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        target = ""
        if isinstance(tool_input, dict):
            raw = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
            if isinstance(raw, str):
                target = pathlib.PurePath(raw).name
        target = re.sub(r"[^\w.\- ]", "", target)[:60]
        return "Editando %s" % target if target else "Editando arquivos…"
    return TOOL_PHRASES.get(name, "Trabalhando…")


def friendly_error(code, kind=None):
    who = NAME.get(kind, "Claude")
    table = {
        "cli_missing": ("O %s não está instalado neste aparelho." % who, "Instalar o %s em Conexões" % who),
        "not_logged_in": ("O %s não está conectado à sua conta." % who, "Entrar no %s em Conexões" % who),
        "timeout": ("A resposta demorou demais e foi interrompida.", "Tentar de novo"),
        "network": ("Sem conexão com a internet para responder.", "Verificar a internet e tentar de novo"),
        "stopped": ("Resposta interrompida.", "Enviar de novo"),
        "failed": ("Não consegui responder agora.", "Tentar de novo"),
        "no_executor": ("Nenhum assistente está instalado neste aparelho.", "Instalar o Claude em Conexões"),
    }
    msg, act = table.get(code, table["failed"])
    return {"code": code, "message_pt": msg, "action_pt": act}


class _Run:
    def __init__(self, chat_id, session_id, text, workspace, executor):
        self.chat_id, self.session_id, self.text = chat_id, session_id, text
        self.workspace, self.executor = workspace, executor
        self.proc = None
        self.kind = None
        self.started_at = None
        self.stopped = False
        self.timed_out = False
        self.answer = ""


class ChatManager:
    def __init__(self, engine):
        self.engine = engine
        self.lock = threading.RLock()
        self.active = {}      # session_id -> _Run em execucao
        self.waiting = {}     # session_id -> [_Run] na fila (ordem de chegada)
        self.runs = {}        # chat_id -> _Run (ativos ou na fila)
        self.closed = False

    # ---- API ---------------------------------------------------------------
    def start(self, text, session_id=None, workspace="default", executor=None):
        if not isinstance(text, str) or not text.strip() or len(text) > 100000:
            raise ValueError("text is required (max 100000 characters)")
        workspace = workspace_name(workspace if workspace is not None else "default")
        if session_id is not None and (not isinstance(session_id, str) or not UUID_RE.fullmatch(session_id)):
            raise ValueError("session_id must be a UUID")
        if executor is not None and executor not in EXECUTORS:
            raise ValueError("unknown executor")
        store = self.engine.store
        external = session_id is not None
        session_id = session_id or str(uuid.uuid4())
        store.session_unhide(session_id)  # voltar a conversar traz a sessao de volta a lista
        if store.chat_conversation(session_id) is None:
            # session_id vindo de fora e desconhecido: a CLI ja tem essa conversa, so retomar.
            store.chat_open(session_id, text.strip().splitlines()[0], workspace, started=external)
            if external:
                # sessao criada no terminal: o --resume so acha o .jsonl no mesmo cwd em que ela nasceu
                from . import sessions
                cwd = sessions.session_cwd(session_id)
                if cwd:
                    store.chat_update_conversation(session_id, cwd=cwd)
        chat_id = str(uuid.uuid4())
        run = _Run(chat_id, session_id, text, workspace, None if executor in (None, "auto") else executor)
        with self.lock:
            busy = session_id in self.active
            store.chat_run_create(chat_id, session_id, "queued" if busy else "running")
            store.chat_add_message(session_id, chat_id, "user", text)
            self.runs[chat_id] = run
            if busy:
                self.waiting.setdefault(session_id, []).append(run)
            else:
                self._launch(run)
        return {"chat_id": chat_id, "session_id": session_id}

    def state(self, chat_id):
        out = self.engine.store.chat_run(chat_id)
        if out is None:
            return None
        run = self.runs.get(chat_id)
        if run is not None and out["state"] in ("running", "queued"):
            out["text"] = run.answer
        return out

    def stop(self, chat_id):
        with self.lock:
            run = self.runs.get(chat_id)
            if run is None:
                return False
            run.stopped = True
            queued = self.waiting.get(run.session_id, [])
            if run in queued:
                queued.remove(run)
                self.runs.pop(chat_id, None)
                self._fail(run, "stopped")
            else:
                self._kill(run)
        return True

    def running_sessions(self):
        with self.lock:
            return set(self.active)

    def stop_session(self, session_id):
        """Para a execucao ativa da sessao e descarta a fila dela. True se havia algo."""
        with self.lock:
            runs = ([self.active[session_id]] if session_id in self.active else []) + \
                list(self.waiting.get(session_id, []))
        for run in runs:
            self.stop(run.chat_id)
        return bool(runs)

    def processes(self):
        """Processos de chat vivos: [{pid, kind, session_id, started_at, exe}]."""
        out = []
        with self.lock:
            for sid, run in self.active.items():
                proc = run.proc
                if proc is not None and proc.poll() is None:
                    out.append({"pid": proc.pid, "kind": EXE.get(run.kind, "claude"), "session_id": sid,
                                "started_at": run.started_at, "argv": list(proc.args)})
        return out

    def close(self):
        with self.lock:
            self.closed = True
            for run in list(self.active.values()):
                run.stopped = True
                self._kill(run)

    # ---- execucao ------------------------------------------------------------
    def _launch(self, run):
        self.active[run.session_id] = run
        threading.Thread(target=self._thread, args=(run,), daemon=True, name="aura-chat").start()

    def _thread(self, run):
        try:
            self._execute(run)
        except Exception:
            self._fail(run, "failed")
        finally:
            with self.lock:
                self.active.pop(run.session_id, None)
                self.runs.pop(run.chat_id, None)
                queue = self.waiting.get(run.session_id) or []
                if queue and not self.closed:
                    nxt = queue.pop(0)
                    self.engine.store.chat_run_set(nxt.chat_id, "running")
                    self._launch(nxt)
                elif not queue:
                    self.waiting.pop(run.session_id, None)

    def _kill(self, run, sig=signal.SIGTERM):
        proc = run.proc
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, sig)
            except OSError:
                try:
                    proc.kill()
                except OSError:
                    pass

    def _emit(self, kind, payload):
        self.engine.store.emit(kind, payload)

    def _fail(self, run, code, kind=None):
        err = friendly_error(code, kind)
        self.engine.store.chat_run_set(run.chat_id, "error", text=run.answer, error=err)
        self._emit("chat.error", dict(err, chat_id=run.chat_id))

    def _execute(self, run):
        order = routing.chat_executors(adapters.installed, run.executor)
        if not order:
            wanted = routing.executor_kind(run.executor)
            return self._fail(run, "cli_missing" if wanted else "no_executor", wanted)
        code, kind = "failed", order[0]
        for kind in order:
            outcome = self._run_one(run, kind)
            if outcome is True:
                return
            code = outcome
            if run.stopped or code not in ("cli_missing", "not_logged_in") or run.answer:
                break
        self._fail(run, code, kind)

    def _build(self, run, kind, conv):
        exe = EXE[kind]
        total = adapters.is_total(self.engine)
        if kind == "claude_cli":
            argv = [exe, "-p", run.text, "--output-format", "stream-json",
                    "--verbose", "--include-partial-messages"]
            cli = conv.get("cli_session") or conv["id"]
            argv += ["--resume", cli] if conv.get("started") else ["--session-id", cli]
            if total:
                argv.append("--dangerously-skip-permissions")  # mesmo gate do run_cli
            try:
                model, _ = routing.resolve({"kind": "claude_cli"}, self.engine.config.models())
            except routing.ModelRefused:
                model = None
            if model:
                argv += ["--model", model]
        elif kind == "gemini_cli":
            argv = [exe, "-p", self._prompt_with_history(run)] + (["--yolo"] if total else [])
        else:
            if total:
                adapters.bootstrap_agy_permissions()
            argv = [exe] + (["--dangerously-skip-permissions"] if total else []) + [
                "-p", self._prompt_with_history(run)]
        return safe_argv(argv)

    def _prompt_with_history(self, run):
        # CLIs sem sessao confiavel: leva o fim da conversa junto.
        past = [m for m in self.engine.store.chat_messages(run.session_id, 12) if m["chat_id"] != run.chat_id]
        if not past:
            return run.text
        lines = ["%s: %s" % ("Usuário" if m["role"] == "user" else "Assistente", m["text"][:2000])
                 for m in past[-10:]]
        return "Conversa até aqui:\n" + "\n".join(lines) + "\n\nNova mensagem do usuário:\n" + run.text

    def _run_one(self, run, kind):
        """True se concluiu; senao o codigo do erro (str)."""
        store = self.engine.store
        conv = store.chat_conversation(run.session_id)
        cwd = adapters.workspace(self.engine.home, {"workspace": run.workspace})
        if kind == "claude_cli" and conv.get("cwd") and os.path.isdir(conv["cwd"]):
            cwd = pathlib.Path(conv["cwd"])
        argv = self._build(run, kind, conv)
        try:
            env = None
            if kind == "claude_cli" and adapters.is_total(self.engine) and hasattr(os, "geteuid") \
                    and os.geteuid() == 0:
                env = dict(os.environ, IS_SANDBOX="1")  # o Claude Code recusa o bypass como root sem isto
            proc = subprocess.Popen(argv, cwd=str(cwd), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, start_new_session=True, env=env)
        except OSError:
            return "cli_missing"
        run.proc = proc
        run.kind, run.started_at = kind, time.time()
        if run.stopped:
            self._kill(run)
        stderr_buf = []

        def drain():
            for chunk in iter(lambda: proc.stderr.read(2048), b""):
                if sum(map(len, stderr_buf)) < 8000:
                    stderr_buf.append(chunk)
        threading.Thread(target=drain, daemon=True).start()

        def on_timeout():
            run.timed_out = True
            self._kill(run)
        timer = threading.Timer(TIMEOUT_SECONDS, on_timeout)
        timer.daemon = True
        timer.start()
        parsed = {"result": None, "session": None, "plain": []}
        state = {"streamed": False}
        try:
            for raw in iter(proc.stdout.readline, b""):
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                if kind == "claude_cli":
                    self._on_line(run, line, parsed, state)
                else:
                    parsed["plain"].append(line)
            proc.wait()
        finally:
            timer.cancel()
            run.proc = None
            for pipe in (proc.stdout, proc.stderr):
                try:
                    pipe.close()
                except OSError:
                    pass
        err_text = adapters.redact(b"".join(stderr_buf).decode("utf-8", "replace")).lower()
        if run.stopped:
            self._fail(run, "stopped", kind)
            return True
        if run.timed_out:
            return "timeout"
        if kind != "claude_cli":
            text = adapters.redact("\n".join(parsed["plain"])).strip()
            if proc.returncode != 0 or not text:
                return self._classify(err_text + " " + text.lower(), proc.returncode)
            self._delta(run, text)
            return self._done(run, None, None)
        result = parsed["result"]
        if result is None or result.get("is_error"):
            body = result.get("result") if result and isinstance(result.get("result"), str) else ""
            return self._classify(err_text + " " + body.lower(), proc.returncode if result is None else 1)
        if not run.answer and isinstance(result.get("result"), str):
            self._delta(run, adapters.redact(result["result"]))
        cost = result.get("total_cost_usd", result.get("cost_usd"))
        return self._done(run, parsed["session"], cost if isinstance(cost, (int, float)) else None)

    @staticmethod
    def _classify(blob, returncode):
        if any(h in blob for h in AUTH_HINTS):
            return "not_logged_in"
        if any(h in blob for h in NET_HINTS):
            return "network"
        if returncode in (126, 127):
            return "cli_missing"
        return "failed"

    def _delta(self, run, text):
        if not text:
            return
        run.answer += text
        self._emit("chat.delta", {"chat_id": run.chat_id, "text": text})

    def _on_line(self, run, line, parsed, state):
        try:
            ev = json.loads(line)
        except ValueError:
            return
        if not isinstance(ev, dict):
            return
        kind = ev.get("type")
        sid = ev.get("session_id")
        if isinstance(sid, str) and UUID_RE.fullmatch(sid):
            parsed["session"] = sid
            self.engine.store.chat_update_conversation(run.session_id, cli_session=sid, started=1)
        if kind == "stream_event":
            inner = ev.get("event") or {}
            if inner.get("type") == "message_start":
                state["streamed"] = False
                if run.answer and not run.answer.endswith("\n"):
                    self._delta(run, "\n\n")
            elif inner.get("type") == "content_block_delta":
                delta = inner.get("delta") or {}
                if delta.get("type") == "text_delta" and isinstance(delta.get("text"), str):
                    state["streamed"] = True
                    self._delta(run, adapters.redact(delta["text"]))
        elif kind == "assistant":
            if ev.get("parent_tool_use_id"):
                return
            for block in (ev.get("message") or {}).get("content") or []:
                if not isinstance(block, dict):
                    continue
                if block.get("type") == "text" and not state["streamed"] and isinstance(block.get("text"), str):
                    if run.answer and not run.answer.endswith("\n"):
                        self._delta(run, "\n\n")
                    self._delta(run, adapters.redact(block["text"]))
                elif block.get("type") == "tool_use":
                    self._emit("chat.tool", {"chat_id": run.chat_id,
                                             "summary": tool_summary(block.get("name"), block.get("input"))})
        elif kind == "result":
            parsed["result"] = ev

    def _done(self, run, cli_session, cost):
        store = self.engine.store
        if cli_session:
            store.chat_update_conversation(run.session_id, cli_session=cli_session, started=1)
        store.chat_add_message(run.session_id, run.chat_id, "assistant", run.answer)
        store.chat_run_set(run.chat_id, "done", text=run.answer)
        payload = {"chat_id": run.chat_id, "text": run.answer, "session_id": run.session_id}
        if cost is not None:
            payload["cost"] = cost
        self._emit("chat.done", payload)
        return True
