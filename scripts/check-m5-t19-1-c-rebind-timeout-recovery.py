#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/m5-t19-1-c-rebind-timeout-recovery.json"
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


connector = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnector.java",
    'recordFailureLocked("BIND_TIMEOUT", null)',
    "timeoutAttempt(waiting)",
    "attempt = null",
    "target.latch.countDown()",
    "target.bound = false",
    "target.unbindClaimed = true",
    "deadlineNanos",
    "Math.min(remaining, attemptRemaining)",
)
if "!waiting.latch.await(waitNanos, TimeUnit.NANOSECONDS)" not in connector:
    errors.append("connector must apply timeout to the current binding wait")
if connector.count('recordFailureLocked("BIND_TIMEOUT", null)') != 1:
    errors.append("connector must record BIND_TIMEOUT through one authoritative path")
if "synchronized (lock) {\n            if (target == null" not in connector:
    errors.append("unbind ownership must be serialized through the connector lock")

self_test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
    "timesOutMissingCallbackAndRebinds",
    "timeoutAfterBackoffCancelsAttempt",
    "ignoresLateConnectionAfterTimeout",
    "closeReleasesWaitingRequire",
    "closeDuringSynchronousCallbackDoesNotRearmBinding",
    "usesBoundedExponentialBackoff",
    "missing callback must report BIND_TIMEOUT",
    "timed-out binding must be safely unbound",
    "a later require must start a fresh binding attempt",
    "late callback capability must be closed instead of published",
    "close must release the waiter with a closed-connector failure",
    "bindService return must not re-arm an already unbound connection",
    "third retry must honor the 40 ms cap",
)
for direct_call in (
    "timesOutMissingCallbackAndRebinds();",
    "timeoutAfterBackoffCancelsAttempt();",
    "ignoresLateConnectionAfterTimeout();",
    "closeReleasesWaitingRequire();",
    "closeDuringSynchronousCallbackDoesNotRearmBinding();",
    "usesBoundedExponentialBackoff();",
):
    if self_test.count(direct_call) != 1:
        errors.append(f"connector self-test must directly execute exactly once: {direct_call}")

runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnectorSelfTest",
)
if runner.count("'com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnectorSelfTest'") != 1:
    errors.append("static Android compiler must execute RebindableServiceConnectorSelfTest exactly once")

require(
    "docs/ARCHITECTURE.md",
    "BIND_TIMEOUT",
    "late `onServiceConnected`",
    "bounded exponential backoff",
)
require(
    "README.md",
    "## M5-T19.1-C Binder binding-timeout recovery fix",
    "`bindService` returns `true` but no callback arrives",
)
require(
    "docs/M5_T19_1_C_DEVELOPMENT_REPORT.md",
    "Source fix: PASS",
    "No-callback timeout recovery: PASS",
    "Android Binder/device evidence: 0",
)

report = {
    "task": "M5-T19.1-C",
    "finding": "P1-03 RebindableServiceConnector remains permanently binding when bindService returns true without a callback",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "timeoutFailure": "BIND_TIMEOUT",
    "timeoutActions": [
        "mark attempt complete",
        "clear authoritative current attempt",
        "record bounded retry failure",
        "release all waiters",
        "claim and unbind the Android ServiceConnection once",
    ],
    "regressions": [
        "no callback timeout and later rebind",
        "request timeout after retry backoff",
        "late onServiceConnected after timeout",
        "close while require is waiting",
        "close after synchronous callback but before bindService return",
        "10/20/40 ms bounded exponential retry sequence",
        "existing Binder-death recovery",
    ],
    "androidBinderEvidenceCount": 0,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-C Binder binding-timeout recovery checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-C no-callback timeout, stale callback, close race and retry-backoff gate")
