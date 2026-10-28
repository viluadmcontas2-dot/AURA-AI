"""Sessoes do Claude Code (~/.claude/projects/*/*.jsonl) mescladas com os chats do motor.

Somente leitura sobre o .jsonl do Claude: o motor nunca escreve nem apaga esses arquivos.
Tolerante a arquivo corrompido ou enorme: ignora linhas ruins e le o arquivo INTEIRO de forma incremental
(so os bytes novos a cada consulta), com ids de mensagem estaveis e paginacao por cursor.
"""
import base64
import datetime
import json
import os
import pathlib
import re
import threading
import time

from . import adapters
from .chat import UUID_RE, tool_summary

MAX_FILES = 300            # sessoes mais recentes consideradas
MAX_BYTES = 192 * 1024 * 1024  # leitura maxima por arquivo; acima disso so a cauda e lida e o corte e avisado
MAX_LINE = 1024 * 1024     # linha maior que isto (ex.: imagem em base64) e descartada
TITLE_LEN = 80
NOISE_PREFIXES = ("<command-", "<local-command", "<system-reminder", "Caveat:", "[Request interrupted",
                  "<user-prompt-submit-hook", "<bash-")
EXTERNAL_RUNNING_SECS = 8  # arquivo escrito ha menos que isto, sem execucao do motor = alguem responde no terminal
MAX_INDEXES = 32
_lock = threading.RLock()
_indexes = {}              # caminho -> _Index (leitura incremental de cada .jsonl)


class HistoryChanged(Exception):
    """O cursor veio de outra revisao do historico (o arquivo foi reescrito)."""


class BadCursor(ValueError):
    pass


def claude_projects():
    return pathlib.Path(os.environ.get("HOME") or pathlib.Path.home()) / ".claude" / "projects"


def _epoch(ts):
    if not isinstance(ts, str):
        return None
    try:
        return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _clean(text):
    return re.sub(r"\s+", " ", text).strip()


def _user_text(msg):
    content = msg.get("content")
    if isinstance(content, str):
        text = content
    elif isinstance(content, list):
        text = "\n".join(b.get("text", "") for b in content
                         if isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str))
    else:
        return ""
    text = text.strip()
    return "" if text.startswith(NOISE_PREFIXES) else text


