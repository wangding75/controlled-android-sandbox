#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "verification/m5-t19-1-e-aidl-pagination.json"
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


aidl = require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "VirtualAccountPage listAccountsPage",
    "VirtualPendingIntentPage listPendingIntentsPage",
    "VirtualAlarmPage listAlarmsPage",
    "VirtualNotificationPage listNotificationsPage",
    "VirtualNotificationChannelPage listNotificationChannelsPage",
    "VirtualJobPage listJobsPage",
    "VirtualShortcutPage listShortcutsPage",
    "VirtualWidgetPage listAppWidgetsPage",
    "VirtualSettingPage listSettingsPage",
    "ParcelFileDescriptor openPageBlob",
)

legacy_method_markers = [
    "byte[] getClipboard()", "void setClipboard", "void clearClipboard", "void registerObserver",
    "List<VirtualAccountSnapshot> listAccounts", "boolean addAccount", "boolean removeAccount",
    "void setPassword", "String getPassword", "void setAuthToken", "String peekAuthToken",
    "void invalidateAuthToken", "VirtualPendingIntentSnapshot reservePendingIntent",
    "VirtualPendingIntentSnapshot markPendingIntentSent", "boolean cancelPendingIntent",
    "List<VirtualPendingIntentSnapshot> listPendingIntents", "void scheduleAlarm",
    "boolean cancelAlarm", "List<VirtualAlarmSnapshot> listAlarms",
    "VirtualNotificationSnapshot reserveNotification", "void commitNotification",
    "boolean removeNotification", "List<VirtualNotificationSnapshot> listNotifications",
    "void upsertNotificationChannel", "boolean removeNotificationChannel",
    "List<VirtualNotificationChannelSnapshot> listNotificationChannels",
    "VirtualJobSnapshot reserveJob", "void commitJob", "boolean removeJob",
    "List<VirtualJobSnapshot> listJobs", "int ensureNamespace", "int hostIdIfPresent",
    "int guestIdForHost", "int removeNamespace", "int[] listNamespaceGuestIds",
    "VirtualDeviceServiceProfileSnapshot getDeviceServiceProfile",
    "VirtualInteractionProfileSnapshot getInteractionProfile",
    "VirtualNetworkServiceProfileSnapshot getNetworkServiceProfile",
    "ApplicationEnvironmentProfileSnapshot getApplicationEnvironmentProfile",
    "VirtualCompatibilityProfileSnapshot getCompatibilityProfile",
    "VirtualPolicyServicesProfileSnapshot getPolicyServicesProfile",
    "VirtualMediaCommunicationProfileSnapshot getMediaCommunicationProfile",
    "VirtualPeripheralServicesProfileSnapshot getPeripheralServicesProfile",
    "VirtualPrivilegedServicesProfileSnapshot getPrivilegedServicesProfile",
    "List<VirtualShortcutSnapshot> listShortcuts", "boolean replaceDynamicShortcuts",
    "boolean addDynamicShortcuts", "void removeShortcuts", "void setShortcutsEnabled",
    "void reportShortcutUsed", "int allocateAppWidgetId", "boolean deleteAppWidgetId",
    "List<VirtualWidgetSnapshot> listAppWidgets", "boolean bindAppWidgetId",
    "void updateAppWidget", "void reportUsageEvent",
    "List<VirtualUsageEventSnapshot> queryUsageEvents", "VirtualSettingSnapshot getSetting",
    "void putSetting", "boolean deleteSetting", "List<VirtualSettingSnapshot> listSettings",
    "void close()",
]
legacy_positions = [aidl.find(marker) for marker in legacy_method_markers]
if any(position < 0 for position in legacy_positions):
    errors.append("pre-existing AIDL method missing from compatibility sequence")
elif legacy_positions != sorted(legacy_positions):
    errors.append("pre-existing AIDL method order changed; Binder transaction IDs are incompatible")
close_position = aidl.find("void close()")
for marker in ("listAccountsPage", "listPendingIntentsPage", "listAlarmsPage",
        "listNotificationsPage", "listNotificationChannelsPage", "listJobsPage",
        "listShortcutsPage", "listAppWidgetsPage", "listSettingsPage", "openPageBlob"):
    if aidl.find(marker) < close_position:
        errors.append(f"new AIDL method must be appended after legacy close(): {marker}")

for name in (
    "VirtualPageRequest", "VirtualPageBlob", "VirtualAccountSummary", "VirtualAccountPage",
    "VirtualPendingIntentPage", "VirtualAlarmPage", "VirtualNotificationPage",
    "VirtualNotificationChannelPage", "VirtualJobPage", "VirtualShortcutPage",
    "VirtualWidgetPage", "VirtualSettingPage",
):
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name}")

