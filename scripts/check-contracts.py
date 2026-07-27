#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []
aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl').read_text()
required = [
    'RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);',
    'Bundle runtimeStatus();',
]
for signature in required:
    if signature not in aidl:
        errors.append(f'IRuntimeBroker is missing {signature}')
if re.search(r'Bundle\s+runtimeStatusV2\s*\(', aidl):
    errors.append('runtimeStatusV2 must not use Bundle')

for name in ['RuntimeStatusRequest', 'RuntimeStatusResult', 'RuntimeStatusSnapshot', 'SandboxError']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    elif 'android.os.Bundle' in source.read_text(): errors.append(f'{name} must not depend on Bundle')

client = (ROOT / 'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java').read_text()
if '.runtimeStatusV2(' not in client:
    errors.append('RuntimeClient must use typed runtimeStatusV2')
if 'requireBroker().runtimeStatus()' in client:
    errors.append('RuntimeClient must not use legacy runtimeStatus Bundle path')

service = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java').read_text()
dispatcher = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/RuntimeStatusDispatcher.java').read_text()
if 'RuntimeStatusLegacyAdapter.toBundle' not in service:
    errors.append('legacy runtimeStatus must be isolated behind RuntimeStatusLegacyAdapter')
if 'RuntimeStatusResult.success(' not in dispatcher:
    errors.append('runtime status dispatcher must build a typed RuntimeStatusResult')
if 'runtimeStatusDispatcher.dispatch(request)' not in service:
    errors.append('broker Binder must delegate typed status to RuntimeStatusDispatcher')

if errors:
    print('FAIL typed contract checks', file=sys.stderr)
    for error in errors: print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS typed contract checks')