class _Index:
    """Mensagens limpas de um .jsonl, lidas aos poucos. Cresce so para a frente; reescrever o arquivo troca `gen`."""

    def __init__(self, path):
        self.path = path
        self.gen = 0
        self.reset()

    def reset(self):
        self.inode = None
        self.offset = 0
        self.entries = []        # {id, role, text, ts}
        self.cwd = None
        self.valid = False
        self.partial = False     # o comeco do arquivo ficou de fora (arquivo maior que MAX_BYTES)
        self.mtime_ns = 0
        self.size = 0

    @property
    def title(self):
        for e in self.entries:
            if e["role"] == "user":
                return _clean(e["text"])[:TITLE_LEN] or "Conversa sem título"
        return "Conversa sem título"

    @property
    def count(self):
        return sum(1 for e in self.entries if e["role"] != "tool")

    def feed(self, ev, pos):
        """Um evento do .jsonl vira zero ou mais entradas (a ultima fala do Claude cresce se vier mais texto)."""
        kind = ev.get("type")
        if kind in ("user", "assistant") and isinstance(ev.get("message"), dict):
            self.valid = True
        if self.cwd is None and isinstance(ev.get("cwd"), str) and ev["cwd"]:
            self.cwd = ev["cwd"]
        if kind not in ("user", "assistant") or ev.get("isMeta") or ev.get("isSidechain"):
            return
        msg = ev.get("message")
        if not isinstance(msg, dict):
            return
        uid = ev.get("uuid")
        base = uid if isinstance(uid, str) and re.fullmatch(r"[\w.-]{1,80}", uid) else "@%d" % pos
        ts = _epoch(ev.get("timestamp"))
        out = self.entries
        if kind == "user":
            text = _user_text(msg)
            if text:
                out.append({"id": "claude:%s:0" % base, "role": "user", "text": text, "ts": ts})
            return
        content = msg.get("content")
        if isinstance(content, str):
            content = [{"type": "text", "text": content}]
        for i, block in enumerate(content if isinstance(content, list) else []):
            if not isinstance(block, dict):
                continue
            if block.get("type") == "text" and isinstance(block.get("text"), str) and block["text"].strip():
                if out and out[-1]["role"] == "assistant":
                    out[-1]["text"] += "\n\n" + block["text"].strip()
                else:
                    out.append({"id": "claude:%s:%d" % (base, i), "role": "assistant",
                                "text": block["text"].strip(), "ts": ts})
            elif block.get("type") == "tool_use":
                line = tool_summary(block.get("name"), block.get("input"))
                if not (out and out[-1]["role"] == "tool" and out[-1]["text"] == line):
                    out.append({"id": "claude:%s:%d" % (base, i), "role": "tool", "text": line, "ts": ts})

    def refresh(self):
        """Le so o que foi acrescentado desde a ultima vez. Levanta OSError se o arquivo sumiu."""
        st = os.stat(self.path)
        if self.inode is not None and (st.st_ino != self.inode or st.st_size < self.offset):
            self.gen += 1  # reescrito ou truncado: o que ja foi lido nao vale mais
            self.reset()
        if self.inode is not None and (st.st_mtime_ns, st.st_size) == (self.mtime_ns, self.size):
            return
        self.inode = st.st_ino
        with open(self.path, "rb") as f:
            if self.offset == 0 and st.st_size > MAX_BYTES:
                # arquivo gigante: le so a cauda (o que importa agora) e AVISA que o comeco ficou de fora
                f.seek(st.st_size - MAX_BYTES)
                f.readline(MAX_LINE)
                self.partial = True
                self.offset = f.tell()
            f.seek(self.offset)
            self._consume(f)
        self.mtime_ns, self.size = st.st_mtime_ns, st.st_size

    def _consume(self, f):
        while True:
            pos = f.tell()
            raw = f.readline(MAX_LINE)
            if not raw:
                return
            if not raw.endswith(b"\n"):
                if len(raw) >= MAX_LINE:
                    # linha gigante (ex.: imagem em base64): descarta o resto dela
                    while True:
                        rest = f.readline(MAX_LINE)
                        if not rest:
                            return  # ainda sendo escrita: tenta de novo na proxima
                        if rest.endswith(b"\n"):
                            break
                    self.offset = f.tell()
                    continue
                try:
                    ev = json.loads(raw)  # sem quebra de linha no fim: vale se o JSON ja esta completo
                except ValueError:
                    return  # linha pela metade (a CLI esta escrevendo): fica para a proxima
                if isinstance(ev, dict):
                    self.feed(ev, pos)
                self.offset = f.tell()
                return
            self.offset = f.tell()
            try:
                ev = json.loads(raw)
            except ValueError:
                continue
            if isinstance(ev, dict):
                self.feed(ev, pos)


def _index(path):
    """O indice atualizado do arquivo (levanta OSError se ele sumiu)."""
    key = str(path)
    with _lock:
        idx = _indexes.get(key)
        if idx is None:
            if len(_indexes) >= MAX_INDEXES:
                _indexes.pop(next(iter(_indexes)))
            idx = _indexes[key] = _Index(path)
        idx.refresh()
        return idx


def _meta(path):
    """{cwd, title, messages} de um .jsonl, ou None se nao parece uma sessao."""
    idx = _index(path)
    if not idx.valid:
        return None
    return {"cwd": idx.cwd, "title": idx.title, "messages": idx.count}


def _project_name(cwd, folder):
    if cwd:
        parts = [p for p in pathlib.PurePosixPath(cwd.replace("\\", "/")).parts if p not in ("/", "")]
        return "/".join(parts[-2:]) if parts else "/"
    return folder.strip("-").replace("-", "/")[-60:]


def _files():
    found = []
    try:
        for p in claude_projects().glob("*/*.jsonl"):
            if UUID_RE.fullmatch(p.stem) and p.is_file() and not p.is_symlink():
                try:
                    found.append((p.stat().st_mtime, p))
                except OSError:
                    pass
    except OSError:
        return []
    found.sort(key=lambda t: t[0], reverse=True)
    return [p for _, p in found[:MAX_FILES]]


def find_file(session_id):
    if not isinstance(session_id, str) or not UUID_RE.fullmatch(session_id):
        return None
    try:
        for p in claude_projects().glob("*/%s.jsonl" % session_id):
            if p.is_file() and not p.is_symlink():
                return p
    except OSError:
        pass
    return None


