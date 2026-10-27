#!/usr/bin/env bash
# Roda o Gradle do app com cadeado entre processos: só UM build por vez nesta máquina
# (cada Gradle usa ~2 GB; vários agentes ao mesmo tempo estouram a memória).
#
# Uso:  scripts/gw.sh [--dir <pasta android>] <argumentos do gradle>
# Ex.:  scripts/gw.sh :app:testDebugUnitTest --tests "*LocalStoreTest" --offline
#
# Funciona também em worktree: o gradlew (que o git ignora) vem do clone principal.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
dir="$here/../android"
if [ "${1:-}" = "--dir" ]; then dir="$2"; shift 2; fi
dir="$(cd "$dir" && pwd)"

# Clone principal = primeira linha de `git worktree list`.
main="$(git -C "$dir" worktree list --porcelain | sed -n '1s/^worktree //p')"
gradlew="$dir/gradlew"
[ -f "$gradlew" ] || gradlew="$main/android/gradlew"
[ -f "$gradlew" ] || { echo "gradlew não encontrado em $dir nem em $main/android"; exit 2; }

# sdk.dir do local.properties (o ANDROID_HOME da máquina pode apontar para um SDK sem android-35).
for f in "$dir/local.properties" "$main/android/local.properties"; do
  if [ -f "$f" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$f" | tr -d '\r' | head -1)"
    if [ -n "$sdk" ]; then export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"; break; fi
  fi
done

lock="${TMPDIR:-/tmp}/aura-gradle.lock"
waited=0
while ! mkdir "$lock" 2>/dev/null; do
  holder="$(cat "$lock/pid" 2>/dev/null || true)"
  # Cadeado órfão: o dono morreu, ou nunca chegou a gravar o pid.
  if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then rm -rf "$lock"; continue; fi
  if [ -z "$holder" ] && [ -n "$(find "$lock" -maxdepth 0 -mmin +1 2>/dev/null)" ]; then rm -rf "$lock"; continue; fi
  sleep 3; waited=$((waited + 3))
  if [ "$waited" -ge 3600 ]; then echo "EXIT=3 cadeado ocupado há mais de 60 min"; exit 3; fi
done
echo $$ > "$lock/pid"
trap 'rm -rf "$lock"' EXIT INT TERM

"$gradlew" -p "$dir" "$@"
code=$?
echo "EXIT=$code"
exit $code
