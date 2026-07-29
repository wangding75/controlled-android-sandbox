#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
ONLINE=0
CLEAN=1
for arg in "$@"; do
  case "$arg" in
    --online) ONLINE=1 ;;
    --no-clean) CLEAN=0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
python3 scripts/check-build-environment.py --android
./scripts/check-wrapper-bootstrap.sh
export TZ=UTC LC_ALL=C LANG=C
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$ROOT/.gradle-reproducible"}
ARGS=(--no-daemon --no-build-cache --no-parallel --stacktrace)
if [[ $ONLINE -eq 0 ]]; then ARGS+=(--offline); fi
TASKS=()
if [[ $CLEAN -eq 1 ]]; then TASKS+=(clean); fi
mapfile -t LOCKED_TASKS < <(python3 - <<'PY'
import json
for item in json.load(open('build-environment.lock.json'))['deviceLabBuild']['artifacts']:
    print(item['gradleTask'])
PY
)
TASKS+=(check "${LOCKED_TASKS[@]}")
./gradlew "${ARGS[@]}" "${TASKS[@]}"
COMMIT=$(git rev-parse --short=12 HEAD 2>/dev/null || printf source-archive)
OUT="$ROOT/artifacts/m5-device-lab-build/$COMMIT"
rm -rf "$OUT"
python3 scripts/verify-device-test-artifacts.py --profile device-lab --android-tools --output "$OUT"
printf 'PASS M5 device-lab APK build: %s\n' "$OUT"
