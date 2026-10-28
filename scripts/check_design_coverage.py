#!/usr/bin/env python3
"""Valida o inventário fixo das 45 telas e 21 funções do Design System.

Modo normal: exige cobertura estrutural de todos os IDs e arquivos.
Modo --release: também exige status feito e evidência para TODOS os 66 IDs.
Apenas valida a estrutura da referência, não a autenticidade do resultado.\nNão substitui revisão humana e teste real no aparelho.
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
        if len(cells) not in (3, 4):
            errors.append(f"{ident}: linha exige descrição, status, evidência e Tarefa opcional")
        rows[ident] = cells

for ident in sorted(EXPECTED - rows.keys()):
    errors.append(f"ID obrigatório ausente: {ident}")
for ident in sorted(rows.keys() - EXPECTED):
    errors.append(f"ID inesperado: {ident}")
if len(screens) != 45:
    errors.append(f"esperadas 45 telas .dc.html, encontradas {len(screens)}")
for screen in screens:
    matches = [ident for ident, cells in rows.items()
               if ident.startswith("UI-") and len(cells) in (3, 4) and screen in cells[0]]
    if len(matches) != 1:
        errors.append(f"tela sem vínculo UI único: {screen}")

# Além de cada arquivo ter um ID único, cada ID UI precisa ter uma tela.
# Sem isso, uma linha pode listar duas telas e deixar outra linha sem nenhuma.
for ident, cells in rows.items():
    if ident.startswith("UI-") and len(cells) in (3, 4):
        linked = [screen for screen in screens if screen in cells[0]]
        if len(linked) != 1:
            errors.append(f"{ident}: exige vínculo com exatamente uma tela .dc.html")

# Elementos que exigem interação com Android/Termux, impossível de
# reproduzir com build/JVM de Actions. CI verde não basta para estes IDs.
DEVICE_REQUIRED = {
    "UI-05", "UI-06", "UI-08", "UI-09", "UI-14",
    "UI-15", "UI-16", "UI-35", "UI-43", "UI-44",
    "FN-01", "FN-02", "FN-10", "FN-11", "FN-13",
    "FN-15", "FN-16", "FN-18", "FN-20",
}

# O gate não aceita uma afirmação textual ('OK', 'testado', etc.) como prova.
# Provas de CI apontam para um job identificável; provas físicas devem ter
# relatório auditável, com aparelho, procedimento, resultado e registro.
CI_JOB = re.compile(
    r"^https://github\.com/viluadmcontas2-dot/AURA-AI/actions/runs/[1-9][0-9]*/job/[1-9][0-9]*$"
)


def evidence_reference(ident, raw):
    if CI_JOB.fullmatch(raw):
        return True
    relative = f"docs/design/evidence/{ident}.md"
    if raw != relative:
        return False
    report = os.path.join(ROOT, *relative.split("/"))
    try:
        body = open(report, encoding="utf-8").read()
    except OSError:
        return False
    fields_present = all(
        re.search(rf"(?m)^{field}:\s*\S", body)
        for field in ("Dispositivo", "Procedimento", "Resultado", "Registro")
    )
    results = re.findall(r"(?mi)^Resultado:[ \t]*([^\r\n]*)$", body)
    passed = len(results) == 1 and results[0].strip().lower() == "aprovado"
    return bool(fields_present and passed)


complete = 0
for ident, cells in rows.items():
    if len(cells) not in (3, 4):
        continue
    status = cells[1].lower()
    evidence = cells[2]
    referenced = evidence_reference(ident, evidence)
    if "--release" in sys.argv and ident in DEVICE_REQUIRED:
        report_path = f"docs/design/evidence/{ident}.md"
        if evidence != report_path:
            referenced = False
    verified_cell = status == "feito" and referenced
    if verified_cell:
        complete += 1
    if "--release" in sys.argv and not verified_cell:
        errors.append(
            f"{ident}: sem prova rastreável (status={status or 'sem status'}; "
            "exigido job Actions completo ou relatório físico auditável)"
        )

print(f"{complete}/{len(EXPECTED)} itens marcados feitos com evidência; {len(screens)} telas no design")
for error in errors:
    print("ERRO:", error)
sys.exit(1 if errors else 0)
