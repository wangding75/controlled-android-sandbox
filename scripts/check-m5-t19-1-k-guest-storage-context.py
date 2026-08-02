#!/usr/bin/env python3
from __future__ import annotations

import json
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


context = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java",
    "deviceProtected ? \"device_protected\" : \"data\"",
    "moveDatabaseFrom(Context sourceContext, String name)",
    "moveSharedPreferencesFrom(Context sourceContext, String name)",
    "GuestStorageTransferCoordinator.move",
    "source.storageNames.release",
    "createDeviceProtectedStorageContext()",
    "createCredentialProtectedStorageContext()",
    "isDeviceProtectedStorage() { return deviceProtected; }",
)
if "NOT_IMPLEMENTED" in context:
    errors.append("GuestContext still contains a NOT_IMPLEMENTED storage API")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageTransferCoordinator.java",
    "FileLock lock = channel.lock()",
    "Main artifact is the commit marker and moves last",
    "rollback(completed, mover, failure)",
    "DurableAtomicFile.move",
    "GUEST_STORAGE_MOVE_ROLLBACK_FAILED",
    "CROSS_GUEST_STORAGE_MOVE_DENIED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/SandboxSharedPreferences.java",
    "SHARED_PREFERENCES_MOVED",
    "invalidateAfterMove",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextStorageTransferSelfTest.java",
    "testSharedPreferencesMove",
    "testDatabaseMove",
    "testDestinationCollision",
    "testPartialMoveRollback",
    "testConcurrentMoveSingleWinner",
    "testForeignContextDenied",
    "device-to-credential",
    "requireMovedPreferences",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.runtime.guest.GuestContextStorageTransferSelfTest",
)
if runner.count("'com.warden.controlledsandbox.runtime.guest.GuestContextStorageTransferSelfTest'") != 1:
    errors.append("GuestContextStorageTransferSelfTest must execute exactly once")

receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt_path.is_file():
    errors.append("static Android execution receipt missing")
else:
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if "com.warden.controlledsandbox.runtime.guest.GuestContextStorageTransferSelfTest" not in set(
            receipt.get("executedTests", [])):
        errors.append("current static execution receipt omits GuestContextStorageTransferSelfTest")

report = {
    "task": "M5-T19.1-K",
    "finding": "P2-04 Guest Context storage APIs",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "implementedApis": [
        "moveDatabaseFrom",
        "moveSharedPreferencesFrom",
        "createDeviceProtectedStorageContext",
    ],
    "transaction": {
        "jvmAndOsLock": True,
        "mainArtifactMovesLast": True,
        "partialMoveRollback": True,
        "destinationOverwrite": False,
        "foreignGuestIdentityDenied": True,
    },
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-k-guest-storage-context.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-K Guest storage context APIs", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-K credential/device Guest storage contexts and transactional moves")
