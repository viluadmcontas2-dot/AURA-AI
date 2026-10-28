"""Cadeia REA no Termux: instala as ferramentas em passos, com checkpoint em arquivo.

Os comandos passam por adapters.run_process (os testes trocam por um executor falso); a execucao
de verdade e do T18. Retomar = rodar de novo: o passo que ja tem checkpoint e pulado.
"""
import json
import os
import re
import subprocess
import time

from . import adapters, install_consent
from .config import atomic_write_json

REA_VERSION = "6.8.0"
JAR_NAME = "jadx-headless-mcp-0.7.1-all.jar"
JAR_URL = "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/" + JAR_NAME
JAR_SHA256 = "6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
MCP_ARGV = ["rea-agents", "mcp"]


def steps(home):
    jar = str(home / "rea" / JAR_NAME)
    return [
        ("node", [["pkg", "install", "-y", "nodejs-lts", "npm"],  # desde o 24.13 o npm e pacote a parte
                  ["node", "--version"], ["npm", "--version"]]),
        ("jdk", [["pkg", "install", "-y", "openjdk-21"], ["javac", "-version"]]),
        ("jadx", [["curl", "-fL", "-o", jar, JAR_URL],
                  ["sh", "-c", 'echo "%s  %s" | sha256sum -c -' % (JAR_SHA256, jar)]]),
        ("apktool", [["pkg", "install", "-y", "apktool"], ["apktool", "--version"]]),
        ("adb", [["pkg", "install", "-y", "android-tools"], ["adb", "version"]]),
        ("rea", [["npm", "install", "-g", "rea-agents@" + REA_VERSION]]),
        ("mcp", []),  # prova de que o servidor sobe: tools/list de verdade
    ]


NOTICE_MB = 100
NPM_ESTIMATE_MB = {"rea-agents": 40}  # estimativa por pacote conhecido; desconhecido = sem aviso
_NEED = re.compile(r"Need to get ([\d.,]+)\s*(kB|MB|GB)", re.I)
_UNIT = {"kb": 1 / 1024, "mb": 1, "gb": 1024}


def is_download(argv):
    """Comando que BAIXA da rede: pkg install, curl, npm install."""
    return bool(argv) and (argv[0] in ("curl", "wget") or (
        argv[0] in ("pkg", "npm") and "install" in argv))


def step_downloads(commands):
    return any(is_download(argv) for argv in commands)


def _run_quiet(argv, home, engine):
    try:
        return adapters.run_process(argv, home, engine, 120).get("stdout") or ""
    except adapters.ActionError:
        return ""  # estimar nunca derruba nem bloqueia nada


def estimate_download_mb(step, home=None, engine=None):
    """Tamanho aproximado (MB inteiros) do que o passo baixa, ou None se nao der para saber.

    step = (nome, comandos). curl: HEAD/Content-Length. pkg: `apt-get -s install` ("Need to get").
    npm: tabela de pacotes conhecidos."""
    home = home or engine.home
    total, known = 0.0, False
    for argv in step[1]:
        mb = None
        if argv[0] == "curl":
            urls = [a for a in argv if a.startswith("http")]
            if urls:
                head = _run_quiet(["curl", "-s", "-I", "-L", urls[-1]], home, engine)
                sizes = re.findall(r"content-length:\s*(\d+)", head, re.I)
                mb = int(sizes[-1]) / 1048576 if sizes else None
        elif argv[0] == "pkg" and "install" in argv:
            pkgs = [a for a in argv[argv.index("install") + 1:] if not a.startswith("-")]
            out = _run_quiet(["apt-get", "-s", "install", "-y"] + pkgs, home, engine)
            found = _NEED.search(out)
            if found:
                try:
                    mb = float(found.group(1).replace(",", "")) * _UNIT[found.group(2).lower()]
                except ValueError:
                    mb = None
        elif argv[0] == "npm" and "install" in argv:
            names = [a.rsplit("@", 1)[0] if a.rfind("@") > 0 else a for a in argv if not a.startswith("-")]
            hits = [NPM_ESTIMATE_MB[n] for n in names if n in NPM_ESTIMATE_MB]
            mb = sum(hits) if hits else None
        if mb is not None:
            total, known = total + mb, True
    return int(round(total)) if known else None


