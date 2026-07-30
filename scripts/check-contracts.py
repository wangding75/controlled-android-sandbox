#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []
aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl').read_text()
required = [
    'RuntimeOperationResult executeV2(in RuntimeOperationRequest request);',
    'RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);',
    'Bundle runtimeStatus();',
    'PackageServiceResult requestRuntimePermission(String sessionId, long generation,',
    'PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,',
]
for signature in required:
    if signature not in aidl:
        errors.append(f'IRuntimeBroker is missing {signature}')
if re.search(r'Bundle\s+runtimeStatusV2\s*\(', aidl):
    errors.append('runtimeStatusV2 must not use Bundle')
if re.search(r'Bundle\s+executeV2\s*\(', aidl):
    errors.append('executeV2 must not return Bundle')

guest_aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl').read_text()
if 'RuntimeOperationResult executeV2(in RuntimeOperationRequest request);' not in guest_aidl:
    errors.append('IGuestProcess is missing typed executeV2')
if re.search(r'Bundle\s+executeV2\s*\(', guest_aidl):
    errors.append('IGuestProcess executeV2 must not return Bundle')

for name in ['RuntimeOperationRequest', 'RuntimeOperationResult']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    else:
        value = source.read_text()
        required_fields = ['protocolVersion', 'requestId', 'operation']
        if name == 'RuntimeOperationRequest':
            required_fields += ['packageName', 'virtualUserId', 'sessionId', 'generation']
        else:
            required_fields += ['success', 'status', 'SandboxError']
        for token in required_fields:
            if token not in value: errors.append(f'{name} is missing typed transport field {token}')

