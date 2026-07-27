#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/wrapper-bootstrap-test"
rm -rf "$OUT"
mkdir -p "$OUT/source/fake-gradle/bin" "$OUT/project/gradle/wrapper" "$OUT/home"
cat > "$OUT/source/fake-gradle/bin/gradle" <<'GRADLE'
#!/usr/bin/env sh
[ "${1:-}" = help ] || exit 9
printf 'PASS verified Gradle bootstrap delegation\n'
GRADLE
chmod +x "$OUT/source/fake-gradle/bin/gradle"
python3 - "$OUT/source/fake-gradle-bin.zip" "$OUT/source" <<'PY'
from pathlib import Path
import sys, zipfile
out=Path(sys.argv[1]); source=Path(sys.argv[2])
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for p in sorted((source/'fake-gradle').rglob('*')):
        if p.is_file(): z.write(p,p.relative_to(source).as_posix())
PY
SUM=$(sha256sum "$OUT/source/fake-gradle-bin.zip" | awk '{print $1}')
cat > "$OUT/project/gradle/wrapper/gradle-wrapper.properties" <<EOF
distributionUrl=$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve().as_uri())' "$OUT/source/fake-gradle-bin.zip")
distributionSha256Sum=$SUM
EOF
java -Duser.home="$OUT/home" -Dcontrolled.wrapper.projectDir="$OUT/project" \
  "$ROOT/tools/wrapper-src/org/gradle/wrapper/GradleWrapperMain.java" help
mkdir -p "$OUT/jar-home"
java -Duser.home="$OUT/jar-home" -Dcontrolled.wrapper.projectDir="$OUT/project" \
  -cp "$ROOT/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain help
MARKER="$OUT/home/.gradle/wrapper/dists/controlled-sandbox/fake-gradle-bin/.distribution.sha256"
[[ $(cat "$MARKER") == "$SUM" ]]
python3 - "$OUT/project/gradle/wrapper/gradle-wrapper.properties" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); text=p.read_text();
text=text.replace(next(line.split('=',1)[1] for line in text.splitlines() if line.startswith('distributionSha256Sum=')), '0'*64)
p.write_text(text)
PY
if java -Duser.home="$OUT/home" -Dcontrolled.wrapper.projectDir="$OUT/project" \
    "$ROOT/tools/wrapper-src/org/gradle/wrapper/GradleWrapperMain.java" help \
    >"$OUT/mismatch.out" 2>"$OUT/mismatch.err"; then
  echo 'Wrapper accepted a checksum mismatch' >&2
  exit 42
fi
grep -q 'checksum mismatch' "$OUT/mismatch.err"
echo 'PASS Gradle wrapper checksum and fail-closed self-test'
