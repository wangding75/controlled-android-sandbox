#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/verification/SOURCE_SHA256SUMS.txt"
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
cd "$ROOT"
{
  git ls-files --cached --others --exclude-standard
} | LC_ALL=C sort -u | while IFS= read -r path; do
  case "$path" in
    verification/SOURCE_SHA256SUMS.txt|build/*|artifacts/*|reports/t57-r03/T57_R03_REPOSITORY_CLEANUP_REPORT.md) continue ;;
  esac
  [[ -f "$path" ]] && sha256sum "$path"
done > "$TMP"
mv "$TMP" "$OUT"
trap - EXIT
echo "PASS source hashes: $OUT"
