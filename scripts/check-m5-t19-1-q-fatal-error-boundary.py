#!/usr/bin/env python3
from pathlib import Path
import re, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
critical=[]
critical += [ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageRuntimePermissionSession.java',
             ROOT/'app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java']
critical += list((ROOT/'sandbox-runtime/src/main/java').rglob('*.java'))
critical += [ROOT/'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/RecoverableFileStore.java',
             ROOT/'sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionWorkspaceStore.java']
framework_names={'VirtualSystemServiceState.java','CapabilityLeaseRegistry.java','SystemServiceInvocationHandler.java',
 'VirtualSystemServiceInterceptor.java','ApplicationEnvironmentInvocationInterceptor.java',
 'DeviceServiceInvocationInterceptor.java','PolicyServicesInvocationInterceptor.java',
 'NetworkServiceInvocationInterceptor.java','FrameworkHooks.java'}
critical += [p for p in (ROOT/'sandbox-framework/src/main/java').rglob('*.java') if p.name in framework_names]
pat=re.compile(r'catch\s*\(\s*Throwable\s+(\w+)\s*\)\s*\{')
guarded=0
for p in critical:
    if not p.is_file(): continue
    text=p.read_text(encoding='utf-8')
    for m in pat.finditer(text):
        var=m.group(1)
        body=text[m.end():m.end()+260]
        if f'rethrowIfFatal({var})' not in body:
            errors.append(f'{p.relative_to(ROOT)} catch(Throwable {var}) lacks fatal-error rethrow')
        else: guarded+=1
helpers=[
 ROOT/'app/src/main/java/com/warden/controlledsandbox/FatalErrorPolicy.java',
 ROOT/'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/FatalErrorPolicy.java',
 ROOT/'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/capability/FatalErrorPolicy.java',
 ROOT/'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/FatalErrorPolicy.java',
 ROOT/'sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/FatalErrorPolicy.java']
for p in helpers:
    text=p.read_text(encoding='utf-8') if p.is_file() else ''
    if 'instanceof Error' not in text or 'getCause()' not in text:
        errors.append(f'{p.relative_to(ROOT)} must rethrow direct and wrapped Error values')
# Representative executable proofs must remain in their real test paths.
proofs={
 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerOperationBoundarySelfTest.java':'fatal-runtime-boundary',
 'sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/SelfTest.java':'fatal-persistence',
 'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/CapabilityServiceProxySelfTest.java':'fatal-cleanup'}
runner=(ROOT/'tools/static_android_compile.py').read_text(encoding='utf-8')
for rel,token in proofs.items():
    text=(ROOT/rel).read_text(encoding='utf-8')
    if token not in text: errors.append(f'{rel} missing executable fatal-boundary proof')
if 'RuntimeBrokerOperationBoundarySelfTest' not in runner or 'CapabilityServiceProxySelfTest' not in runner:
    errors.append('static Android runner must execute Runtime and Framework fatal-boundary proofs')
if guarded < 90: errors.append(f'guarded critical catch count unexpectedly low: {guarded}')
if errors:
    print('FAIL M5-T19.1-Q fatal-error boundary',file=sys.stderr)
    for e in errors: print(' - '+e,file=sys.stderr)
    raise SystemExit(1)
print(f'PASS M5-T19.1-Q fatal-error boundary ({guarded} guarded catches)')
