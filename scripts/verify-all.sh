#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

./scripts/self-test.sh
python3 scripts/check-architecture.py
python3 scripts/check-contracts.py
python3 scripts/check-ports-dispatchers.py
python3 scripts/check-package-boundaries.py
python3 scripts/check-broadcast-model.py
python3 scripts/check-native-file-hooks.py
python3 scripts/generate-sbom.py
python3 scripts/check-m3-source-progress.py
python3 tools/static_android_compile.py
./scripts/test-native.sh
./scripts/test-m3-gate.sh
bash -n scripts/self-test.sh scripts/verify-all.sh scripts/test-m3-gate.sh scripts/check-m3-release-gate.sh

python3 - <<'PY'
from pathlib import Path
for p in Path('scripts').glob('*.ps1'):
    text=p.read_text()
    pairs={'{':'}','(':')','[':']'}
    stack=[]; quote=None
    for i,ch in enumerate(text):
        if quote:
            if ch==quote and (i==0 or text[i-1] != '`'): quote=None
            continue
        if ch in "'\"": quote=ch; continue
        if ch in pairs: stack.append((ch,i))
        elif ch in pairs.values():
            if not stack or pairs[stack[-1][0]] != ch: raise SystemExit(f'{p}: unmatched {ch} at {i}')
            stack.pop()
    if quote: raise SystemExit(f'{p}: unclosed quote')
    if stack: raise SystemExit(f'{p}: unclosed delimiter')
    print('PASS structural PowerShell check', p)
PY

FAKE="$ROOT/build/fake-wrapper-home"
rm -rf "$FAKE"
mkdir -p "$FAKE/.gradle/wrapper/dists/controlled-sandbox/gradle-8.13-bin/gradle-8.13/bin"
cat > "$FAKE/.gradle/wrapper/dists/controlled-sandbox/gradle-8.13-bin/gradle-8.13/bin/gradle" <<'SH'
#!/usr/bin/env sh
[ "$1" = "help" ] || exit 9
printf 'PASS custom Gradle bootstrap delegation\n'
SH
chmod +x "$FAKE/.gradle/wrapper/dists/controlled-sandbox/gradle-8.13-bin/gradle-8.13/bin/gradle"
java -Duser.home="$FAKE" -Dcontrolled.wrapper.projectDir="$ROOT" \
  -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain help --offline

echo 'PASS all locally executable verification gates'
