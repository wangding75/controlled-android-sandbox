#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cp "$ROOT/build-environment.lock.json" "$TMP/build-environment.lock.json"
mkdir -p "$TMP/app/build/outputs/apk/debug" \
  "$TMP/fixture-basic/build/outputs/apk/debug" \
  "$TMP/fixture-compat32/build/outputs/apk/debug" \
  "$TMP/sandbox-companion32/build/outputs/apk/debug"
python3 - "$TMP" <<'PY'
from pathlib import Path
import json,sys,zipfile
root=Path(sys.argv[1])
lock=json.load(open(root/'build-environment.lock.json'))
for item in lock['deviceLabBuild']['artifacts']:
    path=root/item['apk']
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('AndroidManifest.xml',b'manifest')
        for abi in item['allowedAbis']:
            for lib in item['requiredNativeLibraries']:
                z.writestr(f'lib/{abi}/{lib}',b'ELF')
PY
mkdir -p "$TMP/sdk/build-tools/35.0.0"
cat > "$TMP/sdk/build-tools/35.0.0/aapt2" <<'AAPT'
#!/usr/bin/env bash
case "$3" in
  *app-debug.apk) id=com.warden.controlledsandbox.debug ;;
  *fixture-basic-debug.apk) id=com.warden.controlledsandbox.fixture ;;
  *fixture-compat32-debug.apk) id=com.warden.controlledsandbox.fixture32 ;;
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
ANDROID_SDK_ROOT="$TMP/sdk" python3 "$ROOT/scripts/verify-device-test-artifacts.py" \
  --profile device-lab --android-tools --root "$TMP" --output "$TMP/out"
test -f "$TMP/out/build-manifest.json"
test -f "$TMP/out/SHA256SUMS.txt"
python3 - "$TMP/fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p,'a') as z:
    z.writestr('lib/x86_64/libcontrolled_sandbox_fixture.so',b'ELF')
PY
if python3 "$ROOT/scripts/verify-device-test-artifacts.py" --profile device-lab --root "$TMP" >"$TMP/fail.out" 2>"$TMP/fail.err"; then
  echo 'Device-lab verifier accepted a forbidden Fixture32 ABI' >&2
  exit 40
fi
grep -q 'APK ABI mismatch' "$TMP/fail.err"
PYTHONPATH="$ROOT/scripts" python3 "$ROOT/scripts/test_m5_device_lab.py"
echo 'PASS M5 device-lab artifact and evidence tests'
