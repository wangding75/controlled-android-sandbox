#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Android/Sdk"}}
CACHE_DIR="$ROOT/.toolchain-cache"
ARCHIVE=""
ONLINE=0
ACCEPT=0
SKIP_PACKAGES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk-root) SDK_ROOT=$2; shift 2 ;;
    --cache-dir) CACHE_DIR=$2; shift 2 ;;
    --command-line-tools) ARCHIVE=$2; shift 2 ;;
    --online) ONLINE=1; shift ;;
    --accept-licenses) ACCEPT=1; shift ;;
    --skip-sdk-packages) SKIP_PACKAGES=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
JAVA_TEXT=$(java -version 2>&1 || true)
if ! grep -Eq 'version "17([."]|$)' <<<"$JAVA_TEXT"; then
  echo "M5 device lab requires JDK 17 on PATH/JAVA_HOME. Observed: $JAVA_TEXT" >&2
  exit 3
fi
readarray -t CLI < <(python3 - "$ROOT/build-environment.lock.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))['deviceLab']['commandLineTools']['linux']
print(x['filename']); print(x['url']); print(x['sha256']); print(json.load(open(sys.argv[1]))['deviceLab']['commandLineTools']['version'])
PY
)
FILENAME=${CLI[0]}; URL=${CLI[1]}; EXPECTED=${CLI[2]}; VERSION=${CLI[3]}
mkdir -p "$SDK_ROOT" "$CACHE_DIR"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  [[ -n "$ARCHIVE" ]] || ARCHIVE="$CACHE_DIR/$FILENAME"
  if [[ ! -f "$ARCHIVE" ]]; then
    if [[ $ONLINE -ne 1 ]]; then
      echo "Command-line tools archive unavailable. Place $FILENAME in $CACHE_DIR or pass --online." >&2
      exit 4
    fi
    curl -fL --retry 3 -o "$ARCHIVE" "$URL"
  fi
  ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  [[ "$ACTUAL" == "$EXPECTED" ]] || { echo "SHA-256 mismatch for $ARCHIVE" >&2; exit 5; }
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  unzip -q "$ARCHIVE" -d "$TMP"
  [[ -x "$TMP/cmdline-tools/bin/sdkmanager" ]] || { echo "Unexpected command-line tools archive layout" >&2; exit 6; }
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -a "$TMP/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi
export ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT"
if [[ $SKIP_PACKAGES -ne 1 ]]; then
  mapfile -t PACKAGES < <(python3 - "$ROOT/build-environment.lock.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
seen=set()
for item in x['toolchain']['android']['sdkPackages'] + x['deviceLab']['sdkPackages']:
    if item not in seen:
        print(item); seen.add(item)
PY
)
  if [[ $ACCEPT -eq 1 ]]; then yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true; fi
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}"
fi
ESCAPED=${SDK_ROOT//:/\\:}
printf 'sdk.dir=%s\n' "$ESCAPED" > "$ROOT/local.properties"
python3 "$ROOT/scripts/check-build-environment.py" --android
mkdir -p "$ROOT/artifacts/m5-device-lab-toolchain"
python3 - "$ROOT" "$SDK_ROOT" "$VERSION" "$EXPECTED" "$JAVA_TEXT" <<'PY'
import json,sys,datetime
root,sdk,version,sha,java=sys.argv[1:]
lock=json.load(open(root+'/build-environment.lock.json'))
packages=list(dict.fromkeys(lock['toolchain']['android']['sdkPackages']+lock['deviceLab']['sdkPackages']))
record={'schemaVersion':1,'generatedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'androidSdk':sdk,'commandLineToolsVersion':version,'commandLineToolsSha256':sha,
        'javaVersion':java,'sdkPackages':packages}
open(root+'/artifacts/m5-device-lab-toolchain/toolchain.json','w').write(json.dumps(record,indent=2)+'\n')
PY
printf 'PASS M5 device-lab toolchain bootstrap: %s\n' "$SDK_ROOT"
