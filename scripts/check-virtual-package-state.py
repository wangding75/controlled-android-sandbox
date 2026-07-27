#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []


def text(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        errors.append(f'missing required file: {path}')
        return ''
    return target.read_text()


def require(path: str, *needles: str) -> str:
    content = text(path)
    for needle in needles:
        if needle not in content:
            errors.append(f'{path} is missing required evidence: {needle}')
    return content


aidl_path = 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl'
aidl = require(
    aidl_path,
    'getVirtualPackageState(String packageName, int virtualUserId)',
    'setPermissionDecision(String packageName, int virtualUserId, String permission, String decision)',
    'setAppOpMode(String packageName, int virtualUserId, String opName, String mode)',
    'resetVirtualPolicy(String packageName, int virtualUserId)',
)
if 'Bundle' in aidl:
    errors.append(f'{aidl_path} must remain typed and must not use Bundle')

for name in (
    'VirtualComponentSnapshot',
    'VirtualPermissionSnapshot',
    'PackageAppOpSnapshot',
    'VirtualPackageStateSnapshot',
):
    declaration = f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl'
    source = f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java'
    text(declaration)
    java = text(source)
    if 'implements Parcelable' not in java:
        errors.append(f'{source} must implement Parcelable')
    if 'android.os.Bundle' in java:
        errors.append(f'{source} must not depend on Bundle')

require(
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageServiceResult.java',
    'VirtualPackageStateSnapshot packageState',
    'successPackageState(',
    'packageState()',
)

repository = require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogRepository.java',
    'SCHEMA_VERSION = 4',
    'root.put("policies", policies)',
    'version != 1 && version != 2 && version != 3 && version != SCHEMA_VERSION',
    'new SandboxCatalogState(packages, instances, policies,',
)
state = require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java',
    'List<SandboxPolicyState> policies',
    'withPermissionDecision(',
    'withAppOpMode(',
    'withoutPolicy(',
)
if 'nextPolicies.removeIf' not in state:
    errors.append('SandboxCatalogState must atomically remove instance policy during deletion')

require(
    'app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java',
    'VirtualPackageStateBuilder',
    'lifecycle.packagePolicy(',
    'lifecycle.setPermissionDecision(',
    'lifecycle.setAppOpMode(',
    'lifecycle.resetPolicy(',
)
require(
    'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
    'packageService.virtualPackageState(',
    'RuntimeKeys.PACKAGE_STATE',
    'PACKAGE_STATE_REVISION_MISMATCH',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java',
    'virtual package state is required',
    'VIRTUAL_PACKAGE_STATE_IDENTITY_MISMATCH',
    'VIRTUAL_PACKAGE_STATE_REVISION_MISMATCH',
    'RuntimeKeys.PACKAGE_STATE',
)
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
    'GuestPackageMetadataMapper.fromSnapshot(',
    'permissionPolicy(spec.packageState)',
    'appOpsPolicy(spec.packageState)',
)
require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java',
    'identity.permissionPolicy().isGranted(permission)',
    'HOST_PACKAGE_HIDDEN',
)
require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ReflectiveServiceHook.java',
    'new SystemServiceInvocationHandler(original, identity, serviceName)',
)
handler = require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java',
    '"permission"',
    '"appops"',
    'identity.permissionPolicy()',
    'identity.appOpsPolicy()',
)

require(
    'app/src/testHarness/java/com/warden/controlledsandbox/SandboxCatalogStateSelfTest.java',
    'instance deletion removes policy atomically',
)
require(
    'app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java',
    'VirtualPackageStateSnapshot',
    'PackageAppOpSnapshot',
)
require(
    'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityProxySelfTest.java',
    'HOST_PACKAGE_HIDDEN',
    'SandboxAppOpsPolicy',
)
compile_tool = require(
    'tools/static_android_compile.py',
    'SandboxCatalogStateSelfTest',
    'PackageServiceContractSelfTest',
    'FrameworkIdentityProxySelfTest',
)

if errors:
    print('FAIL virtual package state checks', file=sys.stderr)
    for error in errors:
        print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS virtual package state checks')
