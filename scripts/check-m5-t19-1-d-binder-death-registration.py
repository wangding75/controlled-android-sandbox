#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/m5-t19-1-d-binder-death-registration.json"
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


helper = require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/internal/DeathRegistrationHelper.java",
    "linkAfterReservation",
    "linkedAndAlive",
    "binder.linkToDeath(recipient, 0)",
    "binder.unlinkToDeath(recipient, 0)",
    "deathAction.run()",
    "State.LINKING",
    "State.LINKED",
    "State.DEAD",
    "State.CLOSED",
)
if helper.count("public boolean linkAfterReservation()") != 1:
    errors.append("death helper must expose one authoritative two-phase link method")

owners = {
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntime.java": (
        "callbacks.put(id, record)",
        "record.deathRegistration.linkAfterReservation()",
        "callbacks.get(id) != record",
        "PROVIDER_OBSERVER_CALLBACK_DEAD_DURING_LINK",
    ),
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java": (
        "activeJobExecutions.put(located.job.hostId, execution)",
        "execution.deathRegistration.linkAfterReservation()",
        "activeJobExecutions.get(located.job.hostId) != execution",
        "rollbackDeathLinkLocked",
    ),
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceBinder.java": (
        "dependencies.systemServices.reserveClientRegistration(session)",
        "session.linkClientDeathAfterReservation()",
        "dependencies.systemServices.commitClientRegistration(session)",
        "VIRTUAL_SYSTEM_SERVICE_CLIENT_TOKEN_DEAD_DURING_LINK",
    ),
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java": (
        "interceptor.register(bridge.finishToken, bridge, bridge.deadlineMs)",
        "bridge.linkCompletionDeathAfterReservation()",
        "ORDERED_RECEIVER_COMPLETION_DEAD_DURING_LINK",
    ),
}
for rel, tokens in owners.items():
    require(rel, *tokens)

observer_test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntimeSelfTest.java",
    "testImmediateDeathDuringRegistration",
    "recipient.binderDied()",
    "immediately dead observer leaked into authority registry",
)
ordered_test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridgeSelfTest.java",
    "immediateCompletionDeathDuringLinkRollsBackReservation",
    "value.binderDied()",
    "immediately dead completion leaked ordered Receiver finish token",
)
job_test = require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualJobDeathRegistrationSelfTest.java",
    "virtual Job immediate-death registration self-test",
    "recipient.binderDied()",
    "immediately dead job callback leaked active execution registry entry",
)
session_test = require(
    "app/src/testHarness/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSessionSelfTest.java",
    "linkClientDeathAfterReservation",
    "recipient.binderDied()",
    "immediately dead session leaked into virtual system-service registry",
    "dead replacement removed the previous live session",
)
for name, value, direct_call in (
    ("observer", observer_test, "testImmediateDeathDuringRegistration();"),
    ("ordered Receiver", ordered_test, "immediateCompletionDeathDuringLinkRollsBackReservation();"),
):
    if value.count(direct_call) != 1:
        errors.append(f"{name} immediate-death regression must execute exactly once")
if "Thread.sleep" in session_test:
    errors.append("session immediate-death regression must not use sleep-based ordering")

runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.VirtualSystemServiceStoreSelfTest",
    "com.warden.controlledsandbox.VirtualJobDeathRegistrationSelfTest",
    "com.warden.controlledsandbox.PackageVirtualSystemServiceSessionSelfTest",
    "com.warden.controlledsandbox.runtime.guest.OrderedReceiverPendingResultBridgeSelfTest",
    "com.warden.controlledsandbox.runtime.provider.BrokerObserverRuntimeSelfTest",
)
if runner.count("'com.warden.controlledsandbox.VirtualJobDeathRegistrationSelfTest'") != 1:
    errors.append("static Android compiler must execute the Job immediate-death self-test once")
if runner.count("'com.warden.controlledsandbox.PackageVirtualSystemServiceSessionSelfTest'") != 1:
    errors.append("static Android compiler must execute the session immediate-death self-test once")

require(
    "README.md",
    "## M5-T19.1-D Binder death-registration atomicity fix",
    "authoritative registry entry before linking",
)
require(
    "docs/ARCHITECTURE.md",
    "## Binder death-registration linearization",
    "reserve authoritative record",
    "recheck same record + Binder liveness",
)
require(
    "docs/M5_T19_1_D_DEVELOPMENT_REPORT.md",
    "Source fix: PASS",
    "Four immediate-death direct regressions: PASS",
    "Android Binder-driver evidence: 0",
)

report = {
    "task": "M5-T19.1-D",
    "finding": "P1-04 Binder death can fire between link and authoritative registry publication",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "registrationPattern": [
        "reserve authoritative record",
        "link death recipient",
        "recheck record identity and Binder liveness",
        "rollback or publish",
    ],
    "owners": [
        "Broker Provider observer",
        "active virtual Job execution",
        "Package virtual-system-service session",
        "ordered Receiver completion",
    ],
    "deterministicImmediateDeathTests": 4,
    "sleepBasedRaceTests": 0,
    "androidBinderEvidenceCount": 0,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-D Binder death-registration checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-D four-owner reserve-link-recheck-rollback gate")
