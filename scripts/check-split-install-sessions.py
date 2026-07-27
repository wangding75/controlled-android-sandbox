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
    return target.read_text(encoding='utf-8')

def require(path: str, *needles: str) -> str:
    content = text(path)
    for needle in needles:
        if needle not in content:
            errors.append(f'{path} is missing required evidence: {needle}')
    return content

aidl_path = 'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl'
aidl = require(aidl_path,
    'createInstallSession(String expectedPackageName)',
    'addInstallArtifact(int sessionId, String sourceUri)',
    'commitInstallSession(int sessionId)',
    'abandonInstallSession(int sessionId)')
if 'Bundle' in aidl:
    errors.append(f'{aidl_path} must remain typed and must not use Bundle')

require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageArtifactSnapshot.java',
        'implements Parcelable', 'splitName', 'configForSplit', 'usesSplit', 'sha256')
text('sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/PackageArtifactSnapshot.aidl')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageRecordSnapshot.java',
        'List<PackageArtifactSnapshot> artifacts', 'baseApkSha256', 'sharedLibraries')

require('sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/ManifestModel.java',
        'splitName()', 'configForSplit()', 'usesSplit()', 'featureSplit()', 'sharedLibraries()')
require('sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/BinaryXmlManifestParser.java',
        'configForSplit', 'usesSplit', 'isFeatureSplit', 'uses-library')

require('app/src/main/java/com/warden/controlledsandbox/PackageArtifactOrder.java',
        'Split dependency cycle', 'Missing split dependency', 'runtimeOrder(')
require('app/src/testHarness/java/com/warden/controlledsandbox/PackageArtifactOrderSelfTest.java',
        'configuration after target', 'split dependency cycle rejected')
require('app/src/main/java/com/warden/controlledsandbox/MainActivity.java',
        'Intent.EXTRA_ALLOW_MULTIPLE', 'packageService.createInstallSession(',
        'packageService.commitInstallSession(')
require('app/src/main/java/com/warden/controlledsandbox/PackageArtifactRecord.java',
        'TYPE_BASE', 'TYPE_FEATURE', 'TYPE_CONFIG', 'sha256')
require('app/src/main/java/com/warden/controlledsandbox/SandboxCatalogRepository.java',
        'SCHEMA_VERSION = 3', 'version != 1 && version != 2 && version != SCHEMA_VERSION')
require('app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java',
        'Package must contain exactly one base artifact', 'Duplicate split metadata')
require('app/src/main/java/com/warden/controlledsandbox/PackageInstallSessionStore.java',
        'OPEN', 'SEALED', 'seal(', 'reopenAfterFailure(', 'sweepStale(', 'MAX_ARTIFACT_BYTES')
require('app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java',
        'createInstallSession(', 'addInstallArtifact(', 'commitInstallSession(',
        'abandonInstallSession(', 'importApkFiles(')
require('app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java',
        'importApkFiles(', 'Install set does not contain a base APK', 'Duplicate split name',
        'APK artifacts belong to different packages', 'APK artifacts have different signing certificates',
        'revisionDigestRecords(')
require('app/src/main/java/com/warden/controlledsandbox/PackageStorageLayout.java',
        'splitApkFile(', 'requireRecordLayout(')

require('app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
        'RuntimeKeys.BASE_APK_SHA256', 'RuntimeKeys.SPLIT_PATHS', 'RuntimeKeys.SPLIT_SHA256S')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
        'PackageRevisionSetVerifier.verify(', 'Split metadata arrays must have identical sizes')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java',
        'dexPath()', 'splitPathArray()', 'hasSplit(')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
        'PackageRevisionSetVerifier.verify(', 'spec.dexPath()', 'spec.splitPathArray()')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestResourceLoader.java',
        'for (String splitPath : splitPaths)', 'addAssetPath')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java',
        'splitSourceDirs', 'createContextForSplit')

require('app/src/testHarness/java/com/warden/controlledsandbox/PackageInstallSessionStoreSelfTest.java',
        'persisted staged install session self-test')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/PackageRevisionSetVerifierSelfTest.java',
        'split tampering rejected')
compiler = require('tools/static_android_compile.py',
        'PackageInstallSessionStoreSelfTest', 'PackageRevisionSetVerifierSelfTest', 'PackageArtifactOrderSelfTest')

if errors:
    print('FAIL split APK and install session checks', file=sys.stderr)
    for error in errors: print('- ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS split APK and install session checks')
