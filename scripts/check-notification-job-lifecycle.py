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

observer = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "interface IVirtualSystemServiceObserver", "boolean onJobReady")
if "oneway interface" in observer:
    errors.append("Job acknowledgement observer cannot remain oneway")
session = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "reserveNotification", "commitNotification", "listNotifications",
    "upsertNotificationChannel", "reserveJob", "commitJob", "listJobs", "finishJob")
if "Bundle" in session:
    errors.append("M4-T12 system-service lifecycle AIDL must remain typed")
for name in ["VirtualNotificationSnapshot", "VirtualNotificationChannelSnapshot", "VirtualJobSnapshot"]:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name}")
    source = require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
                     "implements Parcelable", "CREATOR")
    if "Bundle" in source:
        errors.append(f"{name} must not depend on Bundle")

store = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "private static final int SCHEMA = 2", "MAX_NOTIFICATIONS_PER_SCOPE", "MAX_JOBS_PER_SCOPE",
    "reserveNotification", "commitNotification", "notificationChannels", "reserveJob",
    "commitJob", "dispatchJob", "observer.onJobReady", "VirtualJobSnapshot.SCHEDULED")
if "if (delivery.observer.onJobReady" not in store:
    errors.append("Job must become RUNNING only after explicit Guest callback acknowledgement")
service = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualJobService.java",
    "extends JobService", "dispatchVirtualJob", "jobFinished", "needsReschedule")
manifest = require("app/src/main/AndroidManifest.xml", ".VirtualJobService", "android.permission.BIND_JOB_SERVICE")
root_aidl = require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
                    "boolean dispatchVirtualJob(int hostJobId)")

interceptor = require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java",
    "cancelAllNotifications", "cancelAllJobs", "state.notifications().reserve", "state.notifications().commit",
    "state.jobs().reserve", "state.jobs().commit", "com.warden.controlledsandbox.VirtualJobService")
for forbidden in ["VIRTUAL_NOTIFICATION_CANCEL_ALL_UNSUPPORTED", "VIRTUAL_JOB_CANCEL_ALL_UNSUPPORTED"]:
    if forbidden in interceptor:
        errors.append(f"obsolete global cancelAll blocker remains: {forbidden}")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "BiFunction<Integer, Object, Boolean>", "session.reserveNotification", "session.reserveJob",
    "onJobReady", "jobReadyListener.apply")
guest = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "virtualServices.jobs().setReadyListener", "JOB_PARAMETERS_BRIDGE_PENDING",
    "return false", "virtualServices.close()")
provider_coordinator = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/RuntimeProviderResourceCoordinator.java",
    "class RuntimeProviderResourceCoordinator", "closeCursorBestEffort", "closeFileBestEffort",
    "recoverSession", "purgeExpired")
broker = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "RuntimeProviderResourceCoordinator providerResources", "providerResources.stopSession",
    "providerResources.recoverSession", "providerResources.invalidateInstance")
for forbidden in ["closeGuestCursorBestEffort", "closeGuestFileBestEffort", "applyProviderCleanup"]:
    if forbidden in broker:
        errors.append(f"RuntimeBrokerService still owns extracted Provider cleanup: {forbidden}")
broker_lines = len(broker.splitlines())
if broker_lines > 1370:
    errors.append(f"RuntimeBrokerService exceeds M4-T12 bound: {broker_lines} lines")

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    "active notification lifecycle must survive Package Service recreation",
    "scheduled job lifecycle must survive Package Service recreation",
    "unacknowledged job must remain SCHEDULED")
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceSelfTest.java",
    "virtual notification cancelAll must cancel only owned host IDs",
    "virtual cancelAll cancels only owned host job IDs")
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/RuntimeProviderResourceCoordinatorSelfTest.java",
    "both Provider capability families must be closed")
runner = text("tools/static_android_compile.py")
for test in ["VirtualSystemServiceStoreSelfTest", "VirtualSystemServiceSelfTest",
             "RuntimeProviderResourceCoordinatorSelfTest"]:
    if runner.count(test) < 1:
        errors.append(f"static Android compiler does not execute {test}")

matrix_path = ROOT / "verification/m3-source-capability-matrix.json"
if matrix_path.is_file():
    ids = {item.get("id") for item in json.loads(matrix_path.read_text(encoding="utf-8")).get("capabilities", [])}
    for expected in ["contract.notification-job-lifecycle", "package.notification-owned-resource-lifecycle",
                     "package.job-durable-spec-state", "runtime.job-callback-acknowledgement",
                     "runtime.provider-resource-coordinator-split"]:
        if expected not in ids:
            errors.append(f"capability matrix missing {expected}")
else:
    errors.append("missing capability matrix")

service_matrix = ROOT / "verification/system-service-coverage-matrix.json"
if service_matrix.is_file():
    services = {item.get("id"): item for item in json.loads(service_matrix.read_text(encoding="utf-8")).get("services", [])}
    notification = services.get("notification", {})
    job = services.get("job-scheduler", {})
    if notification.get("production") != "wired" or "owned-resource" not in notification.get("hook", ""):
        errors.append("notification service matrix must disclose wired owned-resource lifecycle")
    if job.get("production") != "partial" or "host-bridge" not in job.get("hook", ""):
        errors.append("job service matrix must disclose partial host bridge")
else:
    errors.append("missing system service coverage matrix")

if errors:
    print("FAIL M4-T12 Notification/Job lifecycle checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS M4-T12 Notification/Job lifecycle checks (broker lines={broker_lines})")
