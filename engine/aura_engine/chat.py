"""Chat nativo: roda a CLI em streaming e traduz para eventos chat.* em portugues.

O app nunca ve JSON cru da CLI, fila de jobs ou pedido de aprovacao para conversar.
"""
import datetime
import hashlib
import json
import os
import pathlib
import re
import signal
import subprocess
import threading
import time
import uuid

from . import adapters, pathutil, routing
from .security import safe_argv, workspace_name

UUID_RE = re.compile(r"[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
EXE = {"claude_cli": "claude", "gemini_cli": "gemini", "agy_cli": "agy"}
NAME = {"claude_cli": "Claude", "gemini_cli": "Gemini", "agy_cli": "Antigravity"}
DEFAULT_TIMEOUT = 3600
NO_CONVERSATION = "no conversation found"  # o que a CLI diz quando o --resume nao conhece a sessao


def chat_timeout():
    """Tempo maximo (s) de uma resposta. Padrao 1 h; AURA_CHAT_TIMEOUT (segundos, 1..86400) muda."""
    try:
        return max(1, min(int(os.environ.get("AURA_CHAT_TIMEOUT", DEFAULT_TIMEOUT)), 86400))
    except ValueError:
        return DEFAULT_TIMEOUT
EXECUTORS = ("auto", "claude", "claude_cli", "gemini", "gemini_cli", "agy", "agy_cli")
STATUS_SECS = 5.0     # intervalo do chat.status (batimento do motor para o app); AURA_CHAT_STATUS_SECS muda
STOP_GRACE = 3.0      # depois de pedir para parar (SIGTERM), este prazo vale antes do SIGKILL
TERMINAL_SEQ = 10 ** 9  # seq do retrato de uma execucao que ja terminou: nunca perde para um status velho
READ_TOOLS = ("Read", "Glob", "Grep", "LS")
EDIT_TOOLS = ("Edit", "Write", "MultiEdit", "NotebookEdit")


def status_interval():
    try:
        return max(0.05, min(float(os.environ.get("AURA_CHAT_STATUS_SECS", STATUS_SECS)), 60.0))
    except ValueError:
        return STATUS_SECS


def iso(ts):
    if ts is None:
        return None
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class RequestConflict(ValueError):
    """O mesmo request_id chegou com outro conteudo (HTTP 409 request_conflict)."""


class SessionNotFound(ValueError):
    """A conversa pedida nao existe aqui (HTTP 404 session_not_found). Nunca vira conversa nova sozinha."""


def phase_for_tool(name):
    if name in READ_TOOLS:
        return "reading_files"
    if name in EDIT_TOOLS:
        return "editing_files"
    return "running_tool"

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


STDIN_MIN = 8000  # acima disso o prompt vai por stdin (o teto de um argumento e 10000, em security.safe_argv)
STDIN_INSTRUCTION = "Responda à mensagem do usuário que veio na entrada."


def via_stdin(text):
    return len(text) > STDIN_MIN or text.lstrip().startswith("-")


def friendly_error(code, kind=None):
    who = NAME.get(kind, "Claude")
    table = {
        "cli_missing": ("O %s ainda não está instalado neste Termux." % who, "Instalar o %s em Conexões" % who),
        "not_logged_in": ("O %s não está conectado à sua conta." % who, "Entrar no %s em Conexões" % who),
        "timeout": ("A resposta demorou demais e foi interrompida.", "Tentar de novo"),
        "network": ("Sem conexão com a internet para responder.", "Verificar a internet e tentar de novo"),
        "stopped": ("Resposta interrompida.", "Enviar de novo"),
        "failed": ("Não consegui responder agora.", "Tentar de novo"),
        "no_executor": ("O Claude ainda não está instalado neste Termux.", "Instalar o Claude em Conexões"),
        "resume_failed": ("Não consegui continuar esta conversa. O histórico foi preservado.", "Tentar retomar"),
        "session_not_found": ("Não encontrei esta conversa no Termux.", "Voltar às sessões"),
    }
    msg, act = table.get(code, table["failed"])
    return {"code": code, "message_pt": msg, "action_pt": act}


class _Run:
    def __init__(self, chat_id, session_id, text, workspace, executor, request_id=None):
        self.chat_id, self.session_id, self.text = chat_id, session_id, text
        self.workspace, self.executor, self.request_id = workspace, executor, request_id
        self.proc = None
        self.kind = None
        self.started_at = None
        self.stopped = False
        self.timed_out = False
        self.answer = ""
        # batimento (chat.status): cada execucao conta o proprio seq e termina uma vez
        self.mu = threading.Lock()
        self.done = threading.Event()
        self.seq = 0
        self.state = "queued"
        self.phase = "starting"
        self.launched = None        # time.monotonic() do inicio da execucao
        self.last_output = None     # time.time() da ultima linha vinda da CLI
        self.heartbeat = None       # time.time() do ultimo chat.status


class ChatManager:
    def __init__(self, engine):
        self.engine = engine
        self.lock = threading.RLock()
        self.active = {}      # session_id -> _Run em execucao
        self.waiting = {}     # session_id -> [_Run] na fila (ordem de chegada)
        self.runs = {}        # chat_id -> _Run (ativos ou na fila)
        self.closed = False

    # ---- API ---------------------------------------------------------------
    def start(self, text, session_id=None, workspace="default", executor=None, request_id=None, project_id=None):
        if not isinstance(text, str) or not text.strip() or len(text) > 100000:
            raise ValueError("text is required (max 100000 characters)")
        workspace = workspace_name(workspace if workspace is not None else "default")
        if session_id is not None and (not isinstance(session_id, str) or not UUID_RE.fullmatch(session_id)):
            raise ValueError("session_id must be a UUID")
        if executor is not None and executor not in EXECUTORS:
            raise ValueError("unknown executor")
        if request_id is not None and (not isinstance(request_id, str) or not UUID_RE.fullmatch(request_id)):
            raise ValueError("request_id must be a UUID")
        if project_id is not None and (not isinstance(project_id, str) or len(project_id) > 100):
            raise ValueError("project_id must be a short string")
        store = self.engine.store
        body_hash = hashlib.sha256(json.dumps(
            [text, session_id or "", workspace, project_id or ""], ensure_ascii=False).encode("utf-8")).hexdigest()
        with self.lock:
            if request_id:
                prior = store.chat_run_by_request(request_id)
                if prior is not None:
                    # mesma chave: devolve o resultado original, nunca roda de novo (nem depois de reiniciar)
                    if prior["body_hash"] != body_hash:
                        raise RequestConflict("request_id reused with a different body")
                    return self._started(prior["id"], prior["session_id"], request_id, prior["state"])
            external = session_id is not None
            if external and store.chat_conversation(session_id) is None:
                from . import sessions
                if sessions.find_file(session_id) is None:
                    raise SessionNotFound(session_id)
            session_id = session_id or str(uuid.uuid4())
            store.session_unhide(session_id)  # voltar a conversar traz a sessao de volta a lista
            if store.chat_conversation(session_id) is None:
                # sessao criada no terminal: a CLI ja tem essa conversa, so retomar.
                store.chat_open(session_id, text.strip().splitlines()[0], workspace, started=external)
                if external:
                    # o --resume so acha o .jsonl no mesmo cwd em que ela nasceu
                    from . import sessions
                    cwd = sessions.session_cwd(session_id)
                    store.chat_update_conversation(session_id, origin="terminal", **({"cwd": cwd} if cwd else {}))
            chat_id = str(uuid.uuid4())
            run = _Run(chat_id, session_id, text, workspace, None if executor in (None, "auto") else executor,
                       request_id)
            busy = session_id in self.active
            store.chat_run_create(chat_id, session_id, "queued" if busy else "running", request_id, body_hash,
                                  project_id)
            store.chat_add_message(session_id, chat_id, "user", text)
            self.runs[chat_id] = run
            if busy:
                self.waiting.setdefault(session_id, []).append(run)
            else:
                self._launch(run)
            return self._started(chat_id, session_id, request_id, "queued" if busy else "running")

    @staticmethod
    def _started(chat_id, session_id, request_id, state):
        return {"chat_id": chat_id, "session_id": session_id, "request_id": request_id, "state": state}

    def state(self, chat_id):
        """Retrato de uma execucao (GET /v1/chat/{id}): estado, fase, tempo, texto parcial e erro."""
        out = self.engine.store.chat_run(chat_id)
        if out is None:
            return None
        with self.lock:
            run = self.runs.get(chat_id)
        if run is not None and out["state"] in ("running", "queued", "stopping"):
            out["text"] = run.answer
            out.update(self._status_fields(run))
            out["state"] = run.state if run.state in ("running", "queued", "stopping") else out["state"]
        else:
            out.update(self._terminal_fields(out))
        out["error"] = out.get("error")
        out.pop("created_at", None)
        out.pop("updated_at", None)
        return out

    def by_request(self, request_id):
        row = self.engine.store.chat_run_by_request(request_id)
        return None if row is None else self.state(row["id"])

    def _status_fields(self, run):
        launched = run.launched
        return {"seq": run.seq, "phase": run.phase,
                "elapsed_ms": int((time.monotonic() - launched) * 1000) if launched else 0,
                "last_output_at": iso(run.last_output), "heartbeat_at": iso(run.heartbeat),
                "can_stop": run.state in ("queued", "running")}

    @staticmethod
    def _terminal_fields(row):
        updated, created = row.get("updated_at") or 0, row.get("created_at") or 0
        return {"seq": TERMINAL_SEQ, "phase": "unknown", "elapsed_ms": int(max(0, updated - created) * 1000),
                "last_output_at": None, "heartbeat_at": iso(updated) if updated else None, "can_stop": False}

    def active_info(self, session_id):
        """O que o motor sabe da resposta em andamento desta conversa (ou None): base de running em /state."""
        with self.lock:
            run = self.active.get(session_id)
            queued = len(self.waiting.get(session_id, []))
        if run is None:
            return None
        info = {"chat_id": run.chat_id, "state": run.state, "queued": queued}
        info.update(self._status_fields(run))
        return info

    def stop(self, chat_id):
        """Pede para parar. True se havia o que parar (execucao ativa ou na fila)."""
        return self.request_stop(chat_id) is not None

    def request_stop(self, chat_id):
        """None se nao ha o que parar. Na fila: para na hora (terminal). Rodando: SIGTERM agora, SIGKILL depois
        do prazo, e o terminal `interrupted` so sai quando o processo realmente acabou."""
        with self.lock:
            run = self.runs.get(chat_id)
            if run is None:
                return None
            if run.stopped:
                return {"chat_id": chat_id, "state": run.state, "stopped": False}
            run.stopped = True
            queued = self.waiting.get(run.session_id, [])
            if run in queued:
                queued.remove(run)
                self.runs.pop(chat_id, None)
                self._fail(run, "stopped")
                return {"chat_id": chat_id, "state": "interrupted", "stopped": True}
            run.state = "stopping"
            self.engine.store.chat_run_set(chat_id, "stopping")
            self._emit_status(run)
            self._kill(run)
            threading.Thread(target=self._escalate, args=(run,), daemon=True, name="aura-chat-stop").start()
            return {"chat_id": chat_id, "state": "stopping", "stopped": False}

    def _escalate(self, run):
        if not run.done.wait(STOP_GRACE):
            self._kill(run, signal.SIGKILL)

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
                run.done.set()

    # ---- execucao ------------------------------------------------------------
    def _launch(self, run):
        self.active[run.session_id] = run
        run.state, run.launched = "running", time.monotonic()
        threading.Thread(target=self._thread, args=(run,), daemon=True, name="aura-chat").start()
        threading.Thread(target=self._heartbeat, args=(run,), daemon=True, name="aura-chat-status").start()

    def _thread(self, run):
        try:
            self._execute(run)
        except Exception:
            self._fail(run, "failed")
        finally:
            run.done.set()
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

    # ---- batimento: chat.status sai de um temporizador proprio, nunca da leitura da CLI ----
    def _heartbeat(self, run):
        interval = status_interval()
        while True:
            self._emit_status(run)
            if run.done.wait(interval):
                return

    def _emit_status(self, run):
        with run.mu:
            if run.done.is_set():
                return  # depois do terminal (chat.done/chat.error) nao sai mais status
            run.seq += 1
            run.heartbeat = time.time()
            payload = {"chat_id": run.chat_id, "session_id": run.session_id, "state": run.state}
            payload.update(self._status_fields(run))
            self._emit("chat.status", payload)

    def _set_phase(self, run, phase):
        if run.phase != phase and not run.done.is_set():
            run.phase = phase
            self._emit_status(run)

    def _finish(self, run):
        """Marca o fim da execucao: a partir daqui nenhum chat.status sai. Devolve False se ja tinha acabado."""
        with run.mu:
            if run.done.is_set():
                return False
            run.done.set()
            return True

    def _fail(self, run, code, kind=None):
        if not self._finish(run):
            return
        err = friendly_error(code, kind)
        if code in ("timeout", "stopped") and run.answer.strip():
            # o que ja chegou nao se perde: fica no historico da conversa
            self.engine.store.chat_add_message(run.session_id, run.chat_id, "assistant", run.answer)
        self.engine.store.chat_run_set(run.chat_id, "interrupted" if code == "stopped" else "error",
                                       text=run.answer, error=err)
        self._emit("chat.error", dict(err, chat_id=run.chat_id))

    def _execute(self, run):
        # O chat e SEMPRE do Claude: nenhum outro assistente responde no lugar dele (nem escondido, nem de reserva).
        if not adapters.installed("claude"):
            return self._fail(run, "cli_missing" if run.executor else "no_executor", "claude_cli")
        outcome = self._run_one(run, "claude_cli")
        if outcome is True:
            return
        self._fail(run, outcome, "claude_cli")

    def _build(self, run, conv):
        """(argv, texto_para_stdin). Texto longo ou que comeca com '-' vai por STDIN: o argv tem teto
        (security.safe_argv) e a CLI leria '-texto' como opcao."""
        total = adapters.is_total(self.engine)
        stdin_text = None
        # `claude -p` sem texto no argv le o prompt do stdin
        if via_stdin(run.text):
            argv, stdin_text = ["claude", "-p"], run.text
        else:
            argv = ["claude", "-p", run.text]
        argv += ["--output-format", "stream-json", "--verbose", "--include-partial-messages"]
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
        return safe_argv(argv), stdin_text

    def _run_one(self, run, kind):
        """True se concluiu; senao o codigo do erro (str)."""
        store = self.engine.store
        conv = store.chat_conversation(run.session_id)
        cwd = adapters.workspace(self.engine.home, {"workspace": run.workspace})
        if conv.get("cwd") and os.path.isdir(conv["cwd"]):
            cwd = pathlib.Path(conv["cwd"])
        argv, stdin_text = self._build(run, conv)
        try:
            env = pathutil.child_env()  # PATH ampliado: acha o `claude` recem-instalado em ~/.local/bin
            if adapters.is_total(self.engine) and hasattr(os, "geteuid") and os.geteuid() == 0:
                env["IS_SANDBOX"] = "1"  # o Claude Code recusa o bypass como root sem isto
            proc = subprocess.Popen(argv, cwd=str(cwd),
                                    stdin=subprocess.DEVNULL if stdin_text is None else subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
                                    env=env)
        except OSError:
            return "cli_missing"
        run.proc = proc
        if stdin_text is not None:
            threading.Thread(target=self._feed_stdin, args=(proc, stdin_text), daemon=True).start()
        run.kind, run.started_at = kind, time.time()
        self._set_phase(run, "waiting_claude")
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
        timer = threading.Timer(chat_timeout(), on_timeout)
        timer.daemon = True
        timer.start()
        parsed = {"result": None, "session": None}
        state = {"streamed": False}
        try:
            for raw in iter(proc.stdout.readline, b""):
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                self._on_line(run, line, parsed, state)
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
        result = parsed["result"]
        # Corrida entre "parar" e "concluir": se a CLI ja tinha terminado bem, a resposta vale (done), nao "parada".
        finished_ok = result is not None and not result.get("is_error") and proc.returncode == 0
        if run.stopped and not finished_ok:
            self._fail(run, "stopped", kind)
            return True
        if run.timed_out:
            return "timeout"
        if result is None or result.get("is_error"):
            body = result.get("result") if result and isinstance(result.get("result"), str) else ""
            blob = err_text + " " + body.lower()
            if NO_CONVERSATION in blob and conv.get("started"):
                # A CLI nao conhece mais essa conversa. NUNCA recomeca sozinho: o historico fica e a pessoa escolhe.
                return "resume_failed"
            return self._classify(blob, proc.returncode if result is None else 1)
        if not run.answer and isinstance(result.get("result"), str):
            self._delta(run, adapters.redact(result["result"]))
        cost = result.get("total_cost_usd", result.get("cost_usd"))
        return self._done(run, parsed["session"], cost if isinstance(cost, (int, float)) else None)

    @staticmethod
    def _feed_stdin(proc, text):
        """Escreve o prompt no stdin e fecha (EOF). Numa thread: texto grande nao trava se a CLI nao ler."""
        try:
            proc.stdin.write(text.encode("utf-8"))
        except (OSError, ValueError):
            pass
        finally:
            try:
                proc.stdin.close()
            except (OSError, ValueError):
                pass

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
        run.last_output = time.time()
        self._emit("chat.delta", {"chat_id": run.chat_id, "text": text})
        self._set_phase(run, "responding")

    def _on_line(self, run, line, parsed, state):
        try:
            ev = json.loads(line)
        except ValueError:
            return
        if not isinstance(ev, dict):
            return
        run.last_output = time.time()
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
                    self._set_phase(run, phase_for_tool(block.get("name")))
        elif kind == "result":
            parsed["result"] = ev

    def _done(self, run, cli_session, cost):
        if not self._finish(run):
            return True
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
