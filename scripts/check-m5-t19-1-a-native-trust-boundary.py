#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/m5-t19-1-a-native-trust-boundary.json"
errors: list[str] = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig", errors="strict")


def require(rel: str, *tokens: str) -> str:
    value = read(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/InstallSessionParamsSnapshot.java",
    'NATIVE_GUEST_TRUST_UNTRUSTED = "UNTRUSTED"',
    'NATIVE_GUEST_TRUST_EXPLICITLY_TRUSTED = "EXPLICITLY_TRUSTED"',
    "nativeGuestTrust",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/NativeGuestExecutionPolicy.java",
    "UNTRUSTED_NATIVE_GUEST_DENIED",
    "BEST_EFFORT_COMPATIBILITY",
    "PLT/GOT hooks are best-effort only",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java",
    "containsNativeCode(stagedRecords)",
    "hasElfMagic",
    "requireInstallAllowed",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/SandboxRecord.java",
    "containsNativeCode",
    "nativeGuestTrust",
    "nativeExecutionMode",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceBinder.java",
    "NativeGuestExecutionPolicy.requireRuntimeAllowed(authoritative)",
    "VIRTUAL_SYSTEM_SERVICE_REVISION_MISMATCH",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
    "NativeGuestExecutionPolicy.requireRuntimeAllowed(record)",
    "RuntimeKeys.NATIVE_CODE_PRESENT",
    "RuntimeKeys.NATIVE_GUEST_TRUST",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeGuestPolicyContract.java",
    "UNTRUSTED_NATIVE_GUEST_DENIED",
    "BEST_EFFORT_COMPATIBILITY",
    "requireAllowed",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "NativeGuestPolicyContract.requireAllowed",
    "RuntimeKeys.NATIVE_GUEST_TRUST",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java",
    "NativeGuestPolicyContract.requireAllowed",
    "RuntimeKeys.NATIVE_GUEST_TRUST",
)
require(
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_hook.h",
    "best-effort compatibility and redirection mechanism",
    "direct syscalls or inline assembly",
)
require(
    "sandbox-native/src/test/cpp/native_syscall_boundary_self_test.cpp",
    "SYS_openat",
    "SYS_connect",
    "SYS_sendto",
    "host-private-secret",
)
require(
    "app/src/testHarness/java/com/warden/controlledsandbox/NativeGuestExecutionPolicySelfTest.java",
    "ELF payload outside lib/ is detected",
    "legacy native record is fail-closed",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextBoundarySelfTest.java",
    "Guest spec rejects untrusted native payload",
)
require(
    "scripts/test-native.sh",
    "native_syscall_boundary_self_test.cpp",
    "native_syscall_boundary_self_test",
)
runner = require("tools/static_android_compile.py", "NativeGuestExecutionPolicySelfTest")
if runner.count("NativeGuestExecutionPolicySelfTest") != 1:
    errors.append("static Android compiler must execute NativeGuestExecutionPolicySelfTest exactly once")
require(
    "docs/THREAT_MODEL.md",
    "Direct syscalls and inline assembly bypass Native PLT/GOT interception",
    "separate UID/isolated execution architecture",
)
require(
    "docs/ARCHITECTURE.md",
    "best-effort compatibility and redirection layer",
    "EXPLICITLY_TRUSTED",
)
require("README.md", "## M5-T19.1-A Native Guest trust-boundary fix")
require("docs/M5_T19_1_A_DEVELOPMENT_REPORT.md",
        "Source fix: PASS", "Strong hostile-Native isolation: not claimed",
        "device evidence 0")

report = {
    "task": "M5-T19.1-A",
    "finding": "P1-01 direct syscall bypass of PLT/GOT isolation",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "productPolicy": "DENY_PACKAGED_NATIVE_BY_DEFAULT",
    "trustedNativeExecutionMode": "BEST_EFFORT_COMPATIBILITY",
    "strongIsolationClaimed": False,
    "directSyscallBypassEliminatedByHooks": False,
    "admissionChecks": [
        "APK artifact scan",
        "install authority",
        "RuntimeClient",
        "Runtime Broker",
        "Guest specification",
        "Package Service authoritative record",
    ],
    "regressions": [
        "SYS_openat bypass characterization",
        "SYS_connect bypass characterization",
        "SYS_sendto bypass characterization",
        "untrusted packaged ELF denial",
        "legacy native record fail-closed",
        "explicit trusted native admission",
    ],
    "deviceEvidenceCount": 0,
    "remainingStrongIsolationWork": "different UID/isolated execution plus Broker-only Host file/network access",
    "errors": errors,
}
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-A native trust-boundary checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-A native Guest admission, direct-syscall characterization and honest-boundary gate")
