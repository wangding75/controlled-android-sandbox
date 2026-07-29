#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK_ROOT" ]]; then
  echo 'ANDROID_SDK_ROOT or ANDROID_HOME is required' >&2
  exit 2
fi
find_sdkmanager() {
  local candidate
  for candidate in \
    "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
    "$SDK_ROOT/cmdline-tools/bin/sdkmanager" \
    "$(command -v sdkmanager 2>/dev/null || true)"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
  done
  return 1
}
SDKMANAGER=$(find_sdkmanager) || {
  echo 'Android command-line tools are required; sdkmanager was not found' >&2
  exit 3
}
mapfile -t PACKAGES < <(python3 - "$ROOT/build-environment.lock.json" <<'PY'
import json,sys
for item in json.load(open(sys.argv[1]))['toolchain']['android']['sdkPackages']:
    print(item)
PY
)
if [[ ${1:-} == '--accept-licenses' ]]; then
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
elif [[ $# -gt 0 ]]; then
  echo "Unknown argument: $1" >&2
  exit 4
fi
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}"
python3 "$ROOT/scripts/check-build-environment.py" --android
printf 'PASS installed locked Android components under %s\n' "$SDK_ROOT"
