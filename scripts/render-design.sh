#!/usr/bin/env bash
# Renderiza cada docs/design/telas/*.dc.html em PNG (390x844) com o Chrome sem janela.
# Saída: .superpowers/design-png/<tela>.png (ignorada pelo git). Uso: scripts/render-design.sh [filtro]
set -u
here="$(cd "$(dirname "$0")/.." && pwd)"
chrome="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
out="$here/.superpowers/design-png"; mkdir -p "$out"; printf '*\n' > "$out/.gitignore"
winroot="$(cd "$here" && pwd -W 2>/dev/null || pwd)"
n=0
for f in "$here"/docs/design/telas/*${1:-}*.dc.html; do
  nome="$(basename "$f" .dc.html)"
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --window-size=390,844 --virtual-time-budget=4000 \
    --screenshot="$(cd "$out" && pwd -W 2>/dev/null || pwd)/$nome.png" "file:///$winroot/docs/design/telas/$nome.dc.html" >/dev/null 2>&1
  [ -s "$out/$nome.png" ] && n=$((n+1)) || echo "FALHOU $nome"
done
echo "renderizadas: $n"
