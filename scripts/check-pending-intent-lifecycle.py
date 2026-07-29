#!/usr/bin/env python3
from pathlib import Path
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

root = require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageService.aidl",
               "int virtualUid", "String packageRevision")
session = require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
                  "reservePendingIntent", "markPendingIntentSent", "cancelPendingIntent",
                  "List<VirtualPendingIntentSnapshot> listPendingIntents()")
snapshot = require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualPendingIntentSnapshot.java",
                   "implements Parcelable", "filterIdentity", "creatorPackage", "creatorUid",
                   "requiredPermission", "ownerGeneration", "packageRevision", "byte[] payload")
if "Bundle" in root or "Bundle" in session or "Bundle" in snapshot:
    errors.append("M4-T16 PendingIntent contract must remain typed and Bundle-free")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/VirtualPendingIntentSnapshot.aidl",
        "parcelable VirtualPendingIntentSnapshot")

store = require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
                "private static final int SCHEMA = 3", "MAX_PENDING_INTENTS_PER_SCOPE",
                "VIRTUAL_PENDING_INTENT_CREATOR_UID_MISMATCH", "nextPendingIntentToken",
                "packageRevision", "filterIdentity", "persistOrRestore")
service = require("app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
                  "private final int virtualUid", "reservePendingIntent", "requireCapability()")
registry = require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistry.java",
                   "FLAG_MUTABLE", "FLAG_IMMUTABLE", "FLAG_NO_CREATE", "FLAG_CANCEL_CURRENT",
                   "FLAG_UPDATE_CURRENT", "persistentTokenId", "senderPermission",
                   "filterIdentity", "persistence.markSent", "Process shutdown detaches")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptor.java",
        "filterIdentity(Intent intent)", "getflagsforintentsender", "ACTIVITY_RESULT",
        "spec.virtualUid", "spec.packageRevision")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPendingIntentDispatcher.java",
        "dispatchActivityResult", "ActivityResultRequest.SEND", "fillIn", "getClipData",
        "pendingIntentCreatorUid")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "session.reservePendingIntent", "session.markPendingIntentSent", "listPendingIntents")

for path, evidence in {
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistrySelfTest.java": [
        "process shutdown retains persistent sender token", "sender permission enforced",
        "APK revision change invalidates old persistent sender", "NO_CREATE does not allocate"],
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptorSelfTest.java": [
        "PendingIntent equality ignores extras", "MIME type participates", "ClipData",
        "Activity Result PendingIntent", "persistent sender is reattached"],
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java": [
        "durable PendingIntent must survive Package Service recreation",
        "Package Service must reject forged virtual creator UID"],
}.items():
    require(path, *evidence)

runner = require("tools/static_android_compile.py", "VirtualPendingIntentRegistrySelfTest",
                 "PendingIntentFrameworkInterceptorSelfTest", "VirtualSystemServiceStoreSelfTest")
if errors:
    print("FAIL M4-T16 PendingIntent lifecycle checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M4-T16 PendingIntent lifecycle checks")
