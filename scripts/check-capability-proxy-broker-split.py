#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f'missing required file: {relative}')
        return ''
    return path.read_text(encoding='utf-8')

def require(relative: str, *needles: str) -> str:
    content = text(relative)
    for needle in needles:
        if needle not in content:
            errors.append(f'{relative} is missing required evidence: {needle}')
    return content

for name, marker in (('CapabilityAccessPolicy', 'class CapabilityAccessPolicy'),
                     ('CapabilityAuditEvent', 'record CapabilityAuditEvent'),
                     ('CapabilityAuditSink', 'interface CapabilityAuditSink'),
                     ('CapabilityLeaseRegistry', 'class CapabilityLeaseRegistry')):
    require(f'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/capability/{name}.java', marker)
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/CapabilityServiceInterceptor.java',
        'class CapabilityServiceInterceptor')

require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java',
        'CameraServiceHook.install(hostServiceContext, identity)',
        'LocationServiceHook.install(hostServiceContext, identity)',
        'AudioCaptureServiceHook.install(hostServiceContext, identity)')
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java',
        'CapabilityServiceInterceptor', 'matchesGuest(', 'SandboxAppOpsPolicy.operationName(')
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/IdentityObjectRewriter.java',
        'fieldName.equals("mNext")', 'fieldName.equals("next")')
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/SandboxAppOpsPolicy.java',
        'case 26 -> "android:camera"', 'case 27 -> "android:record_audio"')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/capability/CapabilityProxyReadiness.java',
        'CAPABILITY_PROXY_UNAVAILABLE', 'audioCapture', 'location')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/capability/GuestCapabilityAuditLog.java',
        'DEFAULT_LIMIT = 128', 'compactSnapshot()', 'deniedCount()')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
        'CapabilityProxyReadiness.require(', 'capabilityLeases.revokeDenied(',
        'capabilityActiveLeases', 'capabilityLeases.close(capabilityAudit)')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimePermissionCoordinator.java',
        'class RuntimePermissionCoordinator', 'PermissionSession', 'RUNTIME_PERMISSION_SESSION_NOT_READY')
broker = require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
                 'RuntimePermissionCoordinator runtimePermissionCoordinator',
                 'runtimePermissionCoordinator.request(', 'runtimePermissionCoordinator.report(')
if 'RuntimePermissionPackageClient runtimePermissionPackages' in broker:
    errors.append('RuntimeBrokerService must not directly own the old permission package client field')

require('sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/CapabilityServiceProxySelfTest.java',
        'camera denied before host delegate', 'location listener actively removed on revoke',
        'camera device closed after AppOps revocation')
require('sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityProxySelfTest.java',
        'attribution chain rewrite', 'proxy AppOps attribution chain targets Guest policy',
        'unknown integer AppOps code fails closed')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/capability/CapabilityProxyReadinessSelfTest.java',
        'effective grant fails closed without proxy')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/capability/GuestCapabilityAuditLogSelfTest.java',
        'audit is bounded')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimePermissionCoordinatorSelfTest.java',
        'ready session request delegated')
require('tools/static_android_compile.py', 'CapabilityServiceProxySelfTest',
        'CapabilityProxyReadinessSelfTest', 'RuntimePermissionCoordinatorSelfTest')

if errors:
    print('FAIL capability proxy and Runtime Broker split checks', file=sys.stderr)
    for error in errors: print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS capability proxy and Runtime Broker split checks')