def network_blocks(engine):
    """Dados moveis (rede medida) nao baixam pacote. Sem estado conhecido = Wi-Fi."""
    return bool(engine.config.settings()["network"]["metered"])


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


def _exchange(home, calls, timeout):
    """JSON-RPC por stdio, uma mensagem por linha. calls = [(id, method, params)] depois do initialize.

    Devolve {id: resposta}. Sobe o servidor MCP, fala e encerra (uma conversa por chamada)."""
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "aura-engine", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
    ] + [{"jsonrpc": "2.0", "id": i, "method": m, "params": p} for i, m, p in calls]
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
    replies = {}
    for line in (out or "").splitlines():
        try:
            reply = json.loads(line)
        except ValueError:
            continue
        if isinstance(reply, dict) and "id" in reply:
            replies[reply["id"]] = reply
    return replies


def list_tools(home, timeout=60):
    """tools/list de verdade (sem paginacao)."""
    reply = _exchange(home, [(2, "tools/list", {})], timeout).get(2)
    if isinstance(reply, dict) and isinstance(reply.get("result"), dict):
        return [t for t in reply["result"].get("tools", []) if isinstance(t, dict)]
    raise adapters.ActionError("mcp_failed", "O servidor MCP não respondeu ao tools/list")


def call_tool(home, name, arguments, timeout=900):
    """tools/call: devolve o resultado estruturado (dict). Erro da ferramenta vira ActionError tool_failed."""
    reply = _exchange(home, [(2, "tools/call", {"name": name, "arguments": arguments})], timeout).get(2)
    if not isinstance(reply, dict):
        raise adapters.ActionError("mcp_failed", "O servidor MCP não respondeu à ferramenta %s" % name)
    result = reply.get("result")
    if not isinstance(result, dict) or result.get("isError") or "error" in reply:
        raise adapters.ActionError("tool_failed", "A ferramenta %s falhou" % name)
    if isinstance(result.get("structuredContent"), dict):
        return result["structuredContent"]
    for item in result.get("content") or []:
        try:
            parsed = json.loads(item.get("text", ""))
        except (ValueError, AttributeError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return {}


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
    notices = []
    for name, commands in steps(home):
        event = {"id": job_id, "name": name}
        if name in done:
            emit("tool.install", dict(event, state="ready", pct=100))  # ja tem checkpoint: pula
            continue
        if step_downloads(commands):
            # Nada é baixado sem um plano aceito (docs/FLUXOS_DE_USO.md secao 7).
            install_consent.require_grant(engine)
            if network_blocks(engine):
                # Dados moveis: nao falha. Guarda onde parou e espera o Wi-Fi (rodar de novo retoma).
                atomic_write_json(home / "rea" / "checkpoint.json", {"done": done, "waiting_wifi": name})
                emit("tool.install", dict(event, state="waiting_wifi", pct=0))
                return {"state": "waiting_wifi", "waiting_step": name, "steps": sorted(done),
                        "checkpoint": last_done(home), "download_notices": notices,
                        "message": "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo: "
                                   "continuo do passo %s." % name}
            install_consent.check_step_size(engine, name, commands)
            mb = estimate_download_mb((name, commands), home, engine)
            if mb is not None and mb > NOTICE_MB:  # aviso discreto: nunca bloqueia
                notices.append({"step": name, "mb": mb})
                emit("download.notice", {"id": job_id, "step": name, "mb": mb})
        install_consent.note_item(engine, name)
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
    return {"steps": sorted(done), "checkpoint": last_done(home), "download_notices": notices}


def run(request, engine):
    action = request.get("action")
    (engine.home / "rea").mkdir(parents=True, exist_ok=True)
    if action == "setup":
        return engine.installs.run_planned(request, "reform_tools", None, lambda: setup(engine))
    if action == "tools":
        return {"tools": [{"name": t.get("name"), "risk": tool_risk(t)} for t in list_tools(engine.home)]}
    if action == "measure":
        return measure(request, engine)
    if action == "reverse_engineer":
        from . import reverse
        return reverse.run(request, engine)
    raise adapters.ActionError("unsupported_action", "REA action unsupported")
