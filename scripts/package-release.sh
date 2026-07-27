#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
EVIDENCE=${1:-}
cd "$ROOT"
if [[ -n $(git status --short --untracked-files=all) ]]; then
  echo 'Release packaging requires a clean Git worktree' >&2
  exit 30
fi
"$ROOT/scripts/verify-all.sh"
"$ROOT/scripts/check-m3-release-gate.sh" "$EVIDENCE"
SHORT=$(git rev-parse --short=12 HEAD)
OUT="$ROOT/build/controlled-sandbox-m3-release-$SHORT.zip"
rm -f "$OUT" "$OUT.sha256"
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
python3 tools/reproducible_zip.py --root "$ROOT" --output "$OUT" --prefix controlled-sandbox-cleanroom/
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "PASS release source package: $OUT"
