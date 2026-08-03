#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors=[]
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
services=manifest.findall('./application/service')
service=None
for candidate in services:
    if candidate.get(android+'name') == '.PackageManagementService':
        service=candidate
        break
if service is None:
    errors.append('PackageManagementService missing from app manifest')
else:
    if service.get(android+'exported') != 'true': errors.append('PackageManagementService must be exported for the signed 32-bit peer')
    if service.get(android+'permission') != 'com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION':
        errors.append('exported PackageManagementService must require the signature companion permission')
    if service.get(android+'process') != ':sandbox_package': errors.append('PackageManagementService must own :sandbox_package')
queries = manifest.find('./queries')
query_packages = set()
if queries is not None:
    for item in queries.findall('./package'):
        value = item.get(android+'name')
        if value: query_packages.add(value)
for expected in ('com.warden.controlledsandbox.companion32',
                 'com.warden.controlledsandbox.companion32.debug'):
    if expected not in query_packages:
        errors.append('Host manifest queries must expose Companion package identity: '+expected)

permissions = manifest.findall('./permission')
if not any(item.get(android+'name') == 'com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION'
           and item.get(android+'protectionLevel') == 'signature' for item in permissions):
    errors.append('Host manifest must declare the companion Binder permission at signature level')

session_aidl=(ROOT/'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl').read_text()
root_aidl=(ROOT/'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl').read_text()
if 'Bundle' in session_aidl or 'Bundle' in root_aidl: errors.append('package management AIDL must remain typed')
if 'IPackageManagementSession openManagementSession(in IBinder clientToken);' not in root_aidl:
    errors.append('root package service must mint a Binder capability from a client death token')
for fragment in ['registerManagementCapability', 'registerRuntimeCapability',
                 'openManagementSessionWithCapability',
                 'openRuntimePermissionSessionWithCapability']:
    if fragment not in root_aidl:
        errors.append('root package service missing role capability method: '+fragment)

service_source=(ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageServiceBinder.java').read_text()
management_source=(ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java').read_text()
authority_guard=(ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageManagementAuthorityGuard.java').read_text()
for fragment in ['Binder.getCallingUid()', 'Binder.getCallingPid()', 'clientToken.linkToDeath']:
    if fragment not in service_source:
        errors.append('missing package service capability-minting fragment: '+fragment)
for fragment in ['guard.requireOwner', 'synchronized (operationLock)']:
    if fragment not in management_source:
        errors.append('missing package management session security/serialization fragment: '+fragment)
for fragment in ['owner.requireOwner', 'registry.requireManagement(capability, generation)']:
    if fragment not in authority_guard:
        errors.append('missing package authority guard fragment: '+fragment)
verifier_source=(ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageCallerVerifier.java').read_text()
if ('ActivityManager' in verifier_source or 'getRunningAppProcesses' in verifier_source
        or '/proc/' in verifier_source or 'processName(' in verifier_source):
    errors.append('PackageCallerVerifier must not depend on process lists or mutable process labels')
for fragment in ['checkCallingPermission', 'RUNTIME_PERMISSION_CALLER_NOT_TRUSTED_UID',
                 'getPackageUid', 'managementCaller()', 'runtimeCaller()',
                 'Binder.getCallingPid()', 'Binder.getCallingUid()']:
    if fragment not in verifier_source:
        errors.append('missing stable UID/package verification fragment: '+fragment)

for path in [ROOT/'app/src/main/java/com/warden/controlledsandbox/MainActivity.java',
             ROOT/'app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java']:
    text=path.read_text()
    if 'new SandboxPackageLifecycle' in text: errors.append(f'{path.relative_to(ROOT)} bypasses Binder package authority')
    if 'PackageServiceClient' not in text: errors.append(f'{path.relative_to(ROOT)} is not wired to PackageServiceClient')

for path in (ROOT/'app/src/main/java').rglob('*.java'):
    if path.name in {'PackageManagementService.java', 'PackageServiceDependencies.java'}: continue
    if 'new SandboxPackageLifecycle' in path.read_text():
        errors.append(f'{path.relative_to(ROOT)} directly constructs SandboxPackageLifecycle')

compiler=(ROOT/'tools/static_android_compile.py').read_text()
if "'com.warden.controlledsandbox.PackageManagementAuthorizationSelfTest'" not in compiler:
    errors.append('static compiler must execute package management authorization self-test')
if "'com.warden.controlledsandbox.PackageServiceContractSelfTest'" not in compiler:
    errors.append('static compiler must execute package service typed contract self-test')

if errors:
    print('FAIL package service boundary checks', file=sys.stderr)
    for e in errors: print(' - '+e, file=sys.stderr)
    raise SystemExit(1)
print('PASS Binder-owned package service boundary checks')
