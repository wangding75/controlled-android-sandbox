#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def require(rel: str, *tokens: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    text = path.read_text(encoding="utf-8-sig")
    for token in tokens:
        if token not in text:
            errors.append(f"{rel} missing: {token}")
    return text


verifier = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageCallerVerifier.java",
    "Binder.getCallingUid()",
    "Binder.getCallingPid()",
    "managementCaller()",
    "runtimeCaller()",
)
for forbidden in ("ActivityManager", "getRunningAppProcesses", "/proc/", "processName("):
    if forbidden in verifier:
        errors.append(f"PackageCallerVerifier retains process-label authorization: {forbidden}")

registry = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageAuthorityCapabilityRegistry.java",
    "installManagement",
    "installRuntime",
    "SERVER_MANAGED_EPOCH",
    "PROCESS_OWNER_MISMATCH",
    "final int ownerUid",
    "final int ownerPid",
    "capability.linkToDeath",
    "capability.unlinkToDeath",
    "requireManagement",
    "requireRuntime",
)
for forbidden in ("registerManagement(", "registerRuntime(", "generation <=", "/proc/"):
    if forbidden in registry:
        errors.append(f"registry retains caller-controlled bootstrap/generation: {forbidden}")

binder = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceBinder.java",
    "PACKAGE_AUTHORITY_PUBLIC_BOOTSTRAP_DISABLED",
    "registerManagementCapability",
    "registerRuntimeCapability",
)
if binder.count("PACKAGE_AUTHORITY_PUBLIC_BOOTSTRAP_DISABLED") < 2:
    errors.append("both legacy public bootstrap transactions must fail closed")

connections = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageAuthorityBootstrapConnections.java",
    "HostPackageAuthorityBootstrapService.class",
    "RuntimePackageAuthorityBootstrapService.class",
    "CompanionRuntimePackageAuthorityBootstrapService",
    "endpoint.ownerPid()",
    "trustedCompanionUid()",
    "packages.checkSignatures(context.getPackageName(), companionPackage)",
    "registry.installManagement(installed, ownerUid, ownerPid)",
    "registry.installRuntime(installed, ownerUid, ownerPid)",
    "registry.installCompanionRuntime(companionPackage, installed, ownerUid, ownerPid)",
)
if "install(installed, ownerUid, ownerPid);" not in connections:
    errors.append("bootstrap connection does not route every fixed endpoint through install-time UID/PID pinning")
require(
    "app/src/main/java/com/warden/controlledsandbox/HostPackageAuthorityBootstrapService.java",
    "IPackageAuthorityBootstrap.Stub",
    "HostPackageAuthorityCapability.token()",
    "return Process.myPid()",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimePackageAuthorityBootstrapService.java",
    "IPackageAuthorityBootstrap.Stub",
    "RuntimePackageAuthorityCapability.token()",
    "return Process.myPid()",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/CompanionRuntimePackageAuthorityBootstrapService.java",
    "IPackageAuthorityBootstrap.Stub",
    "RuntimePackageAuthorityCapability.token()",
    "return Process.myPid()",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageAuthorityBootstrap.aidl",
    "IBinder capability()",
    "int ownerPid()",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestClassLoader.java",
    "isPrivilegedContract",
    "IPackageAuthorityBootstrap",
    "IPackageService",
    "IPackageManagementSession",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestHostOperationDenyContext.java",
    "GUEST_CONTEXT_HOST_OPERATION_DENIED: " .replace(" ", ""),
    "bindService",
    "getContentResolver",
    "getPackageManager",
    "startActivity",
)

root_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
    "registerManagementCapability",
    "registerRuntimeCapability",
    "openManagementSessionWithCapability",
    "Legacy transaction IDs 1-5 are retained",
)
legacy = [
    "IPackageManagementSession openManagementSession(in IBinder clientToken);",
    "IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);",
]
for signature in legacy:
    if signature not in root_aidl:
        errors.append(f"legacy AIDL transaction order changed or removed: {signature}")

