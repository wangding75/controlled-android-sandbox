#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

files = {
    'keys': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeKeys.java',
    'verifier': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/ApkRevisionVerifier.java',
    'client': ROOT / 'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
    'session': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/GuestSession.java',
    'registry': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/SessionRegistry.java',
    'policy': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/SessionRevisionPolicy.java',
    'broker': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
    'spec': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java',
    'guest': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
    'test': ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/ApkRevisionVerifierSelfTest.java',
    'compiler': ROOT / 'tools/static_android_compile.py',
}
for name, path in files.items():
    if not path.is_file(): errors.append(f'missing {name}: {path.relative_to(ROOT)}')

if not errors:
    text = {name: path.read_text(encoding='utf-8') for name, path in files.items()}
    for key in ['APK_SHA256', 'APK_VERSION_CODE', 'PACKAGE_REVISION']:
        if f'public static final String {key}' not in text['keys']:
            errors.append(f'missing Runtime key {key}')
        if f'RuntimeKeys.{key}' not in text['spec']:
            errors.append(f'GuestPackageSpec does not retain {key}')
    for key in ['APK_SHA256', 'APK_VERSION_CODE']:
        if f'RuntimeKeys.{key}' not in text['client']:
            errors.append(f'RuntimeClient does not send {key}')
    if 'input.putString(RuntimeKeys.PACKAGE_REVISION, revision.canonical())' not in text['broker']:
        errors.append('Broker does not derive authoritative PACKAGE_REVISION')

    required_verifier = [
        'MessageDigest.getInstance("SHA-256")',
        'MessageDigest.isEqual(',
        'throw new SecurityException("APK_SHA256_MISMATCH',
    ]
    for fragment in required_verifier:
        if fragment not in text['verifier']:
            errors.append(f'APK verifier missing control: {fragment}')

    required_session = [
        'private final String packageRevision;',
        'public String packageRevision()',
        'packageRevision,\n                processSlot, generation, next',
    ]
    for fragment in required_session:
        if fragment not in text['session']:
            errors.append(f'GuestSession revision binding missing: {fragment}')

    if 'SESSION_REVISION_MISMATCH' not in text['registry']:
        errors.append('SessionRegistry does not reject active-session revision mismatch')
    if 'mismatchedLiveSessions' not in text['policy']:
        errors.append('Session revision replacement policy is missing')

    required_broker = [
        'ApkRevisionVerifier.verify(apk, apkVersionCode, apkSha256)',
        'stopMismatchedRevisionSessions(packageName, userId, packageRevision)',
        'sessions.allocate(\n                    packageName, userId, processName, packageRevision, now())',
        'PREPARED_SPEC_REVISION_MISMATCH',
    ]
    for fragment in required_broker:
        if fragment not in text['broker']:
            errors.append(f'Broker revision control missing: {fragment}')

    required_guest = [
        'ApkRevisionVerifier.verify(spec.apkFile(), spec.apkVersionCode, spec.apkSha256)',
        'PACKAGE_REVISION_MISMATCH',
        'current.spec.packageRevision.equals(spec.packageRevision)',
    ]
    for fragment in required_guest:
        if fragment not in text['guest']:
            errors.append(f'Guest revision control missing: {fragment}')

    test_class = 'com.warden.controlledsandbox.runtime.protocol.ApkRevisionVerifierSelfTest'
    if text['compiler'].count("'" + test_class + "'") != 1:
        errors.append(f'static compiler must execute {test_class} exactly once')
    if 'changed APK bytes rejected' not in text['test']:
        errors.append('APK revision self-test does not cover post-hash mutation')

if errors:
    print('FAIL immutable APK revision binding checks', file=sys.stderr)
    for error in errors: print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS immutable APK revision binding checks')
