#!/usr/bin/env python3
from __future__ import annotations
import json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.architecture_governance import metrics

BASELINE = 'b7372b5002fd205a31acb323c921100d6f9096d4'
ALLOWLIST = ROOT / 'verification/m5-t19-1-t-public-api-allowlist.txt'
errors = []
baseline = metrics(ROOT, BASELINE)
live = metrics(ROOT)

if live['moduleCycles']:
    errors.append('module dependency cycles: ' + repr(live['moduleCycles']))
baseline_cycles = {tuple(row) for row in baseline['packageDependencyCycles']}
live_cycles = {tuple(row) for row in live['packageDependencyCycles']}
new_cycles = sorted(live_cycles - baseline_cycles)
if new_cycles:
    errors.append('new package dependency cycles: ' + repr(new_cycles))
if live['maxMethodComplexity'] > baseline['maxMethodComplexity']:
    errors.append(f"max method complexity grew {baseline['maxMethodComplexity']} -> {live['maxMethodComplexity']}")
if live['maxMethodsPerSource'] > baseline['maxMethodsPerSource']:
    errors.append(f"max methods per source grew {baseline['maxMethodsPerSource']} -> {live['maxMethodsPerSource']}")
for band, baseline_count in baseline['complexityBands'].items():
    if live['complexityBands'][band] > baseline_count:
        errors.append(f"complexity band {band} grew {baseline_count} -> {live['complexityBands'][band]}")
for band, baseline_count in baseline['methodCountBands'].items():
    if live['methodCountBands'][band] > baseline_count:
        errors.append(f"method-count band {band} grew {baseline_count} -> {live['methodCountBands'][band]}")

allowed = {line.strip() for line in ALLOWLIST.read_text(encoding='utf-8').splitlines()
           if line.strip() and not line.lstrip().startswith('#')}
baseline_api = set(baseline['publicApiSignatures'])
live_api = set(live['publicApiSignatures'])
api_additions = sorted(live_api - baseline_api)
unapproved = [item for item in api_additions if item not in allowed]
if unapproved:
    errors.append('unapproved public API additions: ' + repr(unapproved[:20]))
unknown_allowlist = sorted(allowed - set(api_additions))
if unknown_allowlist:
    errors.append('stale public API allowlist entries: ' + repr(unknown_allowlist[:20]))

report = {
    'task': 'M5-T19.1-T',
    'finding': 'P3 architecture governance beyond line counts',
    'baselineCommit': BASELINE,
    'sourceStatus': 'PASS' if not errors else 'FAIL',
    'checks': {
        'moduleCyclesMustBeZero': True,
        'newPackageCyclesForbidden': True,
        'methodComplexityGrowthForbidden': True,
        'methodsPerSourceGrowthForbidden': True,
        'highComplexityBandGrowthForbidden': True,
        'highMethodCountBandGrowthForbidden': True,
        'publicApiGrowthRequiresAllowlist': True,
        'dependencyDirectionGate': 'scripts/check-architecture.py',
    },
    'baseline': {key: value for key, value in baseline.items() if key != 'publicApiSignatures'},
    'live': {key: value for key, value in live.items() if key != 'publicApiSignatures'},
    'publicApi': {
        'baselineCount': baseline['publicApiCount'],
        'liveCount': live['publicApiCount'],
        'additions': api_additions,
        'removals': sorted(baseline_api - live_api),
        'allowlist': sorted(allowed),
    },
    'newPackageCycles': new_cycles,
    'errors': errors,
    'deviceEvidenceCount': 0,
}
out = ROOT / 'build/verification/m5-t19-1-t-architecture-governance.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
if errors:
    print('FAIL M5-T19.1-T architecture governance', file=sys.stderr)
    for error in errors: print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS M5-T19.1-T architecture governance '
      f"(moduleCycles=0 packageCycles={len(live_cycles)} maxComplexity={live['maxMethodComplexity']} "
      f"maxMethods={live['maxMethodsPerSource']} complexityBands={live['complexityBands']} "
      f"methodBands={live['methodCountBands']} publicApi={live['publicApiCount']})")
