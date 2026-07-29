#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def require(path, *tokens):
    p = ROOT / path
    if not p.is_file():
        errors.append(f"missing {path}")
        return ""
    text = p.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{path} missing evidence: {token}")
    return text

session = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "VirtualJobSnapshot reserveJob(in VirtualJobSnapshot candidate);", "List<VirtualJobSnapshot> listJobs();")
if "reserveJob(int guestId, in byte[] payload)" in session:
    errors.append("obsolete opaque Job reservation contract remains")

snapshot = require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualJobSnapshot.java",
    "String packageRevision", "requiredNetworkType", "requiresCharging", "requiresBatteryNotLow",
    "requiresStorageNotLow", "requiresDeviceIdle", "boolean periodic", "minimumLatencyMs",
    "overrideDeadlineMs", "boolean expedited", "boolean persisted", "BACKOFF_LINEAR",
    "BACKOFF_EXPONENTIAL", "failureCount", "nextRunAtMs", "lastFailureAtMs")
if "Bundle" in snapshot:
    errors.append("typed Job policy snapshot must not depend on Bundle")

require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualJobPolicySnapshotFactory.java",
    "getNetworkType", "isRequireCharging", "isRequireBatteryNotLow", "isRequireStorageNotLow",
    "isRequireDeviceIdle", "getIntervalMillis", "getFlexMillis", "getMinLatencyMillis",
    "getMaxExecutionDelayMillis", "isExpedited", "isPersisted", "getBackoffPolicy",
    "getInitialBackoffMillis")
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java",
    "VirtualJobPolicySnapshotFactory.from", "state.jobs().reserve", "rewriteJobService")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "session.reserveJob(snapshot)", "candidate.packageRevision()", "value.failureCount()")
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
    "reserveJob(VirtualJobSnapshot candidate)", "packageRevision, candidate",
    "jobs(scope, processName, generation, packageRevision)")
store = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "private static final int SCHEMA = 5", "pruneJobRevisionLocked", "retryDelay(JobRecord job)",
    "VirtualJobSnapshot.BACKOFF_LINEAR", "1L <<", "job.failureCount = Math.min",
    "job.periodic", "safeAdd(now, job.intervalMs)", "minimumLatencyMs", "overrideDeadlineMs")
for forbidden in ["new Bundle(", "android.os.Bundle"]:
    if forbidden in snapshot:
        errors.append(f"Job contract contains forbidden untyped payload: {forbidden}")

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    "jobPolicyPersistenceAndRetryLifecycle", "typed Job constraints must survive Package Service recreation",
    "linear retry must persist failure count", "successful periodic job must remain scheduled",
    "APK revision update must prune stale persisted jobs")
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceSelfTest.java",
    "JobInfo constraints and scheduling policy must enter typed virtual state")
require(
    "app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java",
    "virtual job policy snapshot lost")

matrix = ROOT / "verification/m3-source-capability-matrix.json"
if matrix.is_file():
    ids = {x.get("id") for x in json.loads(matrix.read_text(encoding="utf-8")).get("capabilities", [])}
    for item in ["contract.job-scheduling-policy", "package.job-retry-persistence",
                 "framework.job-policy-projection"]:
        if item not in ids:
            errors.append(f"capability matrix missing {item}")
else:
    errors.append("missing capability matrix")
service_matrix = ROOT / "verification/system-service-coverage-matrix.json"
if service_matrix.is_file():
    services = {x.get("id"): x for x in json.loads(service_matrix.read_text(encoding="utf-8")).get("services", [])}
    job = services.get("job-scheduler", {})
    if job.get("production") != "wired" or "typed-policy" not in job.get("hook", ""):
        errors.append("job-scheduler service matrix must disclose wired typed policy")
else:
    errors.append("missing service matrix")

if errors:
    print("FAIL M4-T16 JobScheduler policy checks", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M4-T16 JobScheduler policy checks")
