#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
if [[ -n $(git status --short --untracked-files=all) ]]; then
  echo 'Development snapshot requires a clean Git worktree' >&2
  exit 30
fi
./scripts/self-test.sh
python3 scripts/check-architecture.py
python3 scripts/check-contracts.py
python3 tools/reference_sources.py verify
SHORT=$(git rev-parse --short=12 HEAD)
OUT=${1:-"$ROOT/build/controlled-sandbox-development-$SHORT.zip"}
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT" "$OUT.sha256"
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
python3 tools/reproducible_zip.py --root "$ROOT" --output "$OUT" --prefix controlled-sandbox-cleanroom/
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "PASS development snapshot: $OUT"
