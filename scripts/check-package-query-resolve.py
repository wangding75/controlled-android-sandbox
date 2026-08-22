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


aidl_path = 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl'
aidl = require(aidl_path, 'setPackageEnabledSetting(String packageName, int virtualUserId, String state)',
               'setComponentEnabledSetting(String packageName, int virtualUserId, String className, String state)')
if 'Bundle' in aidl:
    errors.append(f'{aidl_path} must remain typed and must not use Bundle')

for name in ('VirtualIntentDataSnapshot', 'VirtualIntentFilterSnapshot'):
    text(f'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl')
    source = require(
        f'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java',
        'implements Parcelable', 'CREATOR')
    if 'android.os.Bundle' in source:
        errors.append(f'{name} must not depend on Bundle')

require(
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualComponentSnapshot.java',
    'String enabledSetting', 'List<VirtualIntentFilterSnapshot> intentFilters',
    'enabledSetting()', 'intentFilters()')
require(
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualPackageStateSnapshot.java',
    'long firstInstallTime', 'long lastUpdateTime', 'String installerPackageName',
    'firstInstallTime()', 'lastUpdateTime()', 'installerPackageName()')

require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogRepository.java',
    'SCHEMA_VERSION = 5',
    'version != 1 && version != 2 && version != 3 && version != 4 && version != SCHEMA_VERSION')
require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxPolicyState.java',
    'COMPONENT_DEFAULT', 'COMPONENT_ENABLED', 'COMPONENT_DISABLED',
    'withPackageState(', 'packageState()', 'withComponentState(', 'componentState(', '"packageState"', '"components"')
require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxRecord.java',
    'firstInstallAt', 'lastUpdateAt', 'withInstallTimes(')
require(
    'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java',
    'withPackageState(', 'withComponentState(', 'previous.firstInstallAt',
    'long lastUpdateAt = Math.max(firstInstallAt, nowMs)',
    'withInstallTimes(firstInstallAt, lastUpdateAt)')
require(
    'app/src/main/java/com/warden/controlledsandbox/VirtualPackageStateBuilder.java',
    'VirtualIntentDataSnapshot', 'VirtualIntentFilterSnapshot',
    'effectiveComponentEnabled(', '"com.warden.virtualinstaller"', 'record.firstInstallAt', 'record.lastUpdateAt')
require(
    'app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java',
    'setPackageEnabledSetting', 'setComponentEnabledSetting', 'declaresComponent(', 'lifecycle.setComponentState(')
require(
    'app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java',
    'setPackageEnabledSetting(', 'requireSession().setPackageEnabledSetting(',
    'setComponentEnabledSetting(', 'requireSession().setComponentEnabledSetting(')

metadata = require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualPackageMetadata.java',
    'MATCH_DISABLED_COMPONENTS', 'MATCH_DEFAULT_ONLY',
    'query(Intent intent, Type type, long flags)',
    'dataMatch(', 'simpleGlob(', 'filter.priority()',
    'firstInstallTime', 'lastUpdateTime', 'installerPackageName',
    'sharedLibraries', 'componentEnabledSetting(')
handler = require(
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java',
    'case "getPackageInfo"', 'case "getApplicationInfo"',
    'case "resolveActivity"', 'case "queryIntentActivities"',
    'case "queryIntentServices"', 'case "queryBroadcastReceivers"',
    'case "resolveContentProvider"', 'case "queryContentProviders"',
    'case "getInstallerPackageName"', 'case "getInstallSourceInfo"',
    'case "getComponentEnabledSetting"', 'case "setApplicationEnabledSetting"',
    'case "setComponentEnabledSetting"', 'HiddenPackageResultMapper.map(')
if 'method.invoke(delegate' not in handler:
    errors.append('PackageManager handler must retain an explicit host/system fallback path')

require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageMetadataMapper.java',
    'VirtualIntentFilterSnapshot', 'VirtualPackageMetadata.DataRule',
    'component.enabledSetting()', 'state.firstInstallTime()', 'state.lastUpdateTime()')
require(
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestManifestMetadataLoader.java',
    'VirtualPackageMetadata.Filter', 'filter.priority()', 'filter.dataRules()')

require(
    'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/packagemanager/VirtualPackageQuerySelfTest.java',
    'both exact and generic filters match', 'priority and specificity order is deterministic',
    'MATCH_DEFAULT_ONLY is enforced', 'disabled component hidden by default',
    'disabled package is hidden by default', 'shared library metadata is exposed')
require(
    'app/src/testHarness/java/com/warden/controlledsandbox/SandboxCatalogStateSelfTest.java',
    'first install timestamp survives upgrade', 'package enabled override persisted', 'component enabled override persisted',
    'policy reset removes overrides')
require(
    'app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java',
    'VirtualIntentFilterSnapshot', 'install source lost')
require('tools/static_android_compile.py', 'VirtualPackageQuerySelfTest')

if errors:
    print('FAIL virtual PackageManager query and Intent resolve checks', file=sys.stderr)
    for error in errors:
        print(f'- {error}', file=sys.stderr)
    raise SystemExit(1)
print('PASS virtual PackageManager query and Intent resolve checks')
