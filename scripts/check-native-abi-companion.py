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

host_build = text('sandbox-native/build.gradle')
companion_build = text('sandbox-companion32/build.gradle')
if not re.search(r"abiFilters\s+'arm64-v8a',\s*'x86_64'", host_build):
    errors.append('Host native module must be restricted to arm64-v8a and x86_64')
if re.search(r"armeabi-v7a|(?<!_)\bx86\b", host_build):
    errors.append('Host native module silently includes a 32-bit ABI')
if not re.search(r"abiFilters\s+'armeabi-v7a',\s*'x86'", companion_build):
    errors.append('Companion module must be restricted to armeabi-v7a and x86')
if re.search(r"arm64-v8a|x86_64", companion_build):
    errors.append('Companion module silently includes a 64-bit ABI')
if "project(':sandbox-contract')" not in companion_build:
    errors.append('Companion must depend on the typed contract module')

contract_paths = [
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/INativeAbiCompanion.aidl',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionRequest.java',
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionResult.java',
]
contract = '\n'.join(text(path) for path in contract_paths)
if 'Bundle' in contract:
    errors.append('Native companion contract contains an untyped Bundle')
for token in ('sessionId', 'generation', 'virtualUserId', 'packageRevision', 'capabilityNonce', 'requestedAbi'):
    if token not in contract:
        errors.append(f'Native companion contract is missing {token}')

host_manifest = text('app/src/main/AndroidManifest.xml')
companion_manifest = text('sandbox-companion32/src/main/AndroidManifest.xml')
permission = 'com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION'
if permission not in host_manifest or 'protectionLevel="signature"' not in host_manifest:
    errors.append('Host does not declare the signature-level companion permission')
for token in (permission, 'android:exported="true"', 'android:process=":native32"'):
    if token not in companion_manifest:
        errors.append(f'Companion manifest is missing {token}')

client = text('app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java')
planner = text('app/src/main/java/com/warden/controlledsandbox/NativeAbiRoutePlanner.java')
runtime = text('app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java')
for token in ('setComponent(new ComponentName', 'SecureRandom', 'INativeAbiCompanion', 'BIND_TIMEOUT_SECONDS'):
    if token not in client:
        errors.append(f'Explicit companion client is missing {token}')
for abi in ('arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86', 'legacy-unknown'):
    if abi not in planner:
        errors.append(f'ABI route planner is missing {abi}')
if 'NATIVE_COMPANION_CROSS_WIDTH_EXECUTION_NOT_WIRED' not in runtime:
    errors.append('Runtime does not fail closed before mismatched Host execution')

cmake = text('sandbox-companion32/src/main/cpp/CMakeLists.txt')
for token in ('controlled_sandbox_native32', 'native_hook.cpp', 'native_loader.cpp', 'native_network.cpp', 'native_audio.cpp'):
    if token not in cmake:
        errors.append(f'Companion native target is missing {token}')

plan = text('docs/plans/M4_T17_DEVELOPMENT_PLAN.md')
for token in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86', 'separate companion APK'):
    if token not in plan:
        errors.append(f'M4-T17 plan is missing ABI decision: {token}')

if errors:
    print('FAIL native ABI companion architecture checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS native ABI companion architecture checks')
