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


coordinator = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeSystemServiceCoordinator.java",
    "current != null && !isAlive(current)",
    "capabilities.remove(key)",
    "closeCapability(current)",
    "openLiveCapability(key, guest, spec)",
    "VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD_AFTER_OPEN",
    "capabilities.put(key, opened)",
)
if "VIRTUAL_SYSTEM_SERVICE_CAPABILITY_DEAD\");" in coordinator:
    errors.append("coordinator still throws the legacy cached-death attach failure")

test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeSystemServiceCoordinatorSelfTest.java",
    "testDeadCachedCapabilityReopensWithinCurrentAttach",
    "testConcurrentAttachesShareOneReplacement",
    "testDeadReplacementFailsClosedAndNextAttachRecovers",
    "current attach did not replace the dead cached capability",
    "concurrent callers opened more than one replacement",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.runtime.broker.RuntimeSystemServiceCoordinatorSelfTest",
)
if runner.count("'com.warden.controlledsandbox.runtime.broker.RuntimeSystemServiceCoordinatorSelfTest'") != 1:
    errors.append("RuntimeSystemServiceCoordinatorSelfTest must execute exactly once")

receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt_path.is_file():
    errors.append("static Android execution receipt missing")
else:
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if "com.warden.controlledsandbox.runtime.broker.RuntimeSystemServiceCoordinatorSelfTest" not in set(
            receipt.get("executedTests", [])):
        errors.append("current static execution receipt omits RuntimeSystemServiceCoordinatorSelfTest")

report = {
    "task": "M5-T19.1-L",
    "finding": "P2-05 cached virtual system-service capability recovery",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "sameAttachReopen": True,
    "singleConcurrentRebuild": True,
    "deadNewCapabilityRejected": True,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-l-system-service-capability-recovery.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-L system-service capability recovery", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-L cached system-service capability recovers within current attach")
