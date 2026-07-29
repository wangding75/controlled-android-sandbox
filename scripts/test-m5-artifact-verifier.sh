#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/build-environment.lock.json" "$TMP/build-environment.lock.json"
mkdir -p "$TMP/app/build/outputs/apk/debug" \
  "$TMP/fixture-basic/build/outputs/apk/debug" \
  "$TMP/sandbox-companion32/build/outputs/apk/debug"
python3 - "$TMP" <<'PY'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1])
specs=[
 ('app/build/outputs/apk/debug/app-debug.apk',['arm64-v8a','x86_64'],'libcontrolled_sandbox_native.so'),
 ('fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk',['arm64-v8a','x86_64'],'libcontrolled_sandbox_fixture.so'),
 ('sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk',['armeabi-v7a','x86'],'libcontrolled_sandbox_native32.so'),
]
for rel,abis,lib in specs:
    path=root/rel
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml',b'manifest')
        for abi in abis: z.writestr(f'lib/{abi}/{lib}',b'ELF')
PY
mkdir -p "$TMP/sdk/build-tools/35.0.0"
cat > "$TMP/sdk/build-tools/35.0.0/aapt2" <<'AAPT'
#!/usr/bin/env bash
case "$3" in
  *app-debug.apk) id=com.warden.controlledsandbox.debug ;;
  *fixture-basic-debug.apk) id=com.warden.controlledsandbox.fixture ;;
  *sandbox-companion32-debug.apk) id=com.warden.controlledsandbox.companion32.debug ;;
  *) exit 9 ;;
esac
printf "package: name='%s' versionCode='1'\n" "$id"
AAPT
cat > "$TMP/sdk/build-tools/35.0.0/apksigner" <<'SIGN'
#!/usr/bin/env bash
[[ "$1" == verify ]]
SIGN
chmod +x "$TMP/sdk/build-tools/35.0.0/aapt2" "$TMP/sdk/build-tools/35.0.0/apksigner"
ANDROID_SDK_ROOT="$TMP/sdk" python3 "$ROOT/scripts/verify-device-test-artifacts.py" --android-tools --root "$TMP" --output "$TMP/out"
test -f "$TMP/out/build-manifest.json"
test -f "$TMP/out/SHA256SUMS.txt"
python3 - "$TMP/sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p,'a') as z: z.writestr('lib/x86_64/libcontrolled_sandbox_native32.so',b'ELF')
PY
if python3 "$ROOT/scripts/verify-device-test-artifacts.py" --root "$TMP" >"$TMP/fail.out" 2>"$TMP/fail.err"; then
  echo 'Artifact verifier accepted a forbidden Companion ABI' >&2
  exit 40
fi
grep -q 'APK ABI mismatch' "$TMP/fail.err"
echo 'PASS M5 APK artifact verifier positive and fail-closed tests'
