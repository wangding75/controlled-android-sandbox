#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")


def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require(
    "docs/plans/M5_T14_MEDIA_COMMUNICATION_SYSTEM_ENVIRONMENT.md",
    "MediaSession",
    "MediaRouter and audio routing",
    "SMS and communication",
    "Backup",
    "DropBox",
    "BLOCKED",
    "STATIC",
    "HOST",
)
require(
    "docs/M5_T14_DEVELOPMENT_REPORT.md",
    "Source status: PASS",
    "Production status: PARTIAL",
    "Device evidence: 0",
    "read-only VA/NBB",
)
require(
    "docs/comparisons/M5_T14_VA_NBB_COMPARISON.md",
    "VirtualApp",
    "NewBlackbox",
    "Device evidence remains 0",
)
require(
    "README.md",
    "## M5-T14 media, communication and archival system-environment source baseline",
)
require(
    "docs/ROADMAP.md",
    "## M5-T14 media, communication and archival system-environment baseline",
)

contracts = (
    "VirtualMediaCommunicationProfileSnapshot",
    "VirtualMediaSessionProfileSnapshot",
    "VirtualMediaRouterProfileSnapshot",
    "VirtualAudioRoutingProfileSnapshot",
    "VirtualMessagingProfileSnapshot",
    "VirtualBackupProfileSnapshot",
    "VirtualDropBoxProfileSnapshot",
)
for name in contracts:
    require(
        f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
        f"parcelable {name};",
    )
    require(
        f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
        "Parcelable",
    )

require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualMediaSessionProfileSnapshot.java",
    "active",
    "allowSessionCreation",
    "allowTransportControls",
    "maximumSessions",
    "playbackState",
    "playbackPositionMs",
    "title",
    "artist",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualMediaRouterProfileSnapshot.java",
    "selectedRouteId",
    "selectedRouteName",
    "routeType",
    "routeVolume",
    "routeVolumeMax",
    "allowRouteChanges",
    "maximumClients",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualAudioRoutingProfileSnapshot.java",
    "audioMode",
    "ringerMode",
    "speakerphoneOn",
    "bluetoothScoOn",
    "microphoneMuted",
    "musicVolume",
    "allowAudioFocus",
    "maximumFocusOwners",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualMessagingProfileSnapshot.java",
    "subscriptionId",
    "defaultSmsPackage",
    "allowTextMessages",
    "allowDataMessages",
    "allowMultipartMessages",
    "maximumMessagesPerWindow",
    "quotaWindowMs",
    "storeSentMessages",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualBackupProfileSnapshot.java",
    "backupEnabled",
    "backupProvisioned",
    "currentTransport",
    "transports",
    "allowDataChanged",
    "allowBackupNow",
    "allowRestore",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualDropBoxProfileSnapshot.java",
    "enabledTags",
    "allowWrites",
    "exposeEntries",
    "maximumEntries",
    "maximumEntryBytes",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
    "getMediaCommunicationProfile",
    "setMediaCommunicationProfile",
    "resetMediaCommunicationProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "getMediaCommunicationProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "onMediaCommunicationProfileChanged",
)

require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualMediaCommunicationDefaults.java",
    "VirtualMediaCommunicationProfileSnapshot",
    "MODE_STATIC",
    "VirtualMessagingProfileSnapshot",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualMediaCommunicationStore.java",
    "MEDIA_COMMUNICATION_PROFILE_VERSION_CONFLICT",
    "deleteScopeBestEffort",
    "scope limit exceeded",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualMediaCommunicationStorePersistence.java",
    "CRC32",
    ".corrupt",
    "ATOMIC_MOVE",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
    "getMediaCommunicationProfile",
    "setMediaCommunicationProfile",
    "resetMediaCommunicationProfile",
    "notifyMediaCommunicationProfileChanged",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
    "mediaCommunicationProfile",
    "setMediaCommunicationProfile",
    "resetMediaCommunicationProfile",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "onMediaCommunicationProfileChanged",
)