android = "{http://schemas.android.com/apk/res/android}"
app_manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
services = {
    node.get(android + "name"): node
    for node in app_manifest.findall("./application/service")
}
host_bootstrap = services.get(".HostPackageAuthorityBootstrapService")
if host_bootstrap is None or host_bootstrap.get(android + "exported") != "false":
    errors.append("Host bootstrap service must be non-exported")

runtime_manifest = ET.parse(ROOT / "sandbox-runtime/src/main/AndroidManifest.xml").getroot()
runtime_services = {
    node.get(android + "name"): node
    for node in runtime_manifest.findall("./application/service")
}
runtime_name = "com.warden.controlledsandbox.runtime.protocol.RuntimePackageAuthorityBootstrapService"
runtime_bootstrap = runtime_services.get(runtime_name)
if runtime_bootstrap is None or runtime_bootstrap.get(android + "exported") != "false":
    errors.append("Runtime bootstrap service must be non-exported")
if runtime_bootstrap is not None and runtime_bootstrap.get(android + "process") != ":sandbox_server":
    errors.append("Runtime bootstrap service must run in :sandbox_server")

companion_manifest = ET.parse(ROOT / "sandbox-companion32/src/main/AndroidManifest.xml").getroot()
companion_services = {
    node.get(android + "name"): node
    for node in companion_manifest.findall("./application/service")
}
companion_name = "com.warden.controlledsandbox.runtime.protocol.CompanionRuntimePackageAuthorityBootstrapService"
companion_bootstrap = companion_services.get(companion_name)
if companion_bootstrap is None:
    errors.append("Companion Runtime bootstrap service is missing")
else:
    if companion_bootstrap.get(android + "exported") != "true":
        errors.append("Companion Runtime bootstrap must be exported for the signed Host package")
    if companion_bootstrap.get(android + "permission") != "com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION":
        errors.append("Companion Runtime bootstrap must require the signature permission")
    if companion_bootstrap.get(android + "process") != ":sandbox_server32":
        errors.append("Companion Runtime bootstrap must run in :sandbox_server32")

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/PackageManagementAuthorizationSelfTest.java",
    "first same-UID process claimed management capability",
    "caller-supplied management token was accepted",
    "caller-controlled management generation was accepted",
    "different same-UID process reused management capability",
    "same-UID Guest process reused Runtime capability",
    "same-UID Guest process substituted Runtime capability",
    "dead management capability remained active",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.PackageManagementAuthorizationSelfTest",
)
if runner.count("'com.warden.controlledsandbox.PackageManagementAuthorizationSelfTest'") != 1:
    errors.append("PackageManagementAuthorizationSelfTest must execute exactly once")

receipt = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt.is_file():
    errors.append("static Android execution receipt missing")
else:
    executed = set(json.loads(receipt.read_text(encoding="utf-8")).get("executedTests", []))
    if "com.warden.controlledsandbox.PackageManagementAuthorizationSelfTest" not in executed:
        errors.append("execution receipt omits PackageManagementAuthorizationSelfTest")

report = {
    "task": "M5-T19.1-O3",
    "finding": "FULL-P1-01 same-UID Guest capability bootstrap",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "publicBootstrapEnabled": False,
    "epochOwner": "package-service",
    "managementBootstrap": "explicit non-exported main-process component with install-time PID pin",
    "runtimeBootstrap": "explicit non-exported :sandbox_server component with install-time PID pin",
    "companionBootstrap": "explicit signature-protected :sandbox_server32 component with package/signature/UID/PID pin",
    "ownerPidBoundAtInstall": True,
    "guestPrivilegedContractAccess": "denied",
    "legacyTransactionsRetained": True,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-o-caller-uid-identity.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-O3 private Package Authority bootstrap", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-O3 private Package Authority bootstrap and Guest denial")