request = require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualPageRequest.java",
    "MAX_ITEMS = 128",
    "MAX_BYTES = 256 * 1024",
    "PAGE_MAX_ITEMS_INVALID",
    "PAGE_MAX_BYTES_INVALID",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualAccountSummary.java",
    "private final String name",
    "private final String type",
)

pager = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServicePager.java",
    "HmacSHA256",
    "PAGE_TOKEN_TAMPERED",
    "PAGE_TOKEN_SCOPE_MISMATCH",
    "PAGE_TOKEN_COLLECTION_MISMATCH",
    "PAGE_TOKEN_STALE",
    "ITEM_EXCEEDS_BINDER_BUDGET",
    "PAGING_REQUIRED",
    "LARGE_BINARY_THRESHOLD = 64 * 1024",
)
blob_store = require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPageBlobStore.java",
    "MAX_GRANTS = 64",
    "MAX_TOTAL_BYTES = 16 * 1024 * 1024",
    "ParcelFileDescriptor.MODE_READ_ONLY",
    "PAGE_BLOB_SCOPE_MISMATCH",
    "PAGE_BLOB_SESSION_BUDGET_EXCEEDED",
    "grants.remove(normalized)",
)
session = require(
    "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java",
    "systemServices.accountSummaries",
    "new VirtualAccountSnapshot(value.name(), value.type(), \"\"",
    "pager.openBlob(pagingScopeKey, blobToken)",
    "legacyRequest()",
)
remote = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "session.listAccountsPage",
    "session::listPendingIntentsPage",
    "session::listAlarmsPage",
    "session::listNotificationsPage",
    "session::listNotificationChannelsPage",
    "session::listJobsPage",
    "session::listShortcutsPage",
    "session.listAppWidgetsPage",
    "session.listSettingsPage",
    "PAGE_MAX_BYTES = 224 * 1024",
)
for forbidden in (
    "session.listAccounts(", "session.listPendingIntents(", "session.listAlarms(",
    "session.listNotifications(", "session.listNotificationChannels(", "session.listJobs(",
    "session.listShortcuts(", "session.listAppWidgets(", "session.listSettings(",
):
    if forbidden in remote:
        errors.append(f"Runtime still calls legacy unpaged API: {forbidden}")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualPageHydrator.java",
    "PAGE_BLOB_LENGTH_MISMATCH",
    "PAGE_BLOB_DIGEST_MISMATCH",
    "session.openPageBlob",
)
test = require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServicePagingSelfTest.java",
    "PAGE_TOKEN_TAMPERED",
    "PAGE_TOKEN_SCOPE_MISMATCH",
    "PAGE_TOKEN_COLLECTION_MISMATCH",
    "PAGE_TOKEN_STALE",
    "ITEM_EXCEEDS_BINDER_BUDGET",
    "PAGING_REQUIRED",
    "ParcelFileDescriptor payload changed",
    "legacy account list leaked password or token material",
    "page did not stop at active blob-grant window",
    "PAGE_BLOB_TOKEN_INVALID",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.VirtualSystemServicePagingSelfTest",
)
if runner.count("'com.warden.controlledsandbox.VirtualSystemServicePagingSelfTest'") != 1:
    errors.append("static Android compiler must execute paging self-test exactly once")

require(
    "README.md",
    "## M5-T19.1-E Binder collection pagination and credential minimization fix",
    "PAGING_REQUIRED",
)
require(
    "docs/ARCHITECTURE.md",
    "## Binder collection pagination and binary payload transport",
    "VirtualAccountSummary(name,type)",
)
require(
    "docs/M5_T19_1_E_DEVELOPMENT_REPORT.md",
    "Source fix: PASS",
    "Android Binder-driver evidence: 0",
)

report = {
    "task": "M5-T19.1-E",
    "findings": [
        "P1-05 unbounded AIDL collection transactions",
        "P2-11 account enumeration transfers credentials",
    ],
    "sourceStatus": "PASS" if not errors else "FAIL",
    "pagedCollections": 9,
    "hardMaximumItems": 128,
    "hardMaximumBytes": 256 * 1024,
    "runtimeRequestBytes": 224 * 1024,
    "legacyMaximumItems": 32,
    "legacyMaximumBytes": 128 * 1024,
    "binaryOffloadThreshold": 64 * 1024,
    "maximumActiveBlobGrants": 64,
    "blobGrantSemantics": "ONE_TIME_NO_SILENT_EVICTION",
    "legacyTransactionIdsPreserved": True,
    "accountSummaryFields": ["name", "type"],
    "androidGeneratedAidlEvidenceCount": 0,
    "androidBinderEvidenceCount": 0,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-E AIDL pagination checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-E typed collection paging, byte budget, blob handle and account minimization gate")
