#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
if [[ -n $(git status --short) ]]; then
  echo 'Development snapshot requires a clean Git worktree' >&2
  exit 30
fi
./scripts/verify-all.sh
SHORT=$(git rev-parse --short=12 HEAD)
OUT=${1:-"$ROOT/build/controlled-sandbox-development-$SHORT.zip"}
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT" "$OUT.sha256"
git archive --format=zip --prefix=controlled-sandbox-cleanroom/ -o "$OUT" HEAD
sha256sum "$OUT" > "$OUT.sha256"
echo "PASS development snapshot: $OUT"
