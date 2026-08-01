#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
mkdir -p build/verification
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --binary HEAD > build/verification/verify-all-before.patch
fi

./scripts/self-test.sh
python3 scripts/check-build-environment.py
python3 tools/baseline_manifest.py
python3 tools/reference_sources.py verify
./scripts/check-wrapper-bootstrap.sh
./scripts/test-wrapper-bootstrap.sh
python3 scripts/check-architecture.py
python3 scripts/check-contracts.py
python3 scripts/check-m4-t18-source-closure.py
python3 scripts/check-m4-t18-ownership-cleanup.py
python3 scripts/check-m4-t18-final-freeze.py
python3 scripts/check-m5-build-baseline.py
python3 scripts/check-m5-t2-cross-width-runtime.py
python3 scripts/check-m5-t3-broadcast-fgs.py
python3 scripts/check-m5-t4-native-diagnostics.py
python3 scripts/check-m5-t5-device-lab.py
python3 scripts/check-m5-t6-isolated-process.py
python3 scripts/check-m5-t7-package-surface.py
python3 scripts/check-m5-t8-device-services.py
python3 scripts/check-m5-t9-interaction-services.py
python3 scripts/check-m5-t10-network-services.py
python3 scripts/check-m5-t11-application-environment.py
python3 scripts/check-m5-t12-webview-gms-oem-detection.py
python3 scripts/check-m5-t13-policy-services.py
python3 scripts/check-m5-t14-media-communication.py
python3 scripts/check-m5-t15-peripheral-services.py
python3 scripts/check-m5-t16-source-closure.py
python3 scripts/check-m5-t17-privileged-services.py
python3 scripts/check-m5-t18-architecture-quality.py
python3 tools/static_android_compile.py
python3 scripts/check-critical-test-ownership.py --self-test
python3 scripts/check-critical-test-ownership.py
python3 scripts/check-m5-t19-architecture-decoupling.py
python3 scripts/check-m5-t19-1-a-native-trust-boundary.py
python3 scripts/check-m5-t19-1-b-native-network-correctness.py
python3 scripts/check-m5-t19-1-c-rebind-timeout-recovery.py
python3 scripts/check-m5-t19-1-d-binder-death-registration.py
python3 scripts/check-m5-t19-1-e-aidl-pagination.py
python3 scripts/check-m5-t19-1-f-critical-test-ownership.py
python3 scripts/check-m5-t19-1-g-store-commit-consistency.py
python3 scripts/check-m5-t19-1-h-guest-pool-reconnect.py
python3 scripts/check-m5-t19-1-i-guest-storage-name-codec.py
python3 scripts/check-m5-t19-1-j-review-remediation.py
./scripts/test-m5-artifact-verifier.sh
./scripts/test-m5-device-lab.sh
python3 scripts/check-ports-dispatchers.py
python3 scripts/check-package-boundaries.py
python3 scripts/check-guest-boundary.py
python3 scripts/check-apk-revision-binding.py
python3 scripts/check-package-lifecycle-transaction.py
python3 scripts/check-package-service-boundary.py
python3 scripts/check-virtual-package-state.py
python3 scripts/check-package-query-resolve.py
python3 scripts/check-runtime-permission-workflow.py
python3 scripts/check-capability-proxy-broker-split.py
python3 scripts/check-system-services-broker-split.py
python3 scripts/check-binder-system-services.py
python3 scripts/check-pending-intent-lifecycle.py
python3 scripts/check-alarm-notification-lifecycle.py
python3 scripts/check-notification-job-lifecycle.py
python3 scripts/check-job-scheduler-policy.py
python3 scripts/check-guest-jobservice-bridge.py
python3 scripts/check-service-lifecycle.py
python3 scripts/check-activity-task-virtualization.py
python3 scripts/check-split-install-sessions.py
python3 scripts/check-broadcast-model.py
python3 scripts/check-native-file-hooks.py
python3 scripts/check-native-files-loader.py
python3 scripts/check-native-network-audio.py
python3 scripts/check-native-abi-companion.py
python3 scripts/generate-sbom.py
python3 scripts/check-m3-source-progress.py
./scripts/test-native.sh
./scripts/test-m3-gate.sh
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  ./scripts/test-reproducible-source-package.sh
else
  echo 'SKIP reproducible source package comparison: Git metadata unavailable in source archive'
fi
bash -n scripts/*.sh
PYTHONPYCACHEPREFIX="$ROOT/build/pycache" python3 -m py_compile scripts/*.py tools/*.py

python3 - <<'PY'
from pathlib import Path
for p in Path('scripts').glob('*.ps1'):
    text=p.read_text()
    pairs={'{':'}','(':')','[':']'}
    stack=[]; quote=None
    for i,ch in enumerate(text):
        if quote:
            if ch==quote and (i==0 or text[i-1] != '`'): quote=None
            continue
        if ch in "'\"": quote=ch; continue
        if ch in pairs: stack.append((ch,i))
        elif ch in pairs.values():
            if not stack or pairs[stack[-1][0]] != ch: raise SystemExit(f'{p}: unmatched {ch} at {i}')
            stack.pop()
    if quote: raise SystemExit(f'{p}: unclosed quote')
    if stack: raise SystemExit(f'{p}: unclosed delimiter')
    print('PASS structural PowerShell check', p)
PY

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --binary HEAD > build/verification/verify-all-after.patch
  cmp build/verification/verify-all-before.patch build/verification/verify-all-after.patch
fi

echo 'PASS all locally executable verification gates'