operation_request = (ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/RuntimeOperationRequest.java').read_text()
for token in ['Bundle payload', 'return new Bundle(payload)', 'OPERATIONS']:
    if token not in operation_request:
        errors.append(f'RuntimeOperationRequest missing bounded legacy payload evidence: {token}')

for name in ['RuntimeStatusRequest', 'RuntimeStatusResult', 'RuntimeStatusSnapshot', 'SandboxError']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    elif 'android.os.Bundle' in source.read_text(): errors.append(f'{name} must not depend on Bundle')


isolated_aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IIsolatedGuestProcess.aidl').read_text()
for signature in [
    'IsolatedProcessResult prepare(in IsolatedProcessRequest request);',
    'IsolatedProcessResult invoke(in IsolatedProcessRequest request);',
    'IsolatedProcessResult status(in IsolatedProcessRequest request);',
    'void shutdown(String sessionId, long generation, String capabilityToken);',
]:
    if signature not in isolated_aidl:
        errors.append(f'IIsolatedGuestProcess is missing {signature}')
for name in ['IsolatedProcessRequest', 'IsolatedProcessResult']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing isolated parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing isolated Parcelable implementation for {name}')
    else:
        value = source.read_text()
        for token in ['sessionId', 'generation', 'processSlot', 'processName', 'componentClass']:
            if token not in value: errors.append(f'{name} is missing typed identity field {token}')
# Bundle remains only as the bounded legacy component payload; route identity and capability are top-level.
request_source = (ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/IsolatedProcessRequest.java').read_text()
for token in ['String capabilityToken', 'Bundle payload', 'return new Bundle(payload)']:
    if token not in request_source: errors.append(f'IsolatedProcessRequest missing guarded legacy payload evidence: {token}')


package_aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl').read_text()
package_signatures = [
    'PackageServiceResult getVirtualPackageState(String packageName, int virtualUserId);',
    'PackageServiceResult setPermissionDecision(String packageName, int virtualUserId, String permission, String decision);',
    'PackageServiceResult setAppOpMode(String packageName, int virtualUserId, String opName, String mode);',
    'PackageServiceResult resetVirtualPolicy(String packageName, int virtualUserId);',
    'PackageServiceResult resolveRuntimePermission(long requestId, String outcome, String reason);',
    'PackageServiceResult revokeRuntimePermission(String packageName, int virtualUserId, String permission, String reason);',
    'PackageServiceResult listPendingPermissionRequests(String packageName, int virtualUserId);',
    'PackageServiceResult listPermissionAudit(String packageName, int virtualUserId, int limit);',
]
for signature in package_signatures:
    if signature not in package_aidl:
        errors.append(f'IPackageManagementSession is missing {signature}')
if 'Bundle' in package_aidl:
    errors.append('IPackageManagementSession must not use Bundle')

for name in ['VirtualComponentSnapshot', 'VirtualPermissionSnapshot',
             'PackageAppOpSnapshot', 'VirtualPackageStateSnapshot',
             'RuntimePermissionRequestSnapshot', 'PermissionAuditSnapshot']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    elif 'android.os.Bundle' in source.read_text(): errors.append(f'{name} must not depend on Bundle')

package_root_aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl').read_text()
virtual_service_signatures = [
    'IVirtualSystemServiceSession openVirtualSystemServiceSession(',
    'boolean startVirtualJob(',
    'boolean stopVirtualJob(',
]
for signature in virtual_service_signatures:
    if signature not in package_root_aidl:
        errors.append(f'IPackageService is missing {signature}')
virtual_service_aidl = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl').read_text()
for signature in [
    'byte[] getClipboard();',
    'List<VirtualAccountSnapshot> listAccounts(String type);',
    'VirtualPendingIntentSnapshot reservePendingIntent(',
    'List<VirtualPendingIntentSnapshot> listPendingIntents();',
    'void scheduleAlarm(in VirtualAlarmSnapshot candidate);',
    'List<VirtualAlarmSnapshot> listAlarms();',
    'int ensureNamespace(String namespace, int guestId);',
    'VirtualNotificationSnapshot reserveNotification(in VirtualNotificationSnapshot candidate);',
    'VirtualJobSnapshot reserveJob(in VirtualJobSnapshot candidate);',
]:
    if signature not in virtual_service_aidl:
        errors.append(f'IVirtualSystemServiceSession is missing {signature}')
if 'Bundle' in virtual_service_aidl or 'Bundle' in package_root_aidl:
    errors.append('virtual system-service contracts must not use Bundle')
for name in ['VirtualAccountSnapshot', 'VirtualAlarmSnapshot', 'VirtualPendingIntentSnapshot', 'VirtualNotificationSnapshot',
             'VirtualNotificationChannelSnapshot', 'VirtualJobSnapshot', 'VirtualJobParametersSnapshot']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    elif 'android.os.Bundle' in source.read_text(): errors.append(f'{name} must not depend on Bundle')


for name in ['IHostJobCallback', 'IVirtualJobExecution']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    if not declaration.is_file(): errors.append(f'missing typed Job execution AIDL for {name}')
job_observer = (ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl').read_text()
for signature in ['boolean onJobStart(', 'boolean onJobStop(']:
    if signature not in job_observer: errors.append(f'Job observer is missing {signature}')
if 'onJobReady' in job_observer: errors.append('obsolete onJobReady acknowledgement remains')

package_result = (ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageServiceResult.java').read_text()
for evidence in ['VirtualPackageStateSnapshot packageState', 'successPackageState(', 'packageState()',
                 'RuntimePermissionRequestSnapshot permissionRequest', 'successPermissionRequest(',
                 'successPermissionAudit(']:
    if evidence not in package_result:
        errors.append(f'PackageServiceResult is missing typed package-state evidence: {evidence}')

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


broker_adapter = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationAdapter.java').read_text()
for evidence in ['broker.prepareGuest(payload)', 'broker.launchActivity(payload)', 'RuntimeOperationTransport.fromLegacy']:
    if evidence not in broker_adapter:
        errors.append(f'RuntimeBrokerOperationAdapter missing evidence: {evidence}')

transport = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java').read_text()
for evidence in ['executeV2(request)', 'requestId()', 'operation()', 'RUNTIME_OPERATION_CORRELATION_MISMATCH']:
    if evidence not in transport:
        errors.append(f'RuntimeOperationTransport missing evidence: {evidence}')
for rel in [
    'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
    'app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/RouteBrokerClient.java',
]:
    value = (ROOT / rel).read_text()
    if 'RuntimeOperationTransport' not in value:
        errors.append(f'{rel} must use RuntimeOperationTransport')
legacy_pattern = re.compile(r'\b(?:broker|guest|requireBroker\(\))\.(?:prepareGuest|launchActivity|invokeComponent|grantUriPermission|revokeUriPermission|consumeRoute|activityEvent|sessionStatus)\s*\(')
for root in [ROOT / 'app/src/main/java', ROOT / 'sandbox-runtime/src/main/java']:
    for source in root.rglob('*.java'):
        if source.name in {'RuntimeBrokerService.java', 'BaseGuestProcessService.java', 'RuntimeBrokerOperationAdapter.java'}:
            continue
        if legacy_pattern.search(source.read_text()):
            errors.append(f'internal runtime caller still uses legacy Bundle path: {source.relative_to(ROOT)}')

if errors:
    print('FAIL typed contract checks', file=sys.stderr)
    for error in errors: print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS typed contract checks')
