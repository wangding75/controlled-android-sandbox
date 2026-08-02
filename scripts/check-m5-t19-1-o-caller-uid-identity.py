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
    "RUNTIME_PERMISSION_CALLER_NOT_COMPANION_BROKER_PROCESS",
    'new FileInputStream("/proc/" + pid + "/cmdline")',
    "MAX_PROCESS_NAME_BYTES = 512",
)
for forbidden in ("ActivityManager", "getRunningAppProcesses"):
    if forbidden in verifier:
        errors.append(f"PackageCallerVerifier retains process-list dependency: {forbidden}")

require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/RuntimePeerIdentity.java",
    "SIGNATURE_PERMISSION",
    "COMPANION_RELEASE_PACKAGE",
    "COMPANION_DEBUG_PACKAGE",
)

policy = require(
    "app/src/main/java/com/warden/controlledsandbox/ManagementCallerPolicy.java",
    "isExpectedProcess",
    "isTrustedCompanionProcess",
    "expectedProcessName.equals(actualProcessName)",
)
if "ProcessIdentity" in policy:
    errors.append("ManagementCallerPolicy retains process-name identities")

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
    "same-UID Guest process must not mint management capability",
    "other Companion process must be rejected",
    "package identity lookup failure must fail closed",
    "different process must not reuse",
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
    "task": "M5-T19.1-O",
    "finding": "P2-08 CallerVerifier depends on process-list visibility",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "activityManagerProcessListDependency": False,
    "hostAuthorization": "Host UID plus exact caller PID cmdline plus per-session owner PID",
    "companionAuthorization": "signature permission plus installed Companion UID plus exact broker PID cmdline",
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-o-caller-uid-identity.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-O caller UID identity", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-O caller authorization uses stable UID/package identity")
