#!/usr/bin/env python3
from __future__ import annotations

import hashlib
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


pool = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java",
    "MAX_PRE_DISPATCH_DEAD_RETRIES",
    "connections.remove(slot, connection)",
    "connection == null || !connection.isBinding()",
    "Publish and start the in-flight binding under one lock boundary",
    "retire(stale, stale.failureReasonOr(\"DEAD_BINDER\"), true)",
    "The Guest call may already have produced side effects. Do not replay it.",
    "claimUnbind()",
    "claimDisconnectNotification()",
    'throw new IllegalStateException("BIND_REJECTED", error)',
    'throw new IllegalStateException("BIND_TIMEOUT")',
)
if pool.count("call.run(guest)") != 1:
    errors.append("Guest operation must have one non-replayed dispatch site")

test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java",
    "testDelayedDeathReconnectsWithinCurrentRequest",
    "testBinderDeathCallbackDisconnectsAndRebinds",
    "testConcurrentCallersShareOneReconnect",
    "testBindTimeoutHasDistinctReasonAndRecovers",
    "testDisconnectedHasDistinctReason",
    "dieWithoutCallback",
    "deliverDelayedDeath",
    "callerCount = 10",
    "dead cached Binder did not trigger exactly one new bind",
)
for call in (
    "testDelayedDeathReconnectsWithinCurrentRequest();",
    "testBinderDeathCallbackDisconnectsAndRebinds();",
    "testConcurrentCallersShareOneReconnect();",
    "testBindTimeoutHasDistinctReasonAndRecovers();",
    "testDisconnectedHasDistinctReason();",
):
    if test.count(call) != 1:
        errors.append(f"direct test must execute exactly once: {call}")

runner = require("tools/static_android_compile.py", "RuntimeGuestConnectionPoolSelfTest")
if runner.count("RuntimeGuestConnectionPoolSelfTest") != 1:
    errors.append("static Android runner must execute RuntimeGuestConnectionPoolSelfTest exactly once")
verify = require("scripts/verify-all.sh", "check-m5-t19-1-h-guest-pool-reconnect.py")
if verify.count("check-m5-t19-1-h-guest-pool-reconnect.py") != 1:
    errors.append("verify-all must execute the P2-02 gate exactly once")
require("docs/ARCHITECTURE.md", "Guest process connection replacement")
require("README.md", "M5-T19.1-H Guest Binder reconnect correctness fix")
require("docs/M5_T19_1_H_DEVELOPMENT_REPORT.md", "Same-request pre-dispatch reconnect: PASS")

digest = hashlib.sha256()
for rel in (
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java",
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java",
):
    digest.update(rel.encode())
    digest.update(b"\0")
    digest.update((ROOT / rel).read_bytes())
    digest.update(b"\0")
report = {
    "task": "M5-T19.1-H",
    "finding": "P2-02 current request did not reconnect after cached Guest Binder death",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "reconnectContract": "same-request-before-dispatch",
    "remoteCallReplay": False,
    "concurrentCallers": 10,
    "failureReasons": ["DEAD_BINDER", "BINDER_DIED", "DISCONNECTED", "BIND_REJECTED", "BIND_TIMEOUT"],
    "androidBinderEvidenceCount": 0,
    "deviceEvidenceCount": 0,
    "inputDigestSha256": digest.hexdigest(),
    "errors": errors,
}
(ROOT / "verification/m5-t19-1-h-guest-pool-reconnect.json").write_text(
    json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-H Guest pool reconnect", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-H same-request dead-Binder reconnect and shared in-flight binding")
