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
    "getPackageUid(packageName, 0)",
    "RuntimePeerIdentity.SIGNATURE_PERMISSION",
    "signaturePermissionGranted()",
    "companionPackageForUid",
    "managementCaller()",
    "runtimeCaller()",
    "RUNTIME_PERMISSION_CALLER_NOT_TRUSTED_UID",
)
for forbidden in (
        "ActivityManager", "getRunningAppProcesses", "/proc/", "FileInputStream",
        "processName(", "actualProcessName", "expectedProcessName"):
    if forbidden in verifier:
        errors.append(f"PackageCallerVerifier retains mutable process-label authorization: {forbidden}")

require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/RuntimePeerIdentity.java",
    "SIGNATURE_PERMISSION",
    "COMPANION_RELEASE_PACKAGE",
    "COMPANION_DEBUG_PACKAGE",
)

policy = require(
    "app/src/main/java/com/warden/controlledsandbox/ManagementCallerPolicy.java",
    "canBootstrapManagement",
    "canBootstrapRuntime",
    "callingUid == hostUid",
    "signaturePermissionGranted",
)
for forbidden in ("processName", "expectedProcessName", "actualProcessName"):
    if forbidden in policy:
        errors.append(f"ManagementCallerPolicy retains process-label comparison: {forbidden}")

registry = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageAuthorityCapabilityRegistry.java",
    "generation <= existing.generation",
    "ownerPid == caller.pid",
    "capability.linkToDeath",
    "capability.unlinkToDeath",
    "PACKAGE_AUTHORITY_ROLE_ALREADY_REGISTERED",
    "PACKAGE_AUTHORITY_GENERATION_NOT_ADVANCED",
    "requireManagement",
    "requireRuntime",
)
if "processName" in registry or "/proc/" in registry:
    errors.append("PackageAuthorityCapabilityRegistry must not authorize process labels")

root_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
    "registerManagementCapability",
    "registerRuntimeCapability",
    "openManagementSessionWithCapability",
    "openRuntimePermissionSessionWithCapability",
    "openVirtualSystemServiceSessionWithCapability",
    "startVirtualJobWithCapability",
    "stopVirtualJobWithCapability",
    "Legacy transaction IDs 1-5 are retained",
)
legacy = [
    "IPackageManagementSession openManagementSession(in IBinder clientToken);",
    "IRuntimePermissionSession openRuntimePermissionSession(in IBinder clientToken);",
]
for signature in legacy:
    if signature not in root_aidl:
        errors.append(f"legacy AIDL transaction order changed or removed: {signature}")

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
queries = {node.get(android + "name") for node in manifest.findall("./queries/package")}
for package_name in (
        "com.warden.controlledsandbox.companion32",
        "com.warden.controlledsandbox.companion32.debug"):
    if package_name not in queries:
        errors.append(f"manifest package visibility missing: {package_name}")

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/PackageManagementAuthorizationSelfTest.java",
    "different same-UID process reused management capability",
    "second same-UID process replaced live management role",
    "same-UID Guest process reused Runtime capability",
    "same-UID Guest process replaced live Runtime role",
    "package identity lookup failure did not fail closed",
    "Companion without signature permission remained authorized",
    "dead management capability remained active",
    "management generation mismatch was accepted",
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
        errors.append("current static execution receipt omits PackageManagementAuthorizationSelfTest")

report = {
    "task": "M5-T19.1-O2",
    "finding": "NEW-P1-02 mutable process-label role authorization",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "processLabelAuthorization": False,
    "managementAuthorization": "host UID/PID bootstrap plus generation-bound death-linked Binder capability",
    "runtimeAuthorization": "host/companion UID and signature eligibility plus PID-owned Binder capability",
    "legacyTransactionsRetained": True,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-o-caller-uid-identity.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-O2 Binder capability caller authorization", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-O2 caller roles use generation-bound Binder capabilities")
