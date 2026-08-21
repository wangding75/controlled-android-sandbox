#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def require(relative: str, *tokens: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    content = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in content:
            errors.append(f"{relative} missing evidence: {token}")
    return content

session = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "void scheduleAlarm(in VirtualAlarmSnapshot candidate)",
    "VirtualNotificationSnapshot reserveNotification(in VirtualNotificationSnapshot candidate)",
    "void upsertNotificationChannel(in VirtualNotificationChannelSnapshot value)")
observer = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "void onAlarm(in VirtualAlarmSnapshot alarm)")
for source, tokens in {
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualAlarmSnapshot.java": [
        "implements Parcelable", "PENDING_INTENT", "exact", "allowWhileIdle",
        "pendingIntentTokenId", "packageRevision", "deliveryCount"],
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualNotificationSnapshot.java": [
        "implements Parcelable", "contentIntentTokenId", "deleteIntentTokenId",
        "actionIntentTokenIds", "foregroundService", "foregroundServiceKey", "packageRevision"],
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualNotificationChannelSnapshot.java": [
        "implements Parcelable", "CHANNEL", "GROUP", "packageRevision"],
}.items():
    content = require(source, *tokens)
    if "Bundle" in content:
        errors.append(f"{source} must remain typed and Bundle-free")
if "Bundle" in session or "Bundle" in observer:
    errors.append("Alarm/Notification AIDL must remain typed and Bundle-free")

store = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "requirePendingIntent(scope, revision, pendingIntentTokenId)",
    "alarm.deliveryCount++", "pruneNotificationRevisionLocked",
    "validateNotificationReferences", "persistOrRestore", "optionalIdentity")
limits = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceLimits.java",
    "static final int SCHEMA =", "MAX_ALARMS_PER_SCOPE",
    "MAX_NOTIFICATIONS_PER_SCOPE", "MAX_NOTIFICATION_CHANNELS_PER_SCOPE",
    "RETRY_WITHOUT_CLIENT_MS")
try:
    store_schema = int(limits.split("static final int SCHEMA =", 1)[1].split(";", 1)[0].strip())
    if store_schema < 5:
        errors.append(f"VirtualSystemServiceStore schema regressed below 5: {store_schema}")
except Exception:
    errors.append("VirtualSystemServiceStore schema is not parseable")

require(
    "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java",
    "systemServices.scheduleAlarm(scope, processName, generation", "packageRevision, candidate",
    "systemServices.notifications(scope, packageRevision)",
    "systemServices.notificationChannels(scope, packageRevision)")
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java",
    "String deliveryPath = hostHeld ? \"PENDING_INTENT\" : \"LISTENER\"", "name.contains(\"exact\")",
    "repeating(name)", "cancelAllNotifications", "notificationMetadata",
    "foregroundServiceKey", "filterAndRestoreChannelResult")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PendingIntentFrameworkInterceptor.java",
    "SYSTEM_HOLDER_INTENT_NOT_REWRITTEN", "RuntimePendingIntentRelayReceiver.ACTION",
    "systemHolderRelayIntent", "SYSTEM_HOLDER_REWRITE")
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityInvocationHandler.java",
    "preserveRawPendingIntentSender", "android.content.IIntentSender", "SYSTEM_HOLDER_AMS_RETURN")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "pendingIntentRelayExecutor", "SYSTEM_HOLDER_RELAY_DISPATCH_ASYNC",
    "dispatchSystemHeldAsync")
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistry.java",
    "sendPersistent", "materializePersistent", "persistence.markSent")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "setRecoveredAlarmDelivery", "VIRTUAL_ALARM_RECOVERED_TARGET_UNAVAILABLE",
    "session.scheduleAlarm", "session::listAlarms")
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "virtualServices.alarms().setRecoveredDelivery", "sendPersistentPendingIntent")
require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    "exact PendingIntent alarm metadata must be durable",
    "Package Service recovery must rebind an alarm",
    "repeating listener alarm must persist delivery count",
    "notification recovery must retain FGS mapping",
    "group deletion must cascade through channels and notifications",
    "APK revision update must prune stale notification state")
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/routing/VirtualPendingIntentRegistrySelfTest.java",
    "durable sender dispatches by persistent token without Binder reissue")
runner = require("tools/static_android_compile.py", "VirtualSystemServiceStoreSelfTest",
                 "VirtualPendingIntentRegistrySelfTest")

if errors:
    print("FAIL M4-T16 Alarm/Notification lifecycle checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M4-T16 Alarm/Notification lifecycle checks")
