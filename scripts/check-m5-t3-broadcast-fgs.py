#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")

def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value

plan = require("docs/plans/M5_T3_DEVELOPMENT_PLAN.md", "Ordered Broadcast", "PendingResult", "Foreground Service")
report = require("docs/M5_T3_DEVELOPMENT_REPORT.md", "Source status: PASS", "Device evidence: 0")
comparison = require("docs/comparisons/M5_T3_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox", "device")
readme = require("README.md", "## M5-T3 ordered broadcast and foreground-service source baseline")

require("sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/service/ForegroundServiceStateMachine.java",
        "DEFAULT_PROMOTION_TIMEOUT_MS", "MAX_PROMOTION_TIMEOUT_MS", "BACKGROUND_START_NOT_ALLOWED",
        "FOREGROUND_SERVICE_TYPE_NOT_DECLARED", "notificationId", "promotionDeadlineMs")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/service/BrokerServiceRuntime.java",
        "startForegroundRequested", "promoteForeground", "demoteForeground", "expireForeground")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeServiceCoordinator.java",
        "purgeExpiredForeground", "START_FOREGROUND_SERVICE", "PROCESS_RECOVERY")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestComponentRuntime.java",
        "ForegroundServiceStateMachine", "START_FOREGROUND_SERVICE", "SET_SERVICE_FOREGROUND")
require("app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
        "backgroundStartAllowed", "exemptionReason", "declaredTypeMask", "promotionTimeoutMs")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/ManifestBroadcastDispatcher.java",
        "DEFAULT_CHAIN_TIMEOUT_MS", "MAX_CHAIN_TIMEOUT_MS", "CHAIN_TIMEOUT", "skippedCount", "timedOutCount", "abortSource")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeReceiverCoordinator.java",
        "BROADCAST_CHAIN_TIMEOUT_MS", "BROADCAST_TIMED_OUT_COUNT", "BROADCAST_TERMINAL_REASON")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java",
        "linkToDeath", "unlinkToDeath", "validateResultPayload", "completionBinderDied")

for rel, tokens in {
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/service/BrokerServiceRuntimeSelfTest.java":
        ("foreground promotion timeout was not expired", "background foreground-service start was not rejected"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeServiceCoordinatorSelfTest.java":
        ("foreground recovery", "foreground timeout"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/receiver/ManifestBroadcastDispatcherSelfTest.java":
        ("chain-wide timeout budget", "policy abort"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridgeSelfTest.java":
        ("Broker completion Binder death", "invalid ordered result extras"),
}.items():
    require(rel, *tokens)

try:
    preflight = json.loads(text("verification/m5-t3-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T3 source status must be pass")
    if preflight.get("androidBuildStatus") != "blocked-toolchain": errors.append("Android build must remain blocked-toolchain")
    for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
        if preflight.get("deviceEvidence", {}).get(field) != 0:
            errors.append(f"device {field} must remain zero")
    expected = {"receiver.ordered-source-model", "receiver.ordered-pending-result-bridge", "runtime.foreground-service-state-model"}
    if set(preflight.get("resolvedM4ProductionIds", [])) != expected:
        errors.append("M5-T3 resolved capability set is incorrect")
except Exception as exc:
    errors.append(f"invalid M5-T3 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    by_id = {item.get("id"): item for item in matrix.get("capabilities", [])}
    for capability in ("receiver.ordered-source-model", "receiver.ordered-pending-result-bridge", "runtime.foreground-service-state-model"):
        item = by_id.get(capability, {})
        if item.get("sourceStatus") != "complete" or item.get("productionStatus") != "wired":
            errors.append(f"{capability} must be source complete and production wired")
        if item.get("deviceStatus") != "not-tested":
            errors.append(f"{capability} device status must remain not-tested")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

runner = text("tools/static_android_compile.py")
for test in ("BrokerServiceRuntimeSelfTest", "RuntimeServiceCoordinatorSelfTest", "ManifestBroadcastDispatcherSelfTest", "OrderedReceiverPendingResultBridgeSelfTest"):
    if test not in runner: errors.append(f"static compiler does not run {test}")

if errors:
    print("FAIL M5-T3 ordered broadcast and foreground-service checks", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T3 ordered broadcast and foreground-service checks: source wired; build/device evidence remains blocked/not-tested")
