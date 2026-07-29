#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

required = [
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_file_system.h",
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_policy.h",
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_hook.h",
    "sandbox-native/src/main/cpp/native_file_system.cpp",
    "sandbox-native/src/main/cpp/native_policy.cpp",
    "sandbox-native/src/main/cpp/native_hook.cpp",
    "sandbox-native/src/main/cpp/native_policy_jni.cpp",
    "sandbox-native/src/test/cpp/native_file_system_self_test.cpp",
    "sandbox-native/src/test/cpp/native_file_hook_fixture.cpp",
    "sandbox-native/src/test/cpp/native_hook_self_test.cpp",
    "docs/fixes/b3-t5a-native-file-system-hooks.md",
]
for relative in required:
    if not (ROOT / relative).is_file():
        errors.append(f"missing native file-hook evidence: {relative}")

hook = (ROOT / "sandbox-native/src/main/cpp/native_hook.cpp").read_text(encoding="utf-8")
interceptors = (ROOT / "sandbox-native/src/main/cpp/native_interceptors.cpp").read_text(encoding="utf-8")
implementation = hook + "\n" + interceptors
for symbol in [
    '"open"', '"open64"', '"openat"', '"openat64"', '"__open_2"', '"__openat_2"',
    '"access"', '"faccessat"', '"stat"', '"lstat"', '"fstatat"',
    '"readlink"', '"readlinkat"',
]:
    if symbol not in implementation:
        errors.append(f"native hook target missing: {symbol}")
for token in [
    "NativeFileSystemResolver::resolve(",
    "NativeFileSystemResolver::resolve_at(",
    "NativeFileSystemResolver::validate_confinement(",
    "NativeFileSystemResolver::rewrite_readlink_result(",
    "supported_relocation_type(",
    "protection_for_address(",
    "POLICY_REVISION_CHANGED_REINSTALL_REQUIRED",
    "patch_failures",
]:
    if token not in implementation:
        errors.append(f"native hook implementation missing: {token}")
if "global_policy().map_path(" in implementation:
    errors.append("native syscall wrappers must route through NativeFileSystemResolver")
if "restore_readonly" in implementation:
    errors.append("PLT patching must restore original page protection, not force read-only")

resolver = (ROOT / "sandbox-native/src/main/cpp/native_file_system.cpp").read_text(encoding="utf-8")
for token in [
    "/proc/self/fd/", "DIRFD_NOT_DIRECTORY", "CONFINEMENT_SYMLINK_ESCAPE",
    "reverse_map_path", "rewrite_readlink_result", "AT_FDCWD", "realpath",
]:
    if token not in resolver:
        errors.append(f"native file resolver missing: {token}")

policy = (ROOT / "sandbox-native/src/main/cpp/native_policy.cpp").read_text(encoding="utf-8")
for token in [
    "NATIVE_POLICY_SESSION_ACTIVE", "STALE_NATIVE_POLICY_GENERATION",
    "NATIVE_POLICY_IDENTITY_CHANGED_WITHIN_SESSION", "PATH_TRAVERSAL",
    "CROSS_PACKAGE_PRIVATE_PATH_DENIED", "CROSS_PACKAGE_APK_PATH_DENIED",
    "PROC_SELF_PATH_DENIED", "is_data_app_apk_alias", "data_app_library_suffix",
]:
    if token not in policy:
        errors.append(f"native policy missing: {token}")
if "if (!configured_) return std::string(guest_path)" in policy:
    errors.append("unconfigured native path policy must fail closed")

java_policy = (ROOT / "sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativePolicy.java").read_text(encoding="utf-8")
for token in ["String sessionId", "long generation", "String apkPath", "String nativeLibraryRoot", "resetPolicy()"]:
    if token not in java_policy:
        errors.append(f"Java native policy boundary missing: {token}")

guest = (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java").read_text(encoding="utf-8")
for token in [
    "spec.sessionId, spec.generation", "spec.apkPath", "spec.nativeLibraryDir",
    "NATIVE_FILE_POLICY_UNAVAILABLE", "NATIVE_FILE_HOOK_INSTALL_FAILED",
    "NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_CREATE",
    "NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_ONCREATE",
    "NativePolicy.resetPolicy()",
]:
    if token not in guest:
        errors.append(f"Guest native file-hook wiring missing: {token}")

component = (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestComponentRuntime.java").read_text(encoding="utf-8")
for token in ["requireNativeHookRefresh(\"SERVICE_CREATE\")", "requireNativeHookRefresh(\"PROVIDER_CREATE\")"]:
    if token not in component:
        errors.append(f"Guest component native hook refresh missing: {token}")

context = (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java").read_text(encoding="utf-8")
for token in ["private final File dataRoot", "new File(dataRoot, \"files\")", "new File(dataRoot, \"cache\")"]:
    if token not in context:
        errors.append(f"Guest Java/native data layout is inconsistent: {token}")

cmake = (ROOT / "sandbox-native/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
if "native_file_system.cpp" not in cmake:
    errors.append("native_file_system.cpp is not part of the Android native library")

test_script = (ROOT / "scripts/test-native.sh").read_text(encoding="utf-8")
for token in ["native_file_system_self_test", "native_file_hook_fixture", "libnative_file_hook_fixture.so"]:
    if token not in test_script:
        errors.append(f"native file-hook test wiring missing: {token}")

if errors:
    print("FAIL native file-system hook architecture checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS native file-system hook architecture checks")