def session_cwd(session_id):
    """cwd gravado pelo Claude na sessao (ou None)."""
    path = find_file(session_id)
    try:
        return (_meta(path) or {}).get("cwd") if path else None
    except OSError:
        return None


def _safe_meta(path):
    try:
        return _meta(path)
    except OSError:
        return None


def _source_file(engine, session_id):
    """O .jsonl desta conversa: o do proprio id ou, em dados antigos, o da sessao interna (alias) da CLI.
    Com os dois, vale o mais recente."""
    candidates = []
    conv = engine.store.chat_conversation(session_id)
    ids = [session_id]
    if conv and conv.get("cli_session") and conv["cli_session"] != session_id:
        ids.append(conv["cli_session"])
    for sid in ids:
        path = find_file(sid)
        if path is not None:
            try:
                candidates.append((path.stat().st_mtime, path))
            except OSError:
                pass
    return max(candidates, key=lambda t: t[0])[1] if candidates else None


def exists(engine, session_id):
    path = _source_file(engine, session_id)
    return (path is not None and _safe_meta(path) is not None) or engine.store.chat_conversation(session_id) is not None


# ---- lista ----------------------------------------------------------------------------
def _encode(payload):
    return base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")


def _decode(cursor):
    try:
        raw = base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4))
        data = json.loads(raw)
    except (ValueError, TypeError):
        raise BadCursor("cursor inválido")
    if not isinstance(data, dict):
        raise BadCursor("cursor inválido")
    return data


def listing(engine, limit=100, cursor=None):
    """(linhas, next_cursor). O cursor e a posicao na lista ordenada (opaco para o cliente)."""
    store = engine.store
    hidden = store.session_hidden_ids()
    titles = store.session_titles()
    convs = {c["id"]: c for c in store.chat_conversations()}
    alias = {c["cli_session"]: c["id"] for c in convs.values()
             if c.get("cli_session") and c["cli_session"] != c["id"]}
    running = engine.chat.running_sessions()
    rows = {}
    for path in _files():
        sid = alias.get(path.stem, path.stem)
        if sid in hidden or path.stem in hidden:
            continue
        try:
            idx = _index(path)
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if not idx.valid:
            continue
        conv = convs.get(sid)
        row = rows.get(sid)
        if row is not None and mtime <= row["_mtime"]:
            continue  # o id e o alias da mesma conversa: vale o arquivo mais recente
        terminal = conv is None or conv.get("origin") == "terminal"
        rows[sid] = {"id": sid, "title": titles.get(sid) or (conv["title"] if conv and alias.get(path.stem) else idx.title),
                     "project": _project_name(idx.cwd, path.parent.name),
                     "updated_at": max(mtime, conv["updated"]) if conv else mtime,
                     "messages": max(idx.count, conv["messages"]) if conv else idx.count,
                     "source": "terminal" if terminal else "aura", "running": sid in running,
                     "canonical_id": sid, "history_complete": not idx.partial,
                     "active_chat_id": None, "_mtime": mtime}
    for sid, conv in convs.items():
        if sid in rows or sid in hidden:
            continue
        rows[sid] = {"id": sid, "title": titles.get(sid) or conv["title"], "project": conv["workspace"],
                     "updated_at": conv["updated"], "messages": conv["messages"], "source": "aura",
                     "running": sid in running, "canonical_id": sid, "history_complete": True,
                     "active_chat_id": None, "_mtime": 0}
    ordered = sorted(rows.values(), key=lambda r: (r["updated_at"], r["id"]), reverse=True)
    start = 0
    if cursor:
        start = _decode(cursor).get("o")
        if not isinstance(start, int) or start < 0:
            raise BadCursor("cursor inválido")
    limit = max(1, min(int(limit), 500))
    page = ordered[start:start + limit]
    for row in page:
        row.pop("_mtime", None)
        info = engine.chat.active_info(row["id"])
        if info:
            row["active_chat_id"] = info["chat_id"]
    more = start + limit < len(ordered)
    return page, (_encode({"o": start + limit}) if more else None)


# ---- historico ------------------------------------------------------------------------
def _db_entries(engine, session_id):
    return [{"id": "aura:%d" % m["id"], "role": m["role"], "text": m["text"], "ts": m["created_at"]}
            for m in engine.store.chat_messages(session_id, 2000)]


