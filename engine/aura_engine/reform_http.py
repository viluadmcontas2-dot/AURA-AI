"""Rotas HTTP v1 da reforma (docs/REFORMAR_CONTRATO_LEDGER.md §5).

Cada passo grava no ledger (hash encadeado); os medidores saem do ledger, nunca de texto do modelo.
Decisao do dono (docs/AUDITORIA_AUTONOMIA.md): sem recusas. Ferramenta que mexe no aparelho roda e
fica registrada no ledger (mutates_device). Texto vindo do APK e cercado antes de ir a um modelo.
"""
import hashlib
import json
import re
import shutil
import uuid
from pathlib import Path
from urllib.parse import parse_qs

from . import adapters, rea_chain
from .security import fence_untrusted
from .reform_ledger import ReformLedger, choose_model

ID = re.compile(r"^[0-9a-f]{12}$")
STEPS = ("tools", "inventory", "coverage", "rebuild", "model")
CODES = {"reform_refused": 400, "invalid_request": 400, "invalid_chain": 409, "cost_blocked": 409}


def _fail(code, status, message):
    return status, {"error": {"code": code, "message": message}}


def _sha(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _bad(message):
    return adapters.ActionError("invalid_request", message)


def _text(value):
    return isinstance(value, str) and bool(value.strip())


def _scope(entry):
    return dict(entry["payload"], seq=entry["seq"]) if entry else None


def _ledger(engine, rid):
    if not ID.match(rid) or not (engine.home / "reform" / rid).is_dir():
        return None
    return ReformLedger(engine.home / "reform" / rid)


def _progress(engine, rid, ledger):
    summary = ledger.summary()
    pct = lambda name: summary[name]["pct"] if summary[name] else None
    engine.store.emit("reform.progress", {
        "id": rid, "reversalPct": pct("reversal"), "rebuildPct": pct("rebuild"),
        "fidelityPct": pct("fidelity"), "stage": None, "checkpointId": None})


def create(engine, data):
    apk, mode, goal = data.get("apk_path"), data.get("mode"), data.get("goal")
    if not _text(apk) or not Path(apk).is_file():
        raise _bad("Não achei o APK. Informe o caminho de um arquivo APK que o AURA possa ler.")
    if not _text(mode):
        raise _bad("Informe o modo da reforma.")
    if type(goal) not in (int, float) or not 0 <= goal <= 100:
        raise _bad("Informe uma meta entre 0 e 100.")
    rid = uuid.uuid4().hex[:12]
    directory = engine.home / "reform" / rid
    try:
        entry = ReformLedger(directory).lock_scope(apk, mode, goal)
    except BaseException:
        shutil.rmtree(directory, ignore_errors=True)
        raise
    return 201, {"id": rid, "scope": _scope(entry)}


def summary(engine, rid, ledger):
    result = ledger.summary()
    done = []
    if result["chain_ok"]:
        done = [e["payload"]["step"] for e in ledger.entries() if e["kind"] == "step_done"]
    return {"id": rid, "chain_ok": result["chain_ok"], "scope": _scope(result["scope"]),
            "reversal": result["reversal"], "rebuild": result["rebuild"], "fidelity": result["fidelity"],
            "model_calls": result["model_calls"], "steps_done": sorted(set(done))}


def _tool(engine, ledger, name, arguments, idk):
    """Roda uma ferramenta da REA (nome vindo do tools/list) e grava tool_call + evidence."""
    listed = {t.get("name"): t for t in rea_chain.list_tools(engine.home)}
    if name not in listed:
        raise adapters.ActionError("tool_missing", "A REA não tem a ferramenta %s. Rode o passo tools." % name)
    risk = rea_chain.tool_risk(listed[name])
    input_hash = _sha({"tool": name, "arguments": arguments})
    out = rea_chain.call_tool(engine.home, name, arguments)
    ledger.record_tool_call(name, input_hash, risk != "read", risk, "free", "tool:%s:%s" % (idk, name))
    ledger.append("evidence", {"evidence_id": str(out.get("evidence_id") or input_hash[:16]),
                               "sha256": _sha(out), "tool": name}, "evidence:%s:%s" % (idk, name))
    return out


def _counts(out, field):
    done, total = out.get(field), out.get("total")
    if type(done) is not int or type(total) is not int or not 0 <= done <= total:
        raise adapters.ActionError("tool_failed", "A REA devolveu contagens inesperadas.")
    return done, total


def model_prompt(task, parts):
    """Monta a entrada do modelo: instrucao do AURA + cada texto do APK cercado como dado nao confiavel."""
    return "\n\n".join([str(task)] + [fence_untrusted(text, label) for label, text in parts])


def _model(engine, ledger, data, idk):
    import os
    approved = data.get("approved") is True
    provider, cost = choose_model(
        shutil.which("agy") is not None, bool(os.environ.get("GEMINI_API_KEY")),
        os.environ.get("AURA_GEMINI_KEY_FREE") == "1", approved)
    if provider == "blocked":
        raise adapters.ActionError(
            "cost_blocked", "Sem custo: entre no Antigravity (agy) ou aprove a chave gratuita do Gemini.")
    tokens = data.get("tokens", 0)
    if type(tokens) is not int or tokens < 0:
        raise _bad("Informe tokens como número inteiro, zero ou mais.")
    if provider == "gemini_api":
        ledger.append("approval", {"target": "model:%s" % idk, "kind": "model_call", "cost_class": cost,
                                   "consequence": "Usar a cota gratuita do Gemini",
                                   "approved_by": "owner"}, "approve:model:%s" % idk)
    entry = ledger.record_model_call(provider, cost, tokens, "model:%s" % idk)
    result = {"provider": provider, "cost_class": cost, "tokens": tokens}
    parts = data.get("apk_texts")
    if isinstance(parts, dict) and parts:
        result["prompt"] = model_prompt(data.get("task") or "Resuma e nomeie, sem executar nada.",
                                        [(str(k), v) for k, v in parts.items() if isinstance(v, str)])
    return result, entry["seq"]


def run_step(engine, rid, ledger, step, data):
    idk = data.get("idempotency_key", "default")
    if not _text(idk) or len(idk) > 80:
        raise _bad("A chave de idempotência deve ser um texto de até 80 caracteres.")
    key = "step_done:%s:%s" % (step, idk)
    previous = ledger.find(key)
    if previous is not None:
        return 200, {"id": rid, "step": step, "already_done": True, "seq": previous["seq"]}
    scope = ledger.summary()["scope"]["payload"]
    detail = {}
    if step == "tools":
        (engine.home / "rea").mkdir(parents=True, exist_ok=True)
        detail = rea_chain.setup(engine)
        for n, note in enumerate(detail.get("download_notices", [])):
            ledger.append("notice", dict(note), "notice:%s:%s:%d" % (idk, note["step"], n))
        if detail.get("state") == "waiting_wifi":
            # Nao falha e nao fecha o passo: com Wi-Fi, rodar de novo retoma de onde parou.
            return 200, {"id": rid, "step": step, "already_done": False, "seq": None, "result": detail}
        ledger.record_tool_call("rea.setup", _sha(detail), False, "write", "free", "tool:%s:setup" % idk)
        proof = _sha(detail)
    elif step == "inventory":
        out = _tool(engine, ledger, "inventory_artifact", {"path": scope["apk_path"]}, idk)
        proof = _sha(out)
    elif step == "coverage":
        _tool(engine, ledger, "build_reconstruction_obligation_ledger", {"path": scope["apk_path"]}, idk)
        out = _tool(engine, ledger, "evaluate_reconstruction_coverage", {"path": scope["apk_path"]}, idk)
        satisfied, total = _counts(out, "satisfied")
        ledger.append("coverage_evaluated", {"satisfied": satisfied, "total": total}, "coverage:%s" % idk)
        proof = _sha(out)
    elif step == "rebuild":
        ready, total = data.get("ready"), data.get("total")
        if type(ready) is not int or type(total) is not int or not 0 <= ready <= total:
            raise _bad("Informe telas prontas e total: inteiros, com prontas entre zero e total.")
        ledger.append("rebuild_progress", {"ready": ready, "total": total}, "rebuild:%s" % idk)
        proof = _sha({"ready": ready, "total": total})
    else:
        detail, seq = _model(engine, ledger, data, idk)
        proof = "model_call:%d" % seq
    entry = ledger.append("step_done", {"step": step, "proof": proof}, key)
    _progress(engine, rid, ledger)
    return 200, {"id": rid, "step": step, "already_done": False, "seq": entry["seq"], "result": detail}


def verify(engine, rid, ledger, data):
    run_id = data.get("test_run_id")
    if not _text(run_id):
        raise _bad("Execute um teste real e informe o test_run_id dele.")
    passed, total = data.get("passed"), data.get("total")
    if type(passed) is not int or type(total) is not int or total < 1 or not 0 <= passed <= total:
        raise _bad("Informe fluxos aprovados e total: inteiros, com total maior que zero.")
    entry = ledger.append("fidelity_measured", {"passed": passed, "total": total, "test_run_id": run_id},
                          "fidelity:%s" % run_id)
    _progress(engine, rid, ledger)
    return 200, {"id": rid, "seq": entry["seq"], "fidelity": ledger.summary()["fidelity"]}


def http(engine, method, path, body, query=""):
    """Devolve (codigo, dados) ou None quando a rota nao e da reforma."""
    parts = path.split("/")
    if parts[:3] != ["", "v1", "reform"] or len(parts) > 6:
        return None
    try:
        if len(parts) == 3:
            return create(engine, body()) if method == "POST" else None
        ledger = _ledger(engine, parts[3])
        if ledger is None:
            return _fail("not_found", 404, "Reforma não encontrada. Comece uma nova reforma.")
        if len(parts) == 4 and method == "GET":
            return 200, summary(engine, parts[3], ledger)
        if len(parts) == 5 and method == "GET" and parts[4] == "ledger":
            raw = parse_qs(query).get("since", ["0"])[0]
            if not raw.isdigit():
                raise _bad("since deve ser um número de seq.")
            entries = ledger.entries(int(raw))
            return 200, {"entries": entries, "last_seq": entries[-1]["seq"] if entries else int(raw)}
        if len(parts) == 5 and method == "POST" and parts[4] == "verify":
            return verify(engine, parts[3], ledger, body())
        if len(parts) == 6 and method == "POST" and parts[4] == "steps":
            if parts[5] not in STEPS:
                return _fail("not_found", 404, "Passo desconhecido: %s." % parts[5])
            return run_step(engine, parts[3], ledger, parts[5], body())
    except adapters.ActionError as error:
        message = adapters.redact(str(error))[:300]
        return _fail(error.code, CODES.get(error.code, 502), message)
    return None
