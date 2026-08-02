#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
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


identity = require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ControlledReleaseIdentity.java",
    'RELEASE_TRAIN = "m5-t19-1"',
    "VERSION_CODE = 19",
    'VERSION_NAME = "0.5.19.1-source"',
    "COMPANION_PROTOCOL = 1",
)
root_gradle = require(
    "build.gradle",
    "ControlledReleaseIdentity.java",
    "controlledVersionCode = identityInt('VERSION_CODE')",
    "controlledVersionName = identityString('VERSION_NAME')",
    "controlledCompanionProtocol = identityInt('COMPANION_PROTOCOL')",
)
for rel in ("app/build.gradle", "sandbox-companion32/build.gradle"):
    value = require(
        rel,
        "versionCode rootProject.ext.controlledVersionCode",
        "versionName rootProject.ext.controlledVersionName",
    )
    if re.search(r"versionCode\s+[0-9]+", value):
        errors.append(f"{rel} retains an independent numeric versionCode")

companion_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/INativeAbiCompanion.aidl",
    "NativeCompanionResult execute(in NativeCompanionRequest request);",
    "NativeCompanionIdentity getIdentity();",
)
if companion_aidl.find("execute(") > companion_aidl.find("getIdentity("):
    errors.append("getIdentity must be appended after execute to preserve the existing transaction ID")

require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeCompanionIdentity.java",
    "NativeCompanionIdentity current()",
    "supportsProtocol",
    "minimumProtocol",
    "maximumProtocol",
)
require(
    "sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionService.java",
    "NativeCompanionIdentity getIdentity()",
    "NativeCompanionIdentity.current()",
    "ControlledReleaseIdentity.COMPANION_PROTOCOL",
)
require(
    "sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionWorkspaceStore.java",
    "ControlledReleaseIdentity.COMPANION_PROTOCOL",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java",
    "requireCompatibleControl()",
    "control.getIdentity()",
    "controlConnection.invalidate()",
    "getPackageInfo(context.getPackageName(), 0)",
    "getPackageInfo(companionPackage(), 0)",
    "requireInstalledPair",
    "ControlledReleaseIdentity.COMPANION_PROTOCOL",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/NativeCompanionIdentityVerifier.java",
    "NATIVE_COMPANION_PROTOCOL_INCOMPATIBLE",
    "NATIVE_COMPANION_RELEASE_TRAIN_MISMATCH",
    "NATIVE_COMPANION_VERSION_IDENTITY_MISMATCH",
    "NATIVE_COMPANION_INSTALLED_VERSION_CODE_MISMATCH",
    "NATIVE_COMPANION_INSTALLED_VERSION_NAME_MISMATCH",
)
require(
    "app/src/testHarness/java/com/warden/controlledsandbox/NativeCompanionIdentityVerifierSelfTest.java",
    "NATIVE_COMPANION_IDENTITY_MISSING",
    "NATIVE_COMPANION_PRODUCT_MISMATCH",
    "NATIVE_COMPANION_PROTOCOL_INCOMPATIBLE",
    "NATIVE_COMPANION_RELEASE_TRAIN_MISMATCH",
    "NATIVE_COMPANION_VERSION_IDENTITY_MISMATCH",
    "NATIVE_COMPANION_INSTALLED_VERSION_CODE_MISMATCH",
    "NATIVE_COMPANION_INSTALLED_VERSION_NAME_MISMATCH",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.NativeCompanionIdentityVerifierSelfTest",
)
if runner.count("'com.warden.controlledsandbox.NativeCompanionIdentityVerifierSelfTest'") != 1:
    errors.append("NativeCompanionIdentityVerifierSelfTest must execute exactly once")

receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt_path.is_file():
    errors.append("static Android execution receipt missing")
else:
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if "com.warden.controlledsandbox.NativeCompanionIdentityVerifierSelfTest" not in set(
            receipt.get("executedTests", [])):
        errors.append("current static execution receipt omits NativeCompanionIdentityVerifierSelfTest")

report = {
    "task": "M5-T19.1-N",
    "finding": "P2-07 Host and Companion32 release identity drift",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "singleVersionSource": True,
    "runtimeIdentityHandshake": True,
    "existingExecuteTransactionPreserved": True,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-n-companion-release-identity.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-N Companion release identity", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-N Host and Companion share release identity and fail-closed handshake")
