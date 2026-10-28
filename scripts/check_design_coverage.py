#!/usr/bin/env python3
"""Valida o inventário fixo das 45 telas e 21 funções do Design System.

Modo normal: exige cobertura estrutural de todos os IDs e arquivos.
Modo --release: também exige status feito e evidência para TODOS os 66 IDs.
Não atesta a autenticidade da evidência nem substitui o teste no aparelho.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, "docs", "design")
SCREENS = os.path.join(DESIGN, "telas")
EXPECTED = {f"UI-{i:02d}" for i in range(1, 46)}
EXPECTED.update(f"FN-{i:02d}" for i in range(1, 22))
screens = sorted(name for name in os.listdir(SCREENS) if name.endswith(".dc.html"))
rows = {}
errors = []

with open(os.path.join(DESIGN, "COBERTURA.md"), encoding="utf-8") as handle:
    for line in handle:
        match = re.match(r"\|\s*((?:UI|FN)-\d+)\s*\|(.*)\|\s*$", line)
        if not match:
            continue
        ident = match.group(1)
        cells = [cell.strip() for cell in match.group(2).split("|")]
        if ident in rows:
            errors.append(f"ID duplicado: {ident}")
        if len(cells) != 3:
            errors.append(f"{ident}: linha exige descrição, status e evidência")
        rows[ident] = cells

for ident in sorted(EXPECTED - rows.keys()):
    errors.append(f"ID obrigatório ausente: {ident}")
for ident in sorted(rows.keys() - EXPECTED):
    errors.append(f"ID inesperado: {ident}")
if len(screens) != 45:
    errors.append(f"esperadas 45 telas .dc.html, encontradas {len(screens)}")
for screen in screens:
    matches = [ident for ident, cells in rows.items()
               if ident.startswith("UI-") and len(cells) == 3 and screen in cells[0]]
    if len(matches) != 1:
        errors.append(f"tela sem vínculo UI único: {screen}")

complete = 0
for ident, cells in rows.items():
    if len(cells) != 3:
        continue
    status = cells[1].lower()
    evidence = cells[2]
    verified_cell = status == "feito" and bool(evidence) and evidence != "-"
    if verified_cell:
        complete += 1
    if "--release" in sys.argv and not verified_cell:
        errors.append(f"{ident} não está feito com evidência ({status or 'sem status'})")

print(f"{complete}/{len(EXPECTED)} itens marcados feitos com evidência; {len(screens)} telas no design")
for error in errors:
    print("ERRO:", error)
sys.exit(1 if errors else 0)