def _load(engine, session_id):
    """(entradas, gen, parcial, versao-do-arquivo) ou None se a sessao nao existe / foi escondida."""
    if session_id in engine.store.session_hidden_ids():
        return None
    path = _source_file(engine, session_id)
    if path is not None:
        try:
            idx = _index(path)
        except OSError:
            idx = None
        if idx is not None and idx.valid:
            with _lock:
                return list(idx.entries), idx.gen, idx.partial, "%d-%d" % (idx.mtime_ns, idx.size)
    if engine.store.chat_conversation(session_id) is None:
        return None
    return _db_entries(engine, session_id), 0, False, "none"


def _version(engine, session_id, file_version):
    n, last = engine.store.chat_stats(session_id)
    return "%s|%d.%d" % (file_version, n, last)


def version(engine, session_id):
    """Texto que muda sempre que o historico muda (arquivo do Claude ou mensagens do motor). None se nao existe."""
    loaded = _load(engine, session_id)
    return None if loaded is None else _version(engine, session_id, loaded[3])


def state(engine, session_id):
    loaded = _load(engine, session_id)
    if loaded is None:
        return None
    entries, gen, partial, file_version = loaded
    conv = engine.store.chat_conversation(session_id)
    path = _source_file(engine, session_id)
    info = engine.chat.active_info(session_id)
    try:
        mtime = path.stat().st_mtime if path else None
    except OSError:
        mtime = None
    updated = max([t for t in (mtime, conv["updated"] if conv else None) if t] or [0])
    external = bool(mtime and info is None and time.time() - mtime < EXTERNAL_RUNNING_SECS)
    last = engine.store.chat_last_run(session_id)
    return {
        "session_id": session_id, "canonical_id": session_id, "version": _version(engine, session_id, file_version),
        "revision": "r%d" % gen, "messages": sum(1 for e in entries if e["role"] != "tool"), "total": len(entries),
        "updated_at": updated, "running": info is not None, "active_chat_id": info["chat_id"] if info else None,
        "external_running": external, "history_complete": not partial,
        "source": "terminal" if (conv is None or conv.get("origin") == "terminal") else "aura",
        "last_chat": None if last is None else {
            "chat_id": last["id"], "state": last["state"], "request_id": last.get("request_id")},
    }


def page(engine, session_id, limit=100, before=None, after=None):
    """Uma pagina do historico: a mais recente (sem cursor), as anteriores (before) ou o que veio depois (after).
    None se a sessao nao existe. Levanta BadCursor / HistoryChanged."""
    loaded = _load(engine, session_id)
    if loaded is None:
        return None
    entries, gen, partial, _ = loaded
    total = len(entries)
    limit = max(1, min(int(limit), 2000))
    if after is not None:
        first = max(0, min(int(after), total))
        chunk = entries[first:first + 2000]
    elif before:
        cur = _decode(before)
        if cur.get("s") != session_id or not isinstance(cur.get("i"), int) or not isinstance(cur.get("g"), int):
            raise BadCursor("cursor inválido")
        if cur["g"] != gen:
            raise HistoryChanged()
        end = max(0, min(cur["i"], total))
        first = max(0, end - limit)
        chunk = entries[first:end]
    else:
        first = max(0, total - limit)
        chunk = entries[first:]
    conv = engine.store.chat_conversation(session_id)
    info = engine.chat.active_info(session_id)
    out = {
        "session_id": session_id,
        "messages": [{"id": e["id"], "role": e["role"], "text": adapters.redact(e["text"]), "created_at": e["ts"]}
                     for e in chunk],
        "previous_cursor": _encode({"s": session_id, "g": gen, "i": first}) if first > 0 else None,
        "has_more": first > 0, "history_complete": not partial,
        "source": "terminal" if (conv is None or conv.get("origin") == "terminal") else "aura",
        "revision": "r%d" % gen, "active_chat_id": info["chat_id"] if info else None,
        "total": total, "first_index": first,
    }
    if partial:
        out["warning_code"] = "source_partial"
    return out


def messages(engine, session_id, limit=500):
    """Compatibilidade: so a lista da pagina mais recente (None se a sessao nao existe)."""
    result = page(engine, session_id, limit)
    return None if result is None else result["messages"]
