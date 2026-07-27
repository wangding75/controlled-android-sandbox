#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/reproducible-source-test"
rm -rf "$OUT"
mkdir -p "$OUT"
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct)}
python3 "$ROOT/tools/reproducible_zip.py" --root "$ROOT" --output "$OUT/first.zip" --prefix controlled-sandbox/
python3 "$ROOT/tools/reproducible_zip.py" --root "$ROOT" --output "$OUT/second.zip" --prefix controlled-sandbox/
cmp "$OUT/first.zip" "$OUT/second.zip"
sha256sum "$OUT/first.zip" > "$OUT/SHA256SUMS.txt"
echo 'PASS reproducible source package byte comparison'
