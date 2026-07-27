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
    if service.get(android+'exported') != 'false': errors.append('PackageManagementService must not be exported')
    if service.get(android+'process') != ':sandbox_package': errors.append('PackageManagementService must own :sandbox_package')

session_aidl=(ROOT/'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl').read_text()
root_aidl=(ROOT/'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl').read_text()
if 'Bundle' in session_aidl or 'Bundle' in root_aidl: errors.append('package management AIDL must remain typed')
if 'IPackageManagementSession openManagementSession(in IBinder clientToken);' not in root_aidl:
    errors.append('root package service must mint a Binder capability from a client death token')

service_source=(ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java').read_text()
for fragment in ['Binder.getCallingUid()', 'Binder.getCallingPid()', 'clientToken.linkToDeath',
                 'guard.requireOwner', 'callerVerifier.requireMainProcessCaller()',
                 'synchronized (operationLock)']:
    if fragment not in service_source: errors.append('missing package service security/serialization fragment: '+fragment)

for path in [ROOT/'app/src/main/java/com/warden/controlledsandbox/MainActivity.java',
             ROOT/'app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java']:
    text=path.read_text()
    if 'new SandboxPackageLifecycle' in text: errors.append(f'{path.relative_to(ROOT)} bypasses Binder package authority')
    if 'PackageServiceClient' not in text: errors.append(f'{path.relative_to(ROOT)} is not wired to PackageServiceClient')

for path in (ROOT/'app/src/main/java').rglob('*.java'):
    if path.name == 'PackageManagementService.java': continue
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
