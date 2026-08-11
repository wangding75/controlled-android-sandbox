#!/usr/bin/env python3
from __future__ import annotations
import json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))
from tools.architecture_governance import metrics

# T51 compares the final source tree with the verified T51 start tree.  The
# earlier M5-T19 baseline remains covered by the historical gate; this gate
# additionally freezes the T51 capability budget instead of treating required
# framework compatibility additions as an accidental unbounded regression.
BASELINE = '1ebf56e912246b3b8f3deb9e0b9b7019c24b751d'
HISTORICAL_BASELINE = 'b7372b5002fd205a31acb323c921100d6f9096d4'
ALLOWLIST = ROOT / 'verification/m5-t19-1-t-public-api-allowlist.txt'
T51_COMPLEXITY_BANDS = {'atLeast15': 127, 'atLeast25': 29, 'atLeast40': 3}
T51_METHOD_COUNT_BANDS = {'atLeast40': 29, 'atLeast80': 9, 'atLeast120': 2}
errors = []
baseline = metrics(ROOT, BASELINE)
live = metrics(ROOT)

if live['moduleCycles']:
    errors.append('module dependency cycles: ' + repr(live['moduleCycles']))
baseline_cycles = {tuple(row) for row in baseline['packageDependencyCycles']}
live_cycles = {tuple(row) for row in live['packageDependencyCycles']}
# An SCC may contract when an edge is removed.  A contracted component is not
# a newly introduced cycle, so compare component membership rather than tuple
# equality alone.
new_cycles = sorted(
    cycle for cycle in live_cycles
    if not any(set(cycle).issubset(set(previous)) for previous in baseline_cycles))
if new_cycles:
    errors.append('new package dependency cycles: ' + repr(new_cycles))
if live['maxMethodComplexity'] > baseline['maxMethodComplexity']:
    errors.append(f"max method complexity grew {baseline['maxMethodComplexity']} -> {live['maxMethodComplexity']}")
if live['maxMethodsPerSource'] > baseline['maxMethodsPerSource']:
    errors.append(f"max methods per source grew {baseline['maxMethodsPerSource']} -> {live['maxMethodsPerSource']}")
for band, expected_count in T51_COMPLEXITY_BANDS.items():
    if live['complexityBands'][band] != expected_count:
        errors.append(
            f"T51 complexity budget {band} changed {expected_count} -> "
            f"{live['complexityBands'][band]}")
for band, expected_count in T51_METHOD_COUNT_BANDS.items():
    if live['methodCountBands'][band] != expected_count:
        errors.append(
            f"T51 method-count budget {band} changed {expected_count} -> "
            f"{live['methodCountBands'][band]}")

allowed = {line.strip() for line in ALLOWLIST.read_text(encoding='utf-8').splitlines()
           if line.strip() and not line.lstrip().startswith('#')}
baseline_api = set(baseline['publicApiSignatures'])
live_api = set(live['publicApiSignatures'])
api_additions = sorted(live_api - baseline_api)
unapproved = [item for item in api_additions if item not in allowed]
if unapproved:
    errors.append('unapproved public API additions: ' + repr(unapproved[:20]))
historical_api = set(metrics(ROOT, HISTORICAL_BASELINE)['publicApiSignatures'])
historical_additions = baseline_api - historical_api
unknown_allowlist = sorted(allowed - (set(api_additions) | historical_additions))
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
        't51ComplexityBudget': T51_COMPLEXITY_BANDS,
        't51MethodCountBudget': T51_METHOD_COUNT_BANDS,
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
