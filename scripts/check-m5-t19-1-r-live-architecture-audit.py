#!/usr/bin/env python3
from __future__ import annotations
import json, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from tools.architecture_metrics import commit_large_classes, live_large_classes
ROOT=Path(__file__).resolve().parents[1]
BASELINE='2071974236f55d3a94aac40bb70d834cea590218'
errors=[]
# Generate from the current source on every run; no pre-generated current JSON is trusted.
result=subprocess.run([sys.executable,'scripts/audit-m5-t19-architecture-decoupling.py'],cwd=ROOT,text=True,capture_output=True)
if result.returncode:
    errors.append('live architecture audit failed: '+result.stderr.strip())
    report={}
else:
    try: report=json.loads(result.stdout)
    except Exception as exc: errors.append(f'live architecture audit is invalid JSON: {exc}'); report={}
live=live_large_classes(ROOT)
baseline=commit_large_classes(ROOT,BASELINE)
architecture=report.get('architecture',{})
if architecture.get('largeProductionClassesOver500') != len(live): errors.append('live large-class count does not match source scan')
if architecture.get('largeClasses') != live: errors.append('live large-class list does not match source scan')
if architecture.get('m5T19BaselineLargeProductionClassesOver500') != 13: errors.append('corrected M5-T19 baseline count must be 13')
if architecture.get('m5T19BaselineLargeClasses') != baseline: errors.append('baseline list does not match Git tree scan')
if not any(x['path'].endswith('ManifestReceiverRegistry.java') for x in baseline): errors.append('baseline omits ManifestReceiverRegistry.java')
out=ROOT/'build/verification/m5-t19-1-r-live-architecture-audit.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
if errors:
    print('FAIL M5-T19.1-R live architecture audit',file=sys.stderr)
    for e in errors: print(' - '+e,file=sys.stderr)
    raise SystemExit(1)
print(f'PASS M5-T19.1-R live architecture audit (baseline=13 current={len(live)})')