require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/MediaCommunicationInvocationInterceptor.java",
    'case "mediasession"',
    'case "mediarouter"',
    'case "audio"',
    'case "isms", "isms2"',
    'case "backup"',
    'case "dropbox"',
    "VIRTUAL_MEDIA_SESSION_LIMIT_EXCEEDED",
    "VIRTUAL_MEDIA_ROUTER_CLIENT_LIMIT_EXCEEDED",
    "VIRTUAL_AUDIO_FOCUS_LIMIT_EXCEEDED",
    "VIRTUAL_SMS_QUOTA_EXCEEDED",
    "VIRTUAL_SMS_INJECTION_DENIED",
    "VIRTUAL_BACKUP_EXECUTION_DENIED",
    "VIRTUAL_RESTORE_DENIED",
    "VIRTUAL_DROPBOX_ENTRY_LIMIT_EXCEEDED",
    'unsupported("audio_routing", method)',
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java",
    "MediaCommunicationInvocationInterceptor",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
    'attempt("mediaSession"',
    'attempt("mediaRouter"',
    'attempt("sms"',
    'attempt("backup"',
    'attempt("dropBox"',
    'attempt("audioCapture"',
)
for hook in (
    "MediaSessionManagerServiceHook",
    "MediaRouterServiceHook",
    "SmsServiceHook",
    "BackupManagerServiceHook",
    "DropBoxManagerServiceHook",
):
    require(
        f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
        "install",
    )

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/MediaCommunicationProxyReadiness.java",
    "VIRTUAL_MEDIA_SESSION_PROXY_REQUIRED",
    "VIRTUAL_MEDIA_ROUTER_PROXY_REQUIRED",
    "VIRTUAL_AUDIO_ROUTING_PROXY_REQUIRED",
    "VIRTUAL_SMS_PROXY_REQUIRED",
    "VIRTUAL_BACKUP_PROXY_REQUIRED",
    "VIRTUAL_DROPBOX_PROXY_REQUIRED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "MediaCommunicationProxyReadiness.require",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "onMediaCommunicationProfileChanged",
    "getMediaCommunicationProfile",
)

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualMediaCommunicationStoreSelfTest.java",
    "per-user media scope isolation",
    "defaults fail closed",
    "optimistic media update",
    "stale media update rejected",
    "profile persisted",
    "corrupt media store quarantined",
    "PASS M5-T14 media-communication profile store self-test",
)
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/MediaCommunicationVirtualizationSelfTest.java",
    "media session quota enforced",
    "media router quota enforced",
    "audio route state projected",
    "audio focus quota enforced",
    "unknown audio route query fails closed",
    "SMS quota enforced",
    "restore token query fails closed deterministically",
    "restore denied by profile",
    "DropBox tags projected",
    "HOST audio passes through",
    "PASS M5-T14 media-communication virtualization self-test",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/MediaCommunicationProxyReadinessSelfTest.java",
    "missing router blocks",
    "PASS M5-T14 media-communication proxy readiness self-test",
)

runner = text("tools/static_android_compile.py")
for class_name in (
    "com.warden.controlledsandbox.VirtualMediaCommunicationStoreSelfTest",
    "com.warden.controlledsandbox.framework.core.MediaCommunicationVirtualizationSelfTest",
    "com.warden.controlledsandbox.runtime.guest.MediaCommunicationProxyReadinessSelfTest",
):
    if runner.count(f"'{class_name}'") != 1:
        errors.append(f"static Android compiler must execute {class_name} exactly once")

# Product modules must not import or embed upstream implementation namespaces.
for root in (
    "app",
    "sandbox-contract",
    "sandbox-domain",
    "sandbox-framework",
    "sandbox-runtime",
    "sandbox-native",
    "sandbox-native-companion",
):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"upstream implementation namespace in product source: {path.relative_to(ROOT)}")

# Reference trees are frozen at the M5-T13 base for this iteration.
try:
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "3f9957c", "--", "ref/upstream"],
        cwd=ROOT,
        text=True,
    ).splitlines()
    if changed:
        errors.append("M5-T14 modified read-only reference files: " + ", ".join(changed))
except Exception as exc:
    errors.append(f"cannot verify read-only references: {exc}")

try:
    preflight = json.loads(text("verification/m5-t14-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS":
        errors.append("M5-T14 preflight sourceStatus must be PASS")
    if preflight.get("productionStatus") != "PARTIAL":
        errors.append("M5-T14 preflight productionStatus must be PARTIAL")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T14 preflight must not invent device evidence")
    if preflight.get("evidence", {}).get("typedParcelableContracts") != 7:
        errors.append("M5-T14 preflight must record seven typed contracts")
    if preflight.get("evidence", {}).get("newFrameworkHookGroups") != 5:
        errors.append("M5-T14 preflight must record five new hook groups")
except Exception as exc:
    errors.append(f"invalid M5-T14 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T14 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
        {"wired": 110, "partial": 2, "not-applicable": 1}
    ):
        errors.append("M5-T14 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
        {"not-tested": 109, "not-applicable": 3, "blocked": 1}
    ):
        errors.append("M5-T14 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t14-media-communication.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T14 gate")

if errors:
    print("FAIL M5-T14 media-communication checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)

print(
    "PASS M5-T14 media/session/router/audio/SMS/backup/DropBox checks: "
    "source expanded; Android/SystemUI/carrier/device limits remain explicit"
)
