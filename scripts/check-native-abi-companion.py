#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def text(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        errors.append(f'missing {path}')
        return ''
    return p.read_text(encoding='utf-8', errors='ignore')

settings = text('settings.gradle')
if "include ':sandbox-companion32'" not in settings:
    errors.append('32-bit companion module is not included')

host_app = text('app/build.gradle')
shared_native = text('sandbox-native/build.gradle')
companion_build = text('sandbox-companion32/build.gradle')
if not re.search(r"abiFilters\s+'arm64-v8a',\s*'x86_64'", host_app):
    errors.append('Host APK must be restricted to arm64-v8a and x86_64')
if re.search(r"abiFilters[^\n]*(armeabi-v7a|(?<!_)\bx86\b)", host_app):
    errors.append('Host APK silently includes a 32-bit ABI')
for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
    if abi not in shared_native:
        errors.append(f'shared native runtime is missing {abi}')
if not re.search(r"abiFilters\s+'armeabi-v7a',\s*'x86'", companion_build):
    errors.append('Companion APK must be restricted to armeabi-v7a and x86')
if re.search(r"abiFilters[^\n]*(arm64-v8a|x86_64)", companion_build):
    errors.append('Companion APK silently includes a 64-bit ABI')
for dependency in ("project(':sandbox-contract')", "project(':sandbox-runtime')"):
    if dependency not in companion_build:
        errors.append(f'Companion is missing dependency {dependency}')

contract_paths = [
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/INativeAbiCompanion.aidl',
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/INativeCompanionArtifactService.aidl',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionRequest.java',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionResult.java',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionArtifactRequest.java',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionArtifactResult.java',
]
contract = '\n'.join(text(path) for path in contract_paths)
if 'Bundle' in contract:
    errors.append('Native companion typed contracts contain an untyped Bundle')
for token in ('sessionId', 'generation', 'virtualUserId', 'packageRevision',
              'capabilityNonce', 'requestedAbi', 'relativePath', 'sha256', 'sizeBytes'):
    if token not in contract:
        errors.append(f'Native companion contracts are missing {token}')

host_manifest = text('app/src/main/AndroidManifest.xml')
companion_manifest = text('sandbox-companion32/src/main/AndroidManifest.xml')
permission = 'com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION'
if permission not in host_manifest or 'protectionLevel="signature"' not in host_manifest:
    errors.append('Host does not declare the signature-level companion permission')
if '.PackageManagementService' not in host_manifest or 'android:exported="true"' not in host_manifest:
    errors.append('Host Package Service is not available to the signed companion broker')
for token in (permission, 'NativeCompanionArtifactService',
              'com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService',
              'android:process=":sandbox_server32"'):
    if token not in companion_manifest:
        errors.append(f'Companion manifest is missing {token}')

client = text('app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java')
runtime = text('app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java')
for token in ('INativeCompanionArtifactService', 'ParcelFileDescriptor.open',
              'prepareGuest', 'launchActivity', 'invokeComponent', 'stopGuest'):
    if token not in client:
        errors.append(f'Companion runtime client is missing {token}')
if 'NATIVE_COMPANION_CROSS_WIDTH_EXECUTION_NOT_WIRED' in runtime:
    errors.append('Runtime still contains the pre-M5 cross-width execution blocker')
for token in ('nativeCompanion.prepare', 'nativeCompanion.launchActivity',
              'nativeCompanion.invokeComponent', 'nativeCompanion.stopGuest'):
    if token not in runtime:
        errors.append(f'Runtime is missing companion route {token}')

workspace = text('sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionWorkspaceStore.java')
for token in ('MAX_WORKSPACE_BYTES', 'MAX_ARTIFACTS_PER_WORKSPACE',
              'COMPANION_ARTIFACT_HASH_MISMATCH', 'ATOMIC_MOVE', 'getCanonicalFile'):
    if token not in workspace:
        errors.append(f'Companion workspace is missing {token}')

native_policy = text('sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativePolicy.java')
if 'controlled_sandbox_native32' not in native_policy:
    errors.append('NativePolicy does not load the 32-bit companion hook library')

cmake = text('sandbox-companion32/src/main/cpp/CMakeLists.txt')
for token in ('controlled_sandbox_native32', 'native_policy_jni.cpp', 'native_hook.cpp',
              'native_loader.cpp', 'native_network.cpp', 'native_audio.cpp'):
    if token not in cmake:
        errors.append(f'Companion native target is missing {token}')

if errors:
    print('FAIL native ABI companion architecture checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS native ABI companion runtime and architecture checks')
