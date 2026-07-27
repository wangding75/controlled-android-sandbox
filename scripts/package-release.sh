#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
EVIDENCE=${1:-}
"$ROOT/scripts/verify-all.sh"
"$ROOT/scripts/check-m3-release-gate.sh" "$EVIDENCE"
OUT="$ROOT/build/controlled-sandbox-m3-release.zip"
rm -f "$OUT"
mkdir -p "$ROOT/build"
cd "$ROOT"
zip -qr "$OUT" . -x '.git/*' 'build/*' '*/build/*' '.gradle/*'
echo "PASS release package: $OUT"
