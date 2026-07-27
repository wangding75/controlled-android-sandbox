#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes"
javac --release 17 -d "$TMP/classes" \
  "$ROOT/tools/wrapper-src/org/gradle/wrapper/GradleWrapperMain.java"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import hashlib, json, sys, zipfile
root=Path(sys.argv[1])
lock=json.loads((root/'build-environment.lock.json').read_text())
expected=lock['toolchain']['gradle']['compatibilityWrapperJarSha256']
actual=hashlib.sha256((root/'gradle/wrapper/gradle-wrapper.jar').read_bytes()).hexdigest()
if actual != expected:
    raise SystemExit(f'Compatibility wrapper JAR checksum mismatch: {actual} != {expected}')
with zipfile.ZipFile(root/'gradle/wrapper/gradle-wrapper.jar') as archive:
    if 'org/gradle/wrapper/GradleWrapperMain.class' not in archive.namelist():
        raise SystemExit('Compatibility wrapper JAR has no GradleWrapperMain class')
print('PASS wrapper source compile and compatibility JAR checksum')
PY
