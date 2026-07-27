#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
EVIDENCE=${1:-}
if [[ -z "$EVIDENCE" ]]; then
  EVIDENCE=$(find "$ROOT/artifacts" -maxdepth 1 -type d -name 'm3-emulator-*' 2>/dev/null | sort | tail -1 || true)
fi
[[ -n "$EVIDENCE" && -f "$EVIDENCE/m3-gate.json" ]] || { echo 'THIRD_MILESTONE_NOT_COMPLETE: emulator evidence missing' >&2; exit 20; }
python3 - "$EVIDENCE/m3-gate.json" <<'PY'
import json,sys
p=sys.argv[1]
data=json.load(open(p,encoding='utf-8-sig'))
checks={
 'status':data.get('status')=='PASS',
 'activityCreated':data.get('activityCreated') is True,
 'guestActivityCreateCount':int(data.get('guestActivityCreateCount',0))>=2,
 'fixtureActivityCreated':data.get('fixtureActivityCreated') is True,
 'nativeFixtureProbe':data.get('nativeFixtureProbe') is True,
 'guestProcessObserved':data.get('guestProcessObserved') is True,
 'guestProcessCount':int(data.get('guestProcessCount',0))>=2,
 'multiInstanceDataRoots':data.get('multiInstanceDataRoots') is True,
 'componentSuitePassed':data.get('componentSuitePassed') is True,
 'diagnosticsCollected':data.get('diagnosticsCollected') is True,
 'diagnosticFileCount':int(data.get('diagnosticFileCount',0))>=1,
 'fatalCrashOrAnr':data.get('fatalCrashOrAnr') is False,
 'durationSeconds':int(data.get('durationSeconds',0))>=1200,
}
failed=[k for k,v in checks.items() if not v]
if failed:
 print('THIRD_MILESTONE_NOT_COMPLETE: '+','.join(failed),file=sys.stderr)
 sys.exit(21)
print('PASS strict third-milestone device evidence gate')
PY
