"""Sessoes do Claude Code (~/.claude/projects/*/*.jsonl) mescladas com os chats do motor.

Somente leitura sobre o .jsonl do Claude: o motor nunca escreve nem apaga esses arquivos.
Tolerante a arquivo corrompido ou enorme: le so um trecho, ignora linhas ruins.
"""
import datetime
import json
import os
import pathlib
import re

from . import adapters
from .chat import UUID_RE, tool_summary

MAX_FILES = 300            # sessoes mais recentes consideradas
MAX_BYTES = 8 * 1024 * 1024  # leitura maxima por arquivo (o resto e ignorado)
MAX_LINE = 1024 * 1024     # linha maior que isto (ex.: imagem em base64) e descartada
TITLE_LEN = 80
NOISE_PREFIXES = ("<command-", "<local-command", "<system-reminder", "Caveat:", "[Request interrupted",
                  "<user-prompt-submit-hook", "<bash-")
_cache = {}


def claude_projects():
    return pathlib.Path(os.environ.get("HOME") or pathlib.Path.home()) / ".claude" / "projects"


def _epoch(ts):
    if not isinstance(ts, str):
        return None
    try:
        return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _lines(path):
    """Gera dicts JSON; linhas ruins ou gigantes sao puladas."""
    read = 0
    with open(path, "rb") as f:
        while read < MAX_BYTES:
            raw = f.readline(MAX_LINE)
            if not raw:
                return
            read += len(raw)
            if not raw.endswith(b"\n") and len(raw) >= MAX_LINE:
                while True:  # descarta o resto da linha gigante
                    rest = f.readline(MAX_LINE)
                    read += len(rest)
                    if not rest or rest.endswith(b"\n"):
                        break
                continue
            try:
                obj = json.loads(raw)
            except ValueError:
                continue
            if isinstance(obj, dict):
                yield obj


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


def _entries(path):
    """Mensagens limpas em ordem: {role: user|assistant|tool, text, ts}."""
    out = []
    for ev in _lines(path):
        kind = ev.get("type")
        if kind not in ("user", "assistant") or ev.get("isMeta") or ev.get("isSidechain"):
            continue
        msg = ev.get("message")
        if not isinstance(msg, dict):
            continue
        ts = _epoch(ev.get("timestamp"))
        if kind == "user":
            text = _user_text(msg)
            if text:
                out.append({"role": "user", "text": text, "ts": ts})
            continue
        content = msg.get("content")
        if isinstance(content, str):
            content = [{"type": "text", "text": content}]
        for block in content if isinstance(content, list) else []:
            if not isinstance(block, dict):
                continue
            if block.get("type") == "text" and isinstance(block.get("text"), str) and block["text"].strip():
                if out and out[-1]["role"] == "assistant":
                    out[-1]["text"] += "\n\n" + block["text"].strip()
                else:
                    out.append({"role": "assistant", "text": block["text"].strip(), "ts": ts})
            elif block.get("type") == "tool_use":
                line = tool_summary(block.get("name"), block.get("input"))
                if not (out and out[-1]["role"] == "tool" and out[-1]["text"] == line):
                    out.append({"role": "tool", "text": line, "ts": ts})
    return out


def _meta(path):
    """{cwd, title, messages} de um .jsonl, ou None se nao parece uma sessao."""
    st = path.stat()
    key = str(path)
    hit = _cache.get(key)
    if hit and hit[0] == (st.st_mtime, st.st_size):
        return hit[1]
    cwd, valid = None, False
    for ev in _lines(path):
        if ev.get("type") in ("user", "assistant") and isinstance(ev.get("message"), dict):
            valid = True
        if cwd is None and isinstance(ev.get("cwd"), str) and ev["cwd"]:
            cwd = ev["cwd"]
    meta = None
    if valid:
        entries = _entries(path)
        users = [e for e in entries if e["role"] == "user"]
        title = _clean(users[0]["text"])[:TITLE_LEN] if users else ""
        meta = {"cwd": cwd, "title": title or "Conversa sem título",
                "messages": sum(1 for e in entries if e["role"] != "tool")}
    if len(_cache) > 2000:
        _cache.clear()
    _cache[key] = ((st.st_mtime, st.st_size), meta)
    return meta


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


def listing(engine, limit=100):
    store = engine.store
    hidden = store.session_hidden_ids()
    titles = store.session_titles()
    convs = {c["id"]: c for c in store.chat_conversations()}
    running = engine.chat.running_sessions()
    rows = {}
    for path in _files():
        sid = path.stem
        if sid in hidden:
            continue
        try:
            meta = _meta(path)
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if meta is None:
            continue
        conv = convs.get(sid)
        rows[sid] = {"id": sid, "title": titles.get(sid) or meta["title"],
                     "project": _project_name(meta["cwd"], path.parent.name),
                     "updated_at": max(mtime, conv["updated"]) if conv else mtime,
                     "messages": max(meta["messages"], conv["messages"]) if conv else meta["messages"],
                     "source": "aura" if conv else "terminal", "running": sid in running}
    for sid, conv in convs.items():
        if sid in rows or sid in hidden:
            continue
        rows[sid] = {"id": sid, "title": titles.get(sid) or conv["title"], "project": conv["workspace"],
                     "updated_at": conv["updated"], "messages": conv["messages"], "source": "aura",
                     "running": sid in running}
    out = sorted(rows.values(), key=lambda r: r["updated_at"], reverse=True)
    return out[:max(1, min(int(limit), 500))]


def _safe_meta(path):
    try:
        return _meta(path)
    except OSError:
        return None


def exists(engine, session_id):
    path = find_file(session_id)
    return (path is not None and _safe_meta(path) is not None) or engine.store.chat_conversation(session_id) is not None


def messages(engine, session_id, limit=500):
    """None se a sessao nao existe ou foi escondida."""
    if session_id in engine.store.session_hidden_ids():
        return None
    limit = max(1, min(int(limit), 2000))
    path = find_file(session_id)
    if path is not None:
        try:
            entries = _entries(path) if _meta(path) is not None else []
        except OSError:
            entries = []
        valid = bool(entries) or (_safe_meta(path) is not None)
        if valid:
            return [{"id": i + 1, "role": e["role"], "text": adapters.redact(e["text"]),
                     "created_at": e["ts"]} for i, e in enumerate(entries[-limit:])]
    if engine.store.chat_conversation(session_id) is None:
        return None
    return [{"id": m["id"], "role": m["role"], "text": m["text"], "created_at": m["created_at"]}
            for m in engine.store.chat_messages(session_id, limit)]
