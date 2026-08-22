#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *tokens: str) -> str:
    content = text(relative)
    for token in tokens:
        if token not in content:
            errors.append(f"{relative} missing evidence: {token}")
    return content

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualSystemServiceState.java",
        "class VirtualSystemServiceState", "class ClipboardState", "class AccountState",
        "class AlarmState", "class IntNamespace", "ScheduledExecutorService")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java",
        'case "clipboard"', 'case "account"', 'case "alarm"',
        'case "notification"', 'case "jobscheduler"',
        "cancelAllJobs", "cancelAllNotifications")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        "AlarmManagerHook.install(hostServiceContext, identity)",
        "ClipboardManagerHook.install(hostServiceContext, identity)",
        "AccountManagerHook.install(hostServiceContext, identity)")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistry.java",
        "FLAG_ONE_SHOT", "FLAG_NO_CREATE", "FLAG_CANCEL_CURRENT", "FLAG_UPDATE_CURRENT",
        "packageName", "virtualUserId", "generation", "cancelAll()")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptor.java",
        "getintentsender", "cancelintentsender", "sendintentsender",
        "getpackageforintentsender", "getuidforintentsender")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPendingIntentDispatcher.java",
        "RouteBrokerClient.launchActivity", "RouteBrokerClient.invokeComponent",
        "resolver().resolveOne", "pendingIntentCreatorPackage")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestIntentResolver.java",
        "Target resolveOne(", "packageManager.resolveActivity", "queryBroadcastReceivers")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
        "CROSS_PACKAGE_TARGET_NOT_INSTALLED", "requireAccessibleCrossPackageComponent",
        "CALLER_PACKAGE_NAME")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestFrameworkCallRouter.java",
        "OrderedReceiverFinishInterceptor", "PendingIntentFrameworkInterceptor")
coordinator = require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeReceiverCoordinator.java",
        "class RuntimeReceiverCoordinator", "dispatchManifestBroadcast(",
        "dispatchImplicitManifestBroadcast(", "dispatchDynamicBroadcast(",
        "IOrderedReceiverCompletion.Stub", "ReceiverLifecycleCoordinator")
component_coordinator = require(
        "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeComponentOperationCoordinator.java",
        "class RuntimeComponentOperationCoordinator", "RuntimeReceiverCoordinator receiverCoordinator",
        "receiverCoordinator.dispatchManifestBroadcast(",
        "receiverCoordinator.dispatchImplicitManifestBroadcast(",
        "receiverCoordinator.dispatchDynamicBroadcast(")
broker_path = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java"
broker = text(str(broker_path.relative_to(ROOT)))
for token in ["RuntimeReceiverCoordinator receiverCoordinator",
              "RuntimeComponentOperationCoordinator componentOperationCoordinator"]:
    if token not in broker:
        errors.append(f"RuntimeBrokerService missing component/receiver coordinator ownership: {token}")
for token in ["receiverCoordinator.dispatchManifestBroadcast(",
              "receiverCoordinator.dispatchImplicitManifestBroadcast(",
              "receiverCoordinator.dispatchDynamicBroadcast("]:
    if token in broker:
        errors.append(f"RuntimeBrokerService must not own Receiver dispatch: {token}")
for forbidden in ["BrokerManifestReceiverRuntime", "BrokerOrderedReceiverRuntime",
                  "ManifestBroadcastDispatcher", "IOrderedReceiverCompletion.Stub"]:
    if forbidden in broker:
        errors.append(f"RuntimeBrokerService still owns Receiver implementation: {forbidden}")
line_count = len(broker.splitlines())
if line_count > 1450:
    errors.append(f"RuntimeBrokerService remains too large after M4-T10 split: {line_count} lines")
if len(coordinator.splitlines()) < 250:
    errors.append("RuntimeReceiverCoordinator does not contain the extracted Receiver authority")

require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceSelfTest.java",
        "clipboard isolated by Guest identity", "host accounts remain hidden",
        "virtual alarm delivered in process", "failed notification does not leak namespace mapping",
        "virtual cancelAll cancels only owned host job IDs")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistrySelfTest.java",
        "equivalent sender reuses stable token", "one-shot sender cancelled after send")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptorSelfTest.java",
        "sender package remains Guest identity", "sender UID remains virtual")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeReceiverCoordinatorSelfTest.java",
        "Guest delivery delegated exactly once", "session stop cleans Receiver registrations")
runner = text("tools/static_android_compile.py")
for test in ["VirtualSystemServiceSelfTest", "VirtualPendingIntentRegistrySelfTest",
             "PendingIntentFrameworkInterceptorSelfTest", "RuntimeReceiverCoordinatorSelfTest"]:
    if runner.count(test) < 1:
        errors.append(f"static Android compiler does not execute {test}")

matrix_path = ROOT / "verification/system-service-coverage-matrix.json"
if not matrix_path.is_file():
    errors.append("missing system service coverage matrix")
else:
    payload = json.loads(matrix_path.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != 1:
        errors.append("system service coverage matrix schemaVersion must be 1")
    services = payload.get("services", [])
    ids = [item.get("id", "") for item in services]
    expected = {"package-manager", "activity-manager", "activity-task-manager", "appops",
                "permission", "camera", "location", "audio", "alarm", "clipboard",
                "account", "notification", "job-scheduler", "pending-intent", "storage"}
    if set(ids) != expected or len(ids) != len(set(ids)):
        errors.append("system service coverage matrix has missing or duplicate service rows")
    for item in services:
        for key in ["hook", "isolation", "production", "hostFallback", "device"]:
            if not item.get(key):
                errors.append(f"system service {item.get('id')} missing {key}")
        if item.get("device") != "not-tested":
            errors.append(f"M4-T10 must not claim device evidence for {item.get('id')}")

if errors:
    print("FAIL M4-T10 system service and Broker split checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS M4-T10 system service and Broker split checks (broker lines={line_count})")
