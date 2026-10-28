#!/usr/bin/env python3
"""Confere vínculos canônicos de requisito -> código -> teste -> Actions.

A entrada é JSON explícito; não tenta interpretar tabelas Markdown.
O resultado não substitui a execução de testes ou o teste físico.
"""
import json
import re
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/remote-traceability.json"
STATUSES = {
    "implementado e comprovado por Actions",
    "coberto por teste, Actions pendente",
    "implementado, teste remoto ausente",
    "somente aparelho",
}
ID_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*$")
SHA_RE = re.compile(r"^[a-f0-9]{40}$")
ACTIONS_RE = re.compile(
    r"^https://github\.com/viluadmcontas2-dot/AURA-AI/actions/runs/[0-9]+$"
)


def checked_path(raw, label, errors):
    if not isinstance(raw, str) or not raw.strip():
        errors.append(f"{label}: caminho ausente")
        return None
    relative = PurePosixPath(raw)
    if relative.is_absolute() or ".." in relative.parts or "\\" in raw:
        errors.append(f"{label}: caminho inválido")
        return None
    path = ROOT.joinpath(*relative.parts)
    if not path.is_file():
        errors.append(f"{label}: arquivo inexistente: {raw}")
        return None
    return path


def check():
    errors = []
    try:
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"TRACE-01: manifest inválido: {exc}", file=sys.stderr)
        return 1
    records = data.get("requirements", []) if isinstance(data, dict) else []
    if not isinstance(data, dict) or data.get("schema") != 1 or not isinstance(records, list) or not records:
        print("TRACE-01: schema ou lista de requisitos inválidos", file=sys.stderr)
        return 1
    seen = set()
    for index, item in enumerate(records, 1):
        label = f"requisito {index}"
        if not isinstance(item, dict):
            errors.append(f"{label}: objeto inválido")
            continue
        ident = item.get("id")
        if not isinstance(ident, str) or not ID_RE.fullmatch(ident):
            errors.append(f"{label}: ID inválido")
            continue
        label = ident
        if ident in seen:
            errors.append(f"{label}: ID duplicado")
        seen.add(ident)
        state = item.get("status")
        if state not in STATUSES:
            errors.append(f"{label}: estado inválido")
            continue
        checked_path(item.get("source"), f"{label} código", errors)
        if state in ("somente aparelho", "implementado, teste remoto ausente"):
            if not isinstance(item.get("reason"), str) or not item["reason"].strip():
                errors.append(f"{label}: falta motivo do limite de prova")
            if item.get("test_file") or item.get("test_symbol"):
                errors.append(f"{label}: teste declarado apesar de status sem teste")
            continue
        test = checked_path(item.get("test_file"), f"{label} teste", errors)
        symbol = item.get("test_symbol")
        if not isinstance(symbol, str) or not symbol.strip():
            errors.append(f"{label}: símbolo de teste ausente")
        elif test is not None:
            body = test.read_text(encoding="utf-8")
            if test.suffix == ".py":
                pattern = rf"^\s*(?:async\s+)?def\s+{re.escape(symbol)}\s*\("
            elif test.suffix == ".kt":
                pattern = rf"\bclass\s+{re.escape(symbol)}\b"
            else:
                pattern = rf"\b{re.escape(symbol)}\b"
            if not re.search(pattern, body, re.MULTILINE):
                errors.append(f"{label}: símbolo de teste não encontrado em {item['test_file']}")
        wf = checked_path(item.get("workflow"), f"{label} workflow", errors)
        job = item.get("job")
        if not isinstance(job, str) or not re.fullmatch(r"[a-z][a-z0-9-]*", job):
            errors.append(f"{label}: job inválido")
        elif wf is not None:
            if not re.search(rf"^  {re.escape(job)}:", wf.read_text(encoding="utf-8"), re.MULTILINE):
                errors.append(f"{label}: job não existe no workflow")
        if state == "implementado e comprovado por Actions":
            proof = item.get("proof")
            if not isinstance(proof, dict) or not SHA_RE.fullmatch(str(proof.get("sha", ""))) or not ACTIONS_RE.fullmatch(str(proof.get("url", ""))) or proof.get("job") != job:
                errors.append(f"{label}: estado comprovado sem URL, SHA e job verificáveis")
    for error in errors:
        print(f"TRACE-01: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"TRACE-01: {len(records)} vínculos estruturais válidos; o CI não prova dispositivo.")
    return 0


if __name__ == "__main__":
    raise SystemExit(check())
