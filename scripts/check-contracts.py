#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []


def read_text_utf8(path: Path) -> str:
    """Do not inherit the Windows code page when checking repository source."""
    return path.read_text(encoding='utf-8-sig')


aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl')
required = [
    'RuntimeOperationResult executeV2(in RuntimeOperationRequest request);',
    'RuntimeStatusResult runtimeStatusV2(in RuntimeStatusRequest request);',
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

guest_aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl')
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
        value = read_text_utf8(source)
        required_fields = ['protocolVersion', 'requestId', 'operation']
        if name == 'RuntimeOperationRequest':
            required_fields += ['packageName', 'virtualUserId', 'sessionId', 'generation']
        else:
            required_fields += ['success', 'status', 'SandboxError']
        for token in required_fields:
            if token not in value: errors.append(f'{name} is missing typed transport field {token}')

operation_request = read_text_utf8(ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/RuntimeOperationRequest.java')
# The legacy component payload remains bounded inside a typed top-level envelope.  The
# class-loader-safe copy is intentionally `new Bundle(source)`, not an unchecked direct return.
for token in ['Bundle payload', 'new Bundle(source)', 'copyPayload(payload)', 'OPERATIONS']:
    if token not in operation_request:
        errors.append(f'RuntimeOperationRequest missing bounded legacy payload evidence: {token}')

for name in ['RuntimeStatusRequest', 'RuntimeStatusResult', 'RuntimeStatusSnapshot', 'SandboxError']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = ROOT / f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    if not declaration.is_file(): errors.append(f'missing AIDL parcelable declaration for {name}')
    if not source.is_file(): errors.append(f'missing Java Parcelable implementation for {name}')
    elif 'android.os.Bundle' in read_text_utf8(source): errors.append(f'{name} must not depend on Bundle')


isolated_aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IIsolatedGuestProcess.aidl')
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
        value = read_text_utf8(source)
        for token in ['sessionId', 'generation', 'processSlot', 'processName', 'componentClass']:
            if token not in value: errors.append(f'{name} is missing typed identity field {token}')
# Bundle remains only as the bounded legacy component payload; route identity and capability are top-level.
request_source = read_text_utf8(ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/IsolatedProcessRequest.java')
for token in ['String capabilityToken', 'Bundle payload', 'return new Bundle(payload)']:
    if token not in request_source: errors.append(f'IsolatedProcessRequest missing guarded legacy payload evidence: {token}')


package_aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl')
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
    elif 'android.os.Bundle' in read_text_utf8(source): errors.append(f'{name} must not depend on Bundle')

package_root_aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl')
virtual_service_signatures = [
    'IVirtualSystemServiceSession openVirtualSystemServiceSession(',
    'boolean startVirtualJob(',
    'boolean stopVirtualJob(',
]
for signature in virtual_service_signatures:
    if signature not in package_root_aidl:
        errors.append(f'IPackageService is missing {signature}')
virtual_service_aidl = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl')
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
    elif 'android.os.Bundle' in read_text_utf8(source): errors.append(f'{name} must not depend on Bundle')


for name in ['IHostJobCallback', 'IVirtualJobExecution']:
    declaration = ROOT / f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    if not declaration.is_file(): errors.append(f'missing typed Job execution AIDL for {name}')
job_observer = read_text_utf8(ROOT / 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl')
for signature in ['boolean onJobStart(', 'boolean onJobStop(']:
    if signature not in job_observer: errors.append(f'Job observer is missing {signature}')
if 'onJobReady' in job_observer: errors.append('obsolete onJobReady acknowledgement remains')

package_result = read_text_utf8(ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageServiceResult.java')
for evidence in ['VirtualPackageStateSnapshot packageState', 'successPackageState(', 'packageState()',
                 'RuntimePermissionRequestSnapshot permissionRequest', 'successPermissionRequest(',
                 'successPermissionAudit(']:
    if evidence not in package_result:
        errors.append(f'PackageServiceResult is missing typed package-state evidence: {evidence}')

client = read_text_utf8(ROOT / 'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java')
if '.runtimeStatusV2(' not in client:
    errors.append('RuntimeClient must use typed runtimeStatusV2')
if 'requireBroker().runtimeStatus()' in client:
    errors.append('RuntimeClient must not use legacy runtimeStatus Bundle path')

service = read_text_utf8(ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java')
dispatcher = read_text_utf8(ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/RuntimeStatusDispatcher.java')
if re.search(r'Bundle\s+runtimeStatus\s*\(', aidl):
    errors.append('legacy runtimeStatus Bundle endpoint must not return after typed-only migration')
if 'RuntimeStatusResult.success(' not in dispatcher:
    errors.append('runtime status dispatcher must build a typed RuntimeStatusResult')
if 'runtimeStatusDispatcher.dispatch(request)' not in service:
    errors.append('broker Binder must delegate typed status to RuntimeStatusDispatcher')


broker_adapter = read_text_utf8(ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerOperationAdapter.java')
for evidence in ['broker.prepareGuest(payload)', 'broker.launchActivity(payload)', 'RuntimeOperationTransport.fromLegacy']:
    if evidence not in broker_adapter:
        errors.append(f'RuntimeBrokerOperationAdapter missing evidence: {evidence}')

transport = read_text_utf8(ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java')
for evidence in ['executeV2(request)', 'requestId()', 'operation()', 'RUNTIME_OPERATION_CORRELATION_MISMATCH']:
    if evidence not in transport:
        errors.append(f'RuntimeOperationTransport missing evidence: {evidence}')
for rel in [
    'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
    'app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/RouteBrokerClient.java',
]:
    value = read_text_utf8(ROOT / rel)
    if 'RuntimeOperationTransport' not in value:
        errors.append(f'{rel} must use RuntimeOperationTransport')
legacy_pattern = re.compile(r'\b(?:broker|guest|requireBroker\(\))\.(?:prepareGuest|launchActivity|invokeComponent|grantUriPermission|revokeUriPermission|consumeRoute|activityEvent|sessionStatus)\s*\(')
for root in [ROOT / 'app/src/main/java', ROOT / 'sandbox-runtime/src/main/java']:
    for source in root.rglob('*.java'):
        if source.name in {'RuntimeBrokerService.java', 'BaseGuestProcessService.java', 'RuntimeBrokerOperationAdapter.java'}:
            continue
        if legacy_pattern.search(read_text_utf8(source)):
            errors.append(f'internal runtime caller still uses legacy Bundle path: {source.relative_to(ROOT)}')

if errors:
    print('FAIL typed contract checks', file=sys.stderr)
    for error in errors: print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS typed contract checks')
