#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
ONLINE=0
VERIFY_TWICE=0
for arg in "$@"; do
  case "$arg" in
    --online) ONLINE=1 ;;
    --verify-twice) VERIFY_TWICE=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
if [[ -n $(git status --short --untracked-files=all) ]]; then
  echo 'Reproducible Android build requires a clean Git worktree' >&2
  exit 30
fi
python3 scripts/check-build-environment.py --android
./scripts/check-wrapper-bootstrap.sh
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
export TZ=UTC LC_ALL=C LANG=C
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$ROOT/.gradle-reproducible"}
ARGS=(--no-daemon --no-build-cache --no-parallel --stacktrace)
if [[ $ONLINE -eq 0 ]]; then ARGS+=(--offline); fi
TASKS=(clean check :app:verifyControlledReleaseSigning :sandbox-companion32:verifyControlledReleaseSigning \
  :fixture-basic:assembleRelease :fixture-compat32:assembleRelease \
  :app:assembleRelease :sandbox-companion32:assembleRelease)
COMMIT=$(git rev-parse --short=12 HEAD)
OUT="$ROOT/build/reproducible/$COMMIT"
rm -rf "$OUT"
mkdir -p "$OUT"
run_build() {
  local label=$1
  ./gradlew "${ARGS[@]}" "${TASKS[@]}"
  mkdir -p "$OUT/$label"
  find app/build/outputs/apk fixture-basic/build/outputs/apk fixture-compat32/build/outputs/apk \
    sandbox-companion32/build/outputs/apk -type f -name '*.apk' -print0 \
    | LC_ALL=C sort -z \
    | while IFS= read -r -d '' apk; do cp "$apk" "$OUT/$label/$(basename "$apk")"; done
  (cd "$OUT/$label" && sha256sum *.apk | LC_ALL=C sort > SHA256SUMS.txt)
  python3 tools/release_apk_signing.py verify \
    --host "$OUT/$label/app-release.apk" \
    --companion "$OUT/$label/sandbox-companion32-release.apk" \
    --fixture64 "$OUT/$label/fixture-basic-release.apk" \
    --fixture32 "$OUT/$label/fixture-compat32-release.apk"
}
run_build first
if [[ $VERIFY_TWICE -eq 1 ]]; then
  run_build second
  cmp "$OUT/first/SHA256SUMS.txt" "$OUT/second/SHA256SUMS.txt"
  for artifact in "$OUT/first"/*.apk; do
    cmp "$artifact" "$OUT/second/$(basename "$artifact")"
  done
fi
python3 - "$ROOT" "$OUT" "$COMMIT" "$VERIFY_TWICE" <<'PY'
from pathlib import Path
import hashlib, json, os, platform, subprocess, sys
root, out, commit, twice = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], bool(int(sys.argv[4]))
def digest(path):
    h=hashlib.sha256(); h.update(path.read_bytes()); return h.hexdigest()
artifacts=[]
for path in sorted((out/'first').glob('*.apk')):
    artifacts.append({'name':path.name,'sha256':digest(path),'size':path.stat().st_size})
manifest={
  'schemaVersion':1,
  'commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
  'commitShort':commit,
  'sourceDateEpoch':int(os.environ['SOURCE_DATE_EPOCH']),
  'toolchainLockSha256':digest(root/'build-environment.lock.json'),
  'platform':platform.platform(),
  'verifiedTwice':twice,
  'artifacts':artifacts,
}
(out/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY
echo "PASS reproducible Android build: $OUT"
