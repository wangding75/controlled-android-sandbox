#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

required = [
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_procfs.h',
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_loader.h',
    'sandbox-native/src/main/cpp/native_procfs.cpp',
    'sandbox-native/src/main/cpp/native_interceptors.cpp',
    'sandbox-native/src/main/cpp/include/controlled_sandbox/native_interceptors.h',
    'sandbox-native/src/main/cpp/native_loader.cpp',
    'sandbox-native/src/test/cpp/native_procfs_self_test.cpp',
    'sandbox-native/src/test/cpp/native_loader_self_test.cpp',
    'sandbox-native/src/test/cpp/native_loader_child_fixture.cpp',
    'docs/plans/M4_T17_DEVELOPMENT_PLAN.md',
]
for relative in required:
    if not (ROOT / relative).is_file():
        errors.append(f'missing M4-T17 B1 evidence: {relative}')

hook = (ROOT / 'sandbox-native/src/main/cpp/native_hook.cpp').read_text(encoding='utf-8')
interceptors = (ROOT / 'sandbox-native/src/main/cpp/native_interceptors.cpp').read_text(encoding='utf-8')
for symbol in ['"openat2"', '"statx"', '"renameat2"', '"faccessat2"',
               '"getdents64"', '"mmap"', '"android_dlopen_ext"']:
    if symbol not in hook:
        errors.append(f'native hook target missing: {symbol}')
for token in ['controlled_openat2(', 'controlled_statx(', 'controlled_renameat2(',
              'controlled_faccessat2(', 'controlled_getdents64(', 'controlled_mmap(',
              'controlled_android_dlopen_ext(', 'validate_same_confinement(',
              'NativeLibraryLoaderPolicy::resolve(']:
    if token not in interceptors:
        errors.append(f'native interceptor implementation missing: {token}')

procfs = (ROOT / 'sandbox-native/src/main/cpp/native_procfs.cpp').read_text(encoding='utf-8')
for token in ['/proc/self/maps', '/proc/self/cmdline', '/proc/self/status',
              '[anon:sandbox-runtime]', 'virtual_uid', 'virtual_pid', 'kMaxMapsBytes']:
    if token not in procfs:
        errors.append(f'procfs virtualization missing: {token}')

loader = (ROOT / 'sandbox-native/src/main/cpp/native_loader.cpp').read_text(encoding='utf-8')
for token in ['DLOPEN_MAIN_PROGRAM_DENIED', 'DLOPEN_SONAME_NOT_ALLOWED',
              'native_library_root', 'is_allowed_system_soname', 'libandroid.so']:
    if token not in loader:
        errors.append(f'native loader policy missing: {token}')

policy_header = (ROOT / 'sandbox-native/src/main/cpp/include/controlled_sandbox/native_policy.h').read_text(encoding='utf-8')
for token in ['process_name', 'virtual_uid', 'virtual_pid', 'abi_name']:
    if token not in policy_header:
        errors.append(f'native policy identity snapshot missing: {token}')

cmake = (ROOT / 'sandbox-native/src/main/cpp/CMakeLists.txt').read_text(encoding='utf-8')
for token in ['native_procfs.cpp', 'native_loader.cpp', 'native_interceptors.cpp']:
    if token not in cmake:
        errors.append(f'Android native build missing: {token}')

test_script = (ROOT / 'scripts/test-native.sh').read_text(encoding='utf-8')
for token in ['native_procfs_self_test', 'native_loader_self_test', 'libnative_loader_child.so']:
    if token not in test_script:
        errors.append(f'native test wiring missing: {token}')


metadata_sources = {
    'app/src/main/java/com/warden/controlledsandbox/SandboxRecord.java': ['nativeAbi', '"nativeAbi"'],
    'app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java': ['selectedAbi'],
    'app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java': ['RuntimeKeys.NATIVE_ABI'],
    'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageRecordSnapshot.java': ['nativeAbi()', 'writeString(nativeAbi)'],
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java': ['NATIVE_ABI_METADATA_MISSING', 'RuntimeKeys.NATIVE_ABI'],
}
for relative, tokens in metadata_sources.items():
    text = (ROOT / relative).read_text(encoding='utf-8')
    for token in tokens:
        if token not in text:
            errors.append(f'native ABI metadata wiring missing in {relative}: {token}')

if errors:
    print('FAIL M4-T17 B1 native filesystem/loader checks', file=sys.stderr)
    for error in errors: print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS M4-T17 B1 native filesystem/loader checks')
