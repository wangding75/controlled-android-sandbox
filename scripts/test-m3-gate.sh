#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/pass" "$TMP/fail"
cat > "$TMP/pass/m3-gate.json" <<'JSON'
{
  "status": "PASS",
  "activityCreated": true,
  "guestActivityCreateCount": 2,
  "fixtureActivityCreated": true,
  "nativeFixtureProbe": true,
  "guestProcessObserved": true,
  "guestProcessCount": 2,
  "multiInstanceDataRoots": true,
  "componentSuitePassed": true,
  "diagnosticsCollected": true,
  "diagnosticFileCount": 1,
  "fatalCrashOrAnr": false,
  "durationSeconds": 1200
}
JSON
"$ROOT/scripts/check-m3-release-gate.sh" "$TMP/pass" >/dev/null
cp "$TMP/pass/m3-gate.json" "$TMP/fail/m3-gate.json"
python3 - "$TMP/fail/m3-gate.json" <<'PY'
import json,sys
p=sys.argv[1]
data=json.load(open(p,encoding='utf-8'))
data['diagnosticsCollected']=False
json.dump(data,open(p,'w',encoding='utf-8'))
PY
set +e
"$ROOT/scripts/check-m3-release-gate.sh" "$TMP/fail" >/dev/null 2>&1
code=$?
set -e
[[ $code -eq 21 ]] || { echo "Expected strict gate failure 21, got $code" >&2; exit 1; }
echo 'PASS strict M3 evidence gate self-test'
