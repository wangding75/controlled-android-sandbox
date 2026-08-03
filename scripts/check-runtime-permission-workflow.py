#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing required file: {relative}")
        return ""
    return path.read_text()


def require(relative: str, *needles: str) -> str:
    content = text(relative)
    for needle in needles:
        if needle not in content:
            errors.append(f"{relative} is missing required evidence: {needle}")
    return content


runtime_aidl = require(
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimePermissionSession.aidl',
    'requestRuntimePermission(String packageName, int virtualUserId,',
    'reportRuntimePermissionResult(String packageName, int virtualUserId,',
    'void close();',
)
if 'Bundle' in runtime_aidl:
    errors.append('IRuntimePermissionSession must remain typed and must not use Bundle')

root_aidl = require(
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl',
    'IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);',
)
management_aidl = require(
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl',
    'resolveRuntimePermission(long requestId, String outcome, String reason)',
    'revokeRuntimePermission(String packageName, int virtualUserId, String permission, String reason)',
    'listPendingPermissionRequests(String packageName, int virtualUserId)',
    'listPermissionAudit(String packageName, int virtualUserId, int limit)',
)
broker_aidl = require(
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl',
    'PackageServiceResult requestRuntimePermission(String sessionId, long generation,',
    'PackageServiceResult reportRuntimePermissionResult(String sessionId, long generation,',
)
for name in ('RuntimePermissionRequestSnapshot', 'PermissionAuditSnapshot'):
    text(f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl')
    source = require(
        f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java',
        'implements Parcelable',
        'CREATOR',
    )
    if 'android.os.Bundle' in source:
        errors.append(f'{name} must not depend on Bundle')

repository = require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogRepository.java',
    'SCHEMA_VERSION = 5',
    'root.put("permissionRequests", permissionRequests)',
    'root.put("permissionAudit", permissionAudit)',
    'version != 1 && version != 2 && version != 3 && version != 4 && version != SCHEMA_VERSION',
)
state = require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java',
    'withPermissionRequest(',
    'withPermissionResolution(',
    'withPermissionRevocation(',
    'withPolicyReset(',
    'pendingPermissionRequest(',
    'pruneResolvedPermissionHistory(',
    'SUPERSEDE',
    'PACKAGE_UPDATE',
)
if 'nextRequests.removeIf(request -> request.packageName.equals(packageName)' not in state:
    errors.append('instance deletion must remove permission request history atomically')
if 'nextAudit.removeIf(audit -> audit.packageName.equals(packageName)' not in state:
    errors.append('instance deletion must remove permission audit atomically')

require(
    'app/src/main/java/com/warden/controlledsandbox/PackageCallerVerifier.java',
    'runtimeCaller()',
    'companionPackageForUid',
    'signaturePermissionGranted()',
)
require(
    'app/src/main/java/com/warden/controlledsandbox/PackageAuthorityCapabilityRegistry.java',
    'registerRuntime(',
    'requireRuntime(',
    'linkToDeath',
    'ownerPid == caller.pid',
)
service = require(
    'app/src/main/java/com/warden/controlledsandbox/PackageServiceBinder.java',
    'openRuntimePermissionSessionWithCapability',
    'clientToken.linkToDeath',
    'capabilityRegistry.requireRuntime(capability, capabilityGeneration)',
)
permission_session = require(
    'app/src/main/java/com/warden/controlledsandbox/PackageRuntimePermissionSession.java',
    'RUNTIME_PERMISSION_HOST_RESULT_MISMATCH',
    'RUNTIME_PERMISSION_PENDING_REQUEST_REQUIRED',
    'hostPermissions.resolve(',
    'capabilityRegistry.requireRuntime(authorityCapability, authorityGeneration)',
)
if 'lifecycle.requestRuntimePermission(' not in permission_session:
    errors.append('Runtime permission capability must persist a request before resolution')

require(
    'app/src/main/java/com/warden/controlledsandbox/VirtualPackageStateBuilder.java',
    'HostPermissionStateResolver',
    'host.grantedToHost',
    'runtimeRequestable',
    'latestPermissionRequestState(',
    'effectiveAppOpMode(',
)
require(
    'app/src/testHarness/java/com/warden/controlledsandbox/VirtualPackageStateBuilderPolicySelfTest.java',
    'AppOps ALLOWED must not bypass a denied effective permission',
)
require(
    'app/src/main/java/com/warden/controlledsandbox/PermissionCapabilityRegistry.java',
    'android.permission.CAMERA',
    'android.permission.RECORD_AUDIO',
    'android.permission.ACCESS_FINE_LOCATION',
    'android:camera',
    'android:record_audio',
)

require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
    'RuntimePermissionCoordinator runtimePermissionCoordinator',
    'runtimePermissionCoordinator.request(',
    'runtimePermissionCoordinator.report(',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimePermissionCoordinator.java',
    'gateway.request(',
    'gateway.report(',
    'requireSession(',
    'RUNTIME_PERMISSION_SESSION_NOT_READY',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/StubActivityBase.java',
    'RouteBrokerClient.requestPermission(',
    'RouteBrokerClient.reportPermissionResult(',
    'refreshPermissionState(',
    'controller.permissionResult(',
    'effectiveGrant(',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/GuestActivityController.java',
    'onRequestPermissionsResult',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java',
    'GuestCapabilityGate',
    'capabilityGate.requireService(name)',
    'updatePermissionState(',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
    'updatePermissionState(',
    'permissionPolicy.replace(',
    'appOpsPolicy.replace(',
    'context.updatePermissionState(',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestCapabilityGate.java',
    'GUEST_CAPABILITY_NOT_GRANTED',
    'android.permission.CAMERA',
    'android.permission.ACCESS_FINE_LOCATION',
)
require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualPermissionPolicy.java',
    'effectiveGrants',
    'isGranted(String permission)',
)

manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
declared = {item.get(android + 'name') for item in manifest.findall('uses-permission')}
for permission in (
    'android.permission.CAMERA',
    'android.permission.RECORD_AUDIO',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_FINE_LOCATION',
):
    if permission not in declared:
        errors.append(f'host manifest must declare controlled capability {permission}')

require(
    'app/src/testHarness/java/com/warden/controlledsandbox/RuntimePermissionWorkflowSelfTest.java',
    'same generation request must be idempotent',
    'stale pending request must be cancelled',
    'virtual grant must require host capability',
    'policy reset must cancel pending callbacks',
    'policy reset must audit cleared permission decisions without pending callbacks',
    'package revision change must cancel pending permission requests',
    'instance deletion must remove permission audit',
)
require(
    'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestCapabilityGateSelfTest.java',
    'camera handle must be blocked without effective grant',
    'location handle must be blocked without effective grant',
    'same-generation revocation must remove camera handle access',
)
require(
    'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/DynamicAccessPolicySelfTest.java',
    'same-generation permission replacement must become visible',
    'same-generation AppOps replacement must become visible',
)
compiler = require(
    'tools/static_android_compile.py',
    'RuntimePermissionWorkflowSelfTest',
    'VirtualPackageStateBuilderPolicySelfTest',
    'GuestCapabilityGateSelfTest',
    'DynamicAccessPolicySelfTest',
    'IRuntimePermissionSession.java',
)

if errors:
    print('FAIL runtime permission workflow checks', file=sys.stderr)
    for error in errors:
        print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS runtime permission capability and workflow checks')
