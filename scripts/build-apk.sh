#!/usr/bin/env bash
# Gera o APK do AURA com nome e versão semânticos: auraai-vN.apk
# Uso: scripts/build-apk.sh N   (N deve ser maior que a versão já instalada)
set -euo pipefail
N="${1:?informe o número da versão, ex.: scripts/build-apk.sh 2}"
cd "$(dirname "$0")/../android"
./gradlew :app:assembleDebug --no-daemon --offline -PauraVersion="$N"
OUT="app/build/outputs/apk/debug/auraai-v${N}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"
echo "APK pronto: android/$OUT"
