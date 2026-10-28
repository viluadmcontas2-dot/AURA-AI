#!/usr/bin/env python3
"""Garante que toda tela e função do design está na matriz docs/design/COBERTURA.md.
Uso: check_design_coverage.py            (CI normal: falha se faltar linha para alguma tela)
     check_design_coverage.py --release  (gate do APK: falha se algo não estiver 'feito' com evidência)"""
import json, re, sys, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, 'docs/design')
telas = sorted(f for f in os.listdir(os.path.join(D, 'telas')) if f.endswith('.dc.html'))
md = open(os.path.join(D, 'COBERTURA.md'), encoding='utf-8').read()
rows = {}
for line in md.splitlines():
    m = re.match(r'\|\s*((?:UI|FN)-\d+)\s*\|(.*)\|\s*$', line)
    if m:
        cells = [c.strip() for c in m.group(2).split('|')]
        rows[m.group(1)] = cells  # [descricao/arquivo, status, evidencia]
errs = []
for t in telas:
    if not any(t in ' '.join(c) for c in rows.values()):
        errs.append('tela sem linha na matriz: ' + t)
if '--release' in sys.argv:
    for k, c in rows.items():
        status = c[1].lower() if len(c) > 1 else ''
        ev = c[2] if len(c) > 2 else ''
        if status != 'feito' or not ev or ev == '-':
            errs.append(f'{k} não está feito com evidência ({status or "sem status"})')
total = len(rows); feito = sum(1 for c in rows.values() if len(c) > 2 and c[1].lower() == 'feito' and c[2] not in ('', '-'))
print(f'{feito}/{total} itens feitos com evidência; {len(telas)} telas no design')
for e in errs: print('ERRO:', e)
sys.exit(1 if errs else 0)
