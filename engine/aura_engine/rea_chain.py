"""Cadeia REA no Termux: instala as ferramentas em passos, com checkpoint em arquivo.

Os comandos passam por adapters.run_process (os testes trocam por um executor falso); a execucao
de verdade e do T18. Retomar = rodar de novo: o passo que ja tem checkpoint e pulado.
"""
import json
import os
import subprocess
import time

from . import adapters
from .config import atomic_write_json

REA_VERSION = "6.8.0"
JAR_NAME = "jadx-headless-mcp-0.7.1-all.jar"
JAR_URL = "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/" + JAR_NAME
JAR_SHA256 = "6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
MCP_ARGV = ["rea-agents", "mcp"]


def steps(home):
    jar = str(home / "rea" / JAR_NAME)
    return [
        ("node", [["pkg", "install", "-y", "nodejs-lts"], ["node", "--version"]]),
        ("jdk", [["pkg", "install", "-y", "openjdk-21"], ["javac", "-version"]]),
        ("jadx", [["curl", "-fL", "-o", jar, JAR_URL],
                  ["sh", "-c", 'echo "%s  %s" | sha256sum -c -' % (JAR_SHA256, jar)]]),
        ("apktool", [["pkg", "install", "-y", "apktool"], ["apktool", "--version"]]),
        ("adb", [["pkg", "install", "-y", "android-tools"], ["adb", "version"]]),
        ("rea", [["npm", "install", "-g", "rea-agents@" + REA_VERSION]]),
        ("mcp", []),  # prova de que o servidor sobe: tools/list de verdade
    ]


def _done(home):
    try:
        done = json.loads((home / "rea" / "checkpoint.json").read_text("utf-8")).get("done", {})
    except (OSError, ValueError, AttributeError):
        return {}
    return done if isinstance(done, dict) else {}


def last_done(home):
    """Ultimo passo concluido (o checkpoint que o app mostra), ou None."""
    done = _done(home)
    return next((name for name, _ in reversed(steps(home)) if name in done), None)


def list_tools(home, timeout=60):
    """tools/list de verdade: JSON-RPC por stdio, uma mensagem por linha (sem paginacao)."""
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "aura-engine", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    ]
    env = dict(os.environ, REA_JADX_MCP_JAR=str(home / "rea" / JAR_NAME))
    try:
        proc = subprocess.Popen(MCP_ARGV, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, encoding="utf-8", env=env)
    except OSError:
        raise adapters.ActionError("tool_missing", "Servidor MCP do REA não encontrado: rode o setup")
    try:
        out, _ = proc.communicate("".join(json.dumps(m) + "\n" for m in messages), timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        out, _ = proc.communicate()
    for line in (out or "").splitlines():
        try:
            reply = json.loads(line)
        except ValueError:
            continue
        if isinstance(reply, dict) and reply.get("id") == 2 and isinstance(reply.get("result"), dict):
            return [t for t in reply["result"].get("tools", []) if isinstance(t, dict)]
    raise adapters.ActionError("mcp_failed", "O servidor MCP não respondeu ao tools/list")


def tool_risk(tool):
    """Ferramenta que mexe no aparelho nunca e leitura: risco medio (write)."""
    notes = tool.get("annotations") if isinstance(tool.get("annotations"), dict) else {}
    flag = tool.get("mutatesDevice") or notes.get("mutatesDevice")
    return "write" if flag or "device-mutating" in str(tool.get("description", "")) else "read"


def _pct(request, field):
    value = request.get(field)
    if type(value) is not int or not 0 <= value <= 100:
        raise adapters.ActionError("invalid_request", "%s must be an integer from 0 to 100" % field)
    return value


def measure(request, engine):
    """Reversao, Reconstrucao e Fidelidade: campos do job. Fidelidade so existe depois de teste real."""
    test = request.get("real_test")
    real = (isinstance(test, dict) and bool(test.get("device"))
            and type(test.get("flows_total")) is int and test["flows_total"] > 0)
    out = {"reversalPct": _pct(request, "reversal_pct"), "rebuildPct": _pct(request, "rebuild_pct"),
           "fidelityPct": _pct(request, "fidelity_pct") if real else None}
    engine.store.emit("reform.progress", dict(
        out, id=getattr(engine.context, "job_id", None), stage=request.get("stage"),
        checkpointId=request.get("checkpoint_id")))
    return out


def setup(engine):
    home, emit = engine.home, engine.store.emit
    job_id = getattr(engine.context, "job_id", None)
    done = _done(home)
    for name, commands in steps(home):
        event = {"id": job_id, "name": name}
        if name in done:
            emit("tool.install", dict(event, state="ready", pct=100))  # ja tem checkpoint: pula
            continue
        emit("tool.install", dict(event, state="installing", pct=0))
        try:
            for argv in commands:
                adapters.run_process(argv, home, engine, 1800)
            if name == "mcp" and not list_tools(home):
                raise adapters.ActionError("mcp_failed", "O servidor MCP não listou nenhuma ferramenta")
        except adapters.ActionError as err:
            if err.code == "cancelled":
                raise
            emit("tool.install", dict(event, state="failed", pct=0))
            raise adapters.ActionError("rea_step_failed", "Passo %s falhou: %s. Rode de novo para "
                                       "continuar daqui." % (name, adapters.redact(str(err))))
        done[name] = time.time()
        atomic_write_json(home / "rea" / "checkpoint.json", {"done": done})
        emit("tool.install", dict(event, state="ready", pct=100))
    return {"steps": sorted(done), "checkpoint": last_done(home)}


def run(request, engine):
    action = request.get("action")
    (engine.home / "rea").mkdir(parents=True, exist_ok=True)
    if action == "setup":
        return setup(engine)
    if action == "tools":
        return {"tools": [{"name": t.get("name"), "risk": tool_risk(t)} for t in list_tools(engine.home)]}
    if action == "measure":
        return measure(request, engine)
    raise adapters.ActionError("unsupported_action", "REA action unsupported")
