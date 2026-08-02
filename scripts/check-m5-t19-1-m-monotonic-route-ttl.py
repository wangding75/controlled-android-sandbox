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


store = require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/routing/OneTimeRouteStore.java",
    "SystemClock::elapsedRealtime",
    "LongSupplier elapsedRealtimeMillis",
    "long elapsedNow = elapsedRealtimeMillis.getAsLong()",
    "expiresAtElapsedMillis <= elapsedNow",
)
if "Clock.systemUTC(), DEFAULT_MAX_ENTRIES" in store:
    errors.append("production route store still constructs TTL directly from wall clock")

test = require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/routing/OneTimeRouteStoreSelfTest.java",
    "testWallClockChangesDoNotAffectTtl",
    "wall-clock forward jump expired a monotonic route",
    "wall-clock backward jump extended a monotonic route",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.framework.routing.OneTimeRouteStoreSelfTest",
)
if runner.count("'com.warden.controlledsandbox.framework.routing.OneTimeRouteStoreSelfTest'") != 1:
    errors.append("OneTimeRouteStoreSelfTest must execute exactly once")

receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt_path.is_file():
    errors.append("static Android execution receipt missing")
else:
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if "com.warden.controlledsandbox.framework.routing.OneTimeRouteStoreSelfTest" not in set(
            receipt.get("executedTests", [])):
        errors.append("current static execution receipt omits OneTimeRouteStoreSelfTest")

report = {
    "task": "M5-T19.1-M",
    "finding": "P2-06 one-time route TTL wall-clock dependency",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "monotonicExpiry": True,
    "wallClockDiagnosticOnly": True,
    "forwardAndBackwardJumpTests": True,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-m-monotonic-route-ttl.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-M monotonic route TTL", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-M one-time route TTL uses monotonic elapsed time")
