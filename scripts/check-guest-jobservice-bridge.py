#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def text(rel):
 p=ROOT/rel
 if not p.is_file(): errors.append(f"missing required file: {rel}"); return ""
 return p.read_text(encoding="utf-8")
def require(rel,*tokens):
 c=text(rel)
 for token in tokens:
  if token not in c: errors.append(f"{rel} missing evidence: {token}")
 return c
root=require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
 "startVirtualJob", "stopVirtualJob", "VirtualJobParametersSnapshot", "IHostJobCallback")
observer=require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
 "boolean onJobStart", "boolean onJobStop", "IVirtualJobExecution")
execution=require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualJobExecution.aidl",
 "guestJobId", "generation", "dispatchToken", "isActive", "finish")
for c in (root,observer,execution):
 if "Bundle" in c: errors.append("M4-T13 Job bridge AIDL must remain typed")
session=text("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl")
if "finishJob" in session: errors.append("Guest system-service session must not expose direct finishJob")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualJobParametersSnapshot.java",
 "implements Parcelable", "forGuest", "withStopReason", "dispatchToken")
store=require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
 "VirtualJobSnapshot.DISPATCHING", "startJob(", "stopJob(", "JOB_EXECUTION_TIMEOUT_MS",
 "activeJobExecutions", "linkToDeath", "onJobStart", "onJobStop", "finishHostJob")
for forbidden in ["dispatchJob(", "onJobReady", "synchronized void finishJob("]:
 if forbidden in store: errors.append(f"obsolete M4-T12 Job path remains: {forbidden}")
require("app/src/main/java/com/warden/controlledsandbox/VirtualJobService.java",
 "startVirtualJob", "stopVirtualJob", "IHostJobCallback", "ConcurrentMap", "jobFinished")
require("app/src/main/java/com/warden/controlledsandbox/HostJobParametersSnapshotFactory.java",
 "VirtualJobParametersSnapshot", "getTriggeredContentUris", "getStopReason", "MAX_PAYLOAD_BYTES")
bridge=require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestJobServiceBridge.java",
 "service.onStartJob", "service.onStopJob", "GuestJobParametersFactory", "GuestJobCallbackBinder",
 "IJobCallback", "execution.finish", "generation() != session.spec.generation")
if "synchronized boolean start(" in bridge or "synchronized boolean stop(" in bridge:
 errors.append("Guest JobService main-thread calls must not run while holding the bridge monitor")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
 "new GuestJobServiceBridge", "setExecutionListener", "onVirtualJobStart", "onVirtualJobStop")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
 "onJobStart", "onJobStop", "IVirtualJobExecution", "JobExecutionListener")
require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
 "Guest jobFinished must complete the trusted host callback", "unacknowledged job must remain SCHEDULED")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestJobServiceBridgeSelfTest.java",
 "Guest JobParameters must expose Guest job ID", "jobFinished must be one-shot")
runner=text("tools/static_android_compile.py")
for t in ["VirtualSystemServiceStoreSelfTest","GuestJobServiceBridgeSelfTest"]:
 if runner.count(t)<1: errors.append(f"static compiler does not execute {t}")
matrix=ROOT/"verification/m3-source-capability-matrix.json"
if matrix.is_file():
 ids={x.get("id") for x in json.loads(matrix.read_text()).get("capabilities",[])}
 for expected in ["contract.typed-job-execution-capability","package.job-execution-state-machine","runtime.guest-jobservice-execution-bridge","runtime.job-finished-callback"]:
  if expected not in ids: errors.append(f"capability matrix missing {expected}")
else: errors.append("missing capability matrix")
if errors:
 print("FAIL M4-T13 Guest JobService bridge checks",file=sys.stderr)
 for e in errors: print(" - "+e,file=sys.stderr)
 raise SystemExit(1)
print("PASS M4-T13 Guest JobService bridge checks")
