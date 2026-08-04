#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

context_path = ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java'
loader_path = ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestClassLoader.java'
context_test_path = ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextBoundarySelfTest.java'
storage_test_path = ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextStorageTransferSelfTest.java'
loader_test_path = ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestClassLoaderSelfTest.java'
compiler_path = ROOT / 'tools/static_android_compile.py'
framework_hooks_path = ROOT / 'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java'

for path in [context_path, loader_path, context_test_path, storage_test_path, loader_test_path, compiler_path, framework_hooks_path]:
    if not path.is_file():
        errors.append(f'missing required Guest boundary file: {path.relative_to(ROOT)}')

if not errors:
    context = context_path.read_text(encoding='utf-8')
    loader = loader_path.read_text(encoding='utf-8')
    context_test = context_test_path.read_text(encoding='utf-8')
    storage_test = storage_test_path.read_text(encoding='utf-8')
    loader_test = loader_test_path.read_text(encoding='utf-8')
    compiler = compiler_path.read_text(encoding='utf-8')
    framework_hooks = framework_hooks_path.read_text(encoding='utf-8')

    required_context_fragments = {
        '@Override public Context getBaseContext() { return this; }': 'host Context unwrap is not closed',
        '@Override public File getDataDir() { return dataRoot; }': 'Guest data root is not redirected',
        '@Override public File getNoBackupFilesDir()': 'no-backup storage is not redirected',
        '@Override public SQLiteDatabase openOrCreateDatabase(': 'database creation is not redirected',
        '@Override public boolean deleteDatabase(String name)': 'database deletion is not redirected',
        '@Override public boolean moveDatabaseFrom(Context sourceContext, String name)':
            'cross-Context database movement is not implemented',
        '@Override public boolean moveSharedPreferencesFrom(Context sourceContext, String name)':
            'cross-Context preference movement is not implemented',
        '@Override public File getExternalFilesDir(String type)': 'external files are not redirected',
        '@Override public File getExternalCacheDir()': 'external cache is not redirected',
        '@Override public File getObbDir()': 'OBB storage is not redirected',
        '@Override public Context createPackageContext(String packageName, int flags)':
            'cross-package Context acquisition is not fail-closed',
        '@Override public Context createDeviceProtectedStorageContext()':
            'device-protected storage context is not implemented',
        'return new ApplicationInfo(applicationInfo);': 'ApplicationInfo is not returned defensively',
    }
    for fragment, message in required_context_fragments.items():
        if fragment not in context:
            errors.append(message)

    required_loader_fragments = {
        'if (isDeniedSandboxInternal(name) || isPrivilegedContract(name))':
            'GuestClassLoader does not enforce host and privileged-contract denial',
        'name.startsWith("com.warden.controlledsandbox.")': 'host namespace denial is missing',
        '!name.startsWith("com.warden.controlledsandbox.contract.")':
            'stable Guest-safe Binder contract exception is missing',
        'throw new ClassNotFoundException("Sandbox privileged implementation is not a Guest API: " + name);':
            'host and privileged-contract denial does not fail closed',
        'name.equals("com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap")':
            'private Package Authority bootstrap contract is not explicitly denied',
        'name.equals("com.warden.controlledsandbox.contract.IPackageService")':
            'privileged Package Service contract is not explicitly denied',
    }
    for fragment, message in required_loader_fragments.items():
        if fragment not in loader:
            errors.append(message)

    forbidden_parent_first = [
        'com.warden.controlledsandbox.runtime.',
        'com.warden.controlledsandbox.framework.',
        'com.warden.controlledsandbox.nativebridge.',
    ]
    parent_first_body = loader.split('static boolean isParentFirst', 1)[-1]
    for namespace in forbidden_parent_first:
        if namespace in parent_first_body:
            errors.append(f'host implementation namespace remains parent-first: {namespace}')

    if 'getBaseContext() == context' not in context_test:
        errors.append('Guest Context test does not verify host Context unwrap denial')
    if 'createPackageContext("com.warden.controlledsandbox", 0)' not in context_test:
        errors.append('Guest Context test does not verify host package Context denial')
    if 'openOrCreateDatabase("guest.db"' not in context_test:
        errors.append('Guest Context test does not execute redirected database creation')
    if 'moveDatabaseFrom(host, "guest.db")' not in context_test:
        errors.append('Guest Context test does not verify host database move denial')
    for token in ['moveSharedPreferencesFrom(credential', 'moveDatabaseFrom(credential',
                  'createDeviceProtectedStorageContext', 'testPartialMoveRollback']:
        if token not in storage_test:
            errors.append(f'Guest storage transfer test missing {token}')
    for namespace in forbidden_parent_first:
        if namespace not in loader_test:
            errors.append(f'Guest class-loader test does not cover denied namespace {namespace}')
    if ('isPrivilegedContract(' not in loader_test
            or 'com.warden.controlledsandbox.contract.IPackageAuthorityBootstrap' not in loader_test
            or 'com.warden.controlledsandbox.contract.IPackageService' not in loader_test):
        errors.append('Guest class-loader test does not cover private bootstrap and Package Service denial')

    mandatory_hook_names = [
        'packageManager', 'activityManager', 'activityTaskManager', 'appOps', 'permission',
        'notification', 'jobScheduler', 'alarm', 'clipboard', 'account', 'storage',
    ]
    readiness_position = framework_hooks.find(
        'FrameworkHookReport mandatoryReport = new FrameworkHookReport(installed, failures);')
    if readiness_position < 0:
        errors.append('Framework hook installation does not perform a mandatory readiness check')
    else:
        for hook_name in mandatory_hook_names:
            if hook_name in {'activityManager', 'activityTaskManager'}:
                fragment = 'installActivityFrameworkPair(identity, callInterceptor, installed, failures, hooks);'
            else:
                fragment = f'attempt("{hook_name}", installed, failures, hooks'
            position = framework_hooks.find(fragment)
            if position < 0:
                errors.append(f'mandatory framework hook is not installed: {hook_name}')
            elif position > readiness_position:
                errors.append(f'mandatory framework hook is checked before installation: {hook_name}')
        optional_position = framework_hooks.find('attempt("camera", installed, failures, hooks')
        if optional_position >= 0 and optional_position < readiness_position:
            errors.append('optional framework hooks are installed before mandatory readiness closes')

    required_test_classes = [
        'com.warden.controlledsandbox.runtime.guest.GuestClassLoaderSelfTest',
        'com.warden.controlledsandbox.runtime.guest.GuestContextBoundarySelfTest',
        'com.warden.controlledsandbox.runtime.guest.GuestContextStorageTransferSelfTest',
    ]
    for class_name in required_test_classes:
        if compiler.count("'" + class_name + "'") != 1:
            errors.append(f'static compiler must execute {class_name} exactly once')

if errors:
    print('FAIL Guest boundary checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)

print('PASS Guest Context and class-loader boundary checks')
