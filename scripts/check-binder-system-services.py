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

root_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
    "IVirtualSystemServiceSession openVirtualSystemServiceSession(",
    "int virtualUid", "String processName", "long generation", "String packageRevision")
session_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "byte[] getClipboard()", "List<VirtualAccountSnapshot> listAccounts",
    "reservePendingIntent", "listPendingIntents", "void scheduleAlarm", "int ensureNamespace", "reserveNotification", "reserveJob", "void close()")
observer_aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "interface IVirtualSystemServiceObserver", "onClipboardChanged", "onAlarm", "boolean onJobStart", "boolean onJobStop")
for aidl in [root_aidl, session_aidl, observer_aidl]:
    if "Bundle" in aidl:
        errors.append("M4-T11 virtual system-service AIDL must not use Bundle")
for name in ["VirtualAccountSnapshot", "VirtualAlarmSnapshot", "VirtualPendingIntentSnapshot", "VirtualNotificationSnapshot", "VirtualNotificationChannelSnapshot", "VirtualJobSnapshot"]:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name}")
    source = require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
                     "implements Parcelable", "CREATOR")
    if "Bundle" in source:
        errors.append(f"{name} must remain a typed Parcelable without Bundle")

store = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    '"sandbox-system-services.json"', "class VirtualSystemServiceStore",
    "ownerProcessName", "ownerGeneration", "deleteScopeBestEffort",
    "VirtualSystemServiceLimits", "ensureNamespace",
    'observerDispatcher.dispatch("CLIPBOARD"')
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceObserverDispatcher.java",
    "Best-effort observer delivery", "scheduler.execute",
    "VIRTUAL_OBSERVER_DISPATCH_FAILED")
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceLimits.java",
    "MAX_PAYLOAD_BYTES", "MAX_ACCOUNTS_PER_SCOPE", "MAX_PENDING_INTENTS_PER_SCOPE",
    "MAX_ALARMS_PER_SCOPE", "MAX_NAMESPACE_MAPPINGS", "MAX_NOTIFICATIONS_PER_SCOPE",
    "MAX_JOBS_PER_SCOPE", "RETRY_WITHOUT_CLIENT_MS")
if "client.processName().equals(alarm.ownerProcessName)" not in store:
    errors.append("alarm delivery must be bound to the owning virtual process")
if "client.generation() == alarm.ownerGeneration" not in store:
    errors.append("alarm delivery must be bound to the owning Runtime generation")

service = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
    "PackageServiceDependencies", "PackageVirtualSystemServiceSession",
    "openVirtualSystemServiceSession(", "dependencies.close()")
virtual_session = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java",
    "VirtualSystemServiceStore.Client", "requireCapability()", "processName", "generation")
for token in ("callerVerifier.requireRuntimeBrokerCaller()", "VIRTUAL_SYSTEM_SERVICE_SCOPE_NOT_INSTALLED",
              "systemServices.reserveClientRegistration(session)",
              "session.linkClientDeathAfterReservation()",
              "systemServices.commitClientRegistration(session)"):
    if token not in service:
        errors.append(f"PackageManagementService missing scoped capability evidence: {token}")
dependencies = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceDependencies.java",
    "VirtualSystemServiceStore systemServices", "deleteScopeBestEffort",
    "maintenanceWarning()")

require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualSystemServiceAuthority.java",
    "interface VirtualSystemServiceAuthority", "AccountRecord", "AlarmRecord", "NamespaceMapping")
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualSystemServiceState.java",
    "VirtualSystemServiceState(VirtualSystemServiceAuthority authority)",
    "binderOwned()", "authority.scheduleAlarm", "authority.ensureNamespace")
interceptor = require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java",
    '"mChannelId"', '"mShortcutId"', '"mGroup"', "passThroughLifecycle")
for token in ["cancelAllNotifications", "cancelAllJobs"]:
    if token not in interceptor:
        errors.append(f"bounded owned-resource cleanup missing: {token}")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeSystemServiceCoordinator.java",
    "class RuntimeSystemServiceCoordinator", "guest.processName()",
    "RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER", "closeCapability(removed)",
    "capability.session().close()")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeVirtualSystemServicePackageClient.java",
    "openVirtualSystemServiceSession", "String processName", "generation")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "class RemoteVirtualSystemServiceAuthority", "Parcel.obtain()", "session.registerObserver",
    "alarmDeliveries", "alarmDeliveries.remove(alarmId)", "session.ensureNamespace", "session.reserveNotification", "session.reserveJob")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "spec.virtualSystemServiceBinder", "RemoteVirtualSystemServiceAuthority",
    "VIRTUAL_SYSTEM_SERVICE_CAPABILITY_INVALID")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java",
    "virtualSystemServiceBinder", "RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER")
broker = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "RuntimeSystemServiceCoordinator systemServiceCoordinator",
    "systemServiceCoordinator.attach(session, spec)", "systemServiceCoordinator.stop")
broker_lines = len(broker.splitlines())
if broker_lines > 1450:
    errors.append(f"RuntimeBrokerService exceeds bounded M4-T11 limit: {broker_lines} lines")

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    "clipboard must be shared", "accounts must be isolated by virtual user",
    "other guest processes must not receive owner alarms",
    "oversized clipboard payload must fail closed",
    "active notification lifecycle must survive Package Service recreation",
    "scheduled job lifecycle must survive Package Service recreation",
    "instance deletion must clear shared system-service state")
contract_test = require(
    "app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java",
    "virtual account snapshot lost", "virtual alarm snapshot lost")
runner = text("tools/static_android_compile.py")
for test in ["VirtualSystemServiceStoreSelfTest", "PackageServiceContractSelfTest"]:
    if runner.count(test) < 1:
        errors.append(f"static Android compiler does not execute {test}")

matrix_path = ROOT / "verification/system-service-coverage-matrix.json"
if not matrix_path.is_file():
    errors.append("missing system service coverage matrix")
else:
    payload = json.loads(matrix_path.read_text(encoding="utf-8"))
    services = {item.get("id"): item for item in payload.get("services", [])}
    expected = {
        "alarm": ("binder-owned", "package-user-process-generation"),
        "clipboard": ("binder-owned", "package-user"),
        "account": ("binder-owned", "package-user"),
        "notification": ("persistent", "package-user"),
        "job-scheduler": ("persistent", "package-user"),
    }
    for service_id, (hook_token, isolation_token) in expected.items():
        item = services.get(service_id)
        if item is None:
            errors.append(f"system service matrix missing {service_id}")
            continue
        if hook_token not in item.get("hook", ""):
            errors.append(f"system service {service_id} hook must disclose {hook_token}")
        if isolation_token not in item.get("isolation", ""):
            errors.append(f"system service {service_id} isolation must disclose {isolation_token}")
        if item.get("device") != "not-tested":
            errors.append(f"M4-T11 must not claim device evidence for {service_id}")

if errors:
    print("FAIL M4-T11 Binder-owned system-service checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS M4-T11 Binder-owned system-service checks (broker lines={broker_lines})")
