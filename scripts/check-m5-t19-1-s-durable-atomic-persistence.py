#!/usr/bin/env python3
from pathlib import Path
import re, shutil, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
UTILITY = ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/DurableAtomicFile.java'
TEST = ROOT / 'sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/persistence/DurableAtomicFileSelfTest.java'


def require(condition, message):
    if not condition:
        raise SystemExit('FAIL M5-T19.1-S ' + message)

source = UTILITY.read_text()
for token in ['channel.force(true)', 'StandardCopyOption.ATOMIC_MOVE',
              'forceDirectory(parent)', 'STRICT_ATOMIC_MOVE_UNSUPPORTED',
              'PARENT_DIRECTORY_FSYNC_FAILED']:
    require(token in source, 'missing strict durability token: ' + token)
require('Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)' not in source,
        'non-atomic replacement fallback is forbidden')

production_roots = [
    ROOT / 'app/src/main/java', ROOT / 'sandbox-domain/src/main/java',
    ROOT / 'sandbox-runtime/src/main/java', ROOT / 'sandbox-framework/src/main/java',
    ROOT / 'sandbox-companion32/src/main/java', ROOT / 'sandbox-contract/src/main/java'
]
violations = []
for base in production_roots:
    for path in base.rglob('*.java'):
        if path == UTILITY:
            continue
        text = path.read_text(errors='replace')
        if 'AtomicMoveNotSupportedException' in text:
            violations.append(str(path.relative_to(ROOT)) + ': catches atomic-move failure')
        if re.search(r'Files\.move\([^;]+StandardCopyOption\.ATOMIC_MOVE[^;]+\)\s*;?\s*}\s*catch', text, re.S):
            violations.append(str(path.relative_to(ROOT)) + ': implements a local atomic fallback')
require(not violations, '; '.join(violations))

required_users = [
    'app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStorePersistence.java',
    'app/src/main/java/com/warden/controlledsandbox/PackageInstallSessionStore.java',
    'app/src/main/java/com/warden/controlledsandbox/VirtualSecretCipher.java',
    'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/RecoverableFileStore.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodec.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/SandboxSharedPreferences.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStore.java',
    'sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionWorkspaceStore.java',
]
for relative in required_users:
    text = (ROOT / relative).read_text()
    require('DurableAtomicFile.' in text, relative + ' does not use strict atomic durability')

build = ROOT / 'build/m5-t19-1-s-durable-atomic'
shutil.rmtree(build, ignore_errors=True)
build.mkdir(parents=True)
compile_result = subprocess.run([
    'javac', '--release', '17', '-Xlint:all', '-d', str(build), str(UTILITY), str(TEST)
], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(compile_result.stdout, end='')
require(compile_result.returncode == 0, 'durability self-test compilation failed')
run = subprocess.run([
    'java', '-ea', '-cp', str(build),
    'com.warden.controlledsandbox.domain.persistence.DurableAtomicFileSelfTest'
], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(run.stdout, end='')
require(run.returncode == 0, 'durability self-test failed')
print('PASS M5-T19.1-S strict atomic persistence and parent-directory durability')
