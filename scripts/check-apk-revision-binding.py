#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

files = {
    'keys': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeKeys.java',
    'single_verifier': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/ApkRevisionVerifier.java',
    'set_verifier': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/PackageRevisionSetVerifier.java',
    'client': ROOT / 'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
    'session': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/GuestSession.java',
    'registry': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/SessionRegistry.java',
    'policy': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/session/SessionRevisionPolicy.java',
    'broker': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
    'validator': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestRequestValidator.java',
    'lifecycle': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestLifecycleCoordinator.java',
    'spec': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java',
    'guest': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java',
    'single_test': ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/ApkRevisionVerifierSelfTest.java',
    'set_test': ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/PackageRevisionSetVerifierSelfTest.java',
    'compiler': ROOT / 'tools/static_android_compile.py',
}
for name, path in files.items():
    if not path.is_file(): errors.append(f'missing {name}: {path.relative_to(ROOT)}')

if not errors:
    text = {name: path.read_text(encoding='utf-8') for name, path in files.items()}
    for key in ['APK_SHA256', 'BASE_APK_SHA256', 'APK_VERSION_CODE', 'PACKAGE_REVISION',
                'SPLIT_NAMES', 'SPLIT_TYPES', 'SPLIT_CONFIG_FOR', 'SPLIT_USES',
                'SPLIT_PATHS', 'SPLIT_SHA256S']:
        if f'public static final String {key}' not in text['keys']:
            errors.append(f'missing Runtime key {key}')
    for key in ['APK_SHA256', 'BASE_APK_SHA256', 'APK_VERSION_CODE', 'SPLIT_PATHS', 'SPLIT_SHA256S']:
        if f'RuntimeKeys.{key}' not in text['spec']:
            errors.append(f'GuestPackageSpec does not retain {key}')
        if f'RuntimeKeys.{key}' not in text['client']:
            errors.append(f'RuntimeClient does not send {key}')
    if 'input.putString(RuntimeKeys.PACKAGE_REVISION, revision.canonical())' not in text['validator']:
        errors.append('Broker validator does not derive authoritative PACKAGE_REVISION')

    for fragment in ['MessageDigest.getInstance("SHA-256")', 'MessageDigest.isEqual(',
                     'throw new SecurityException("APK_SHA256_MISMATCH']:
        if fragment not in text['single_verifier']:
            errors.append(f'single APK verifier missing control: {fragment}')
    for fragment in ['PackageRevisionSetVerifier', 'digestValidated(all)',
                     'SPLIT_APK_SHA256_MISMATCH', 'PACKAGE_REVISION_SET_MISMATCH']:
        if fragment not in text['set_verifier']:
            errors.append(f'multi-APK verifier missing control: {fragment}')

    for fragment in ['private final String packageRevision;', 'public String packageRevision()',
                     'packageRevision,\n                processSlot, generation, next']:
        if fragment not in text['session']:
            errors.append(f'GuestSession revision binding missing: {fragment}')
    if 'SESSION_REVISION_MISMATCH' not in text['registry']:
        errors.append('SessionRegistry does not reject active-session revision mismatch')
    if 'mismatchedLiveSessions' not in text['policy']:
        errors.append('Session revision replacement policy is missing')

    # The broker lifecycle was split after this gate was first introduced.  Keep the
    # assertions tied to the owning classes instead of requiring the implementation to
    # collapse validation, session allocation, and recovery into RuntimeBrokerService.
    broker_control = '\n'.join((text['validator'], text['lifecycle']))
    required_broker = [
        'PackageRevisionSetVerifier.verify(',
        'Split metadata arrays must have identical sizes',
        'Split APK path is outside app-private storage',
        'stopMismatchedRevisionSessions(packageName, userId, packageRevision)',
        'owner.sessions.allocate(\n                    packageName, userId, processName, packageRevision, owner.now())',
        'PREPARED_SPEC_REVISION_MISMATCH',
    ]
    for fragment in required_broker:
        if fragment not in broker_control:
            errors.append(f'Broker revision control missing from validator/lifecycle: {fragment}')

    required_guest = [
        'PackageRevisionSetVerifier.verify(spec.apkFile(), spec.baseApkSha256',
        'PACKAGE_REVISION_MISMATCH',
        'current.spec.packageRevision.equals(spec.packageRevision)',
    ]
    for fragment in required_guest:
        if fragment not in text['guest']:
            errors.append(f'Guest revision control missing: {fragment}')

    tests = [
        ('com.warden.controlledsandbox.runtime.protocol.ApkRevisionVerifierSelfTest', 'changed APK bytes rejected', 'single_test'),
        ('com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifierSelfTest', 'split tampering rejected', 'set_test'),
    ]
    for test_class, evidence, key in tests:
        if text['compiler'].count("'" + test_class + "'") != 1:
            errors.append(f'static compiler must execute {test_class} exactly once')
        if evidence not in text[key]:
            errors.append(f'{test_class} does not cover required mutation evidence')

if errors:
    print('FAIL immutable APK revision binding checks', file=sys.stderr)
    for error in errors: print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS immutable APK revision binding checks')
