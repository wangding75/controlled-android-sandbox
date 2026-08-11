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
    "docs/plans/M5_T15_PERIPHERAL_EXTERNAL_SERVICES.md",
    "NFC",
    "USB",
    "Printing and Companion Device",
    "MediaProjection and Camera",
    "OEM system services",
    "BLOCKED",
    "STATIC",
    "HOST",
)
require(
    "docs/M5_T15_DEVELOPMENT_REPORT.md",
    "Source status: PASS",
    "Production status: PARTIAL",
    "Device evidence: 0",
    "read-only VA/NBB",
)
require(
    "docs/comparisons/M5_T15_VA_NBB_COMPARISON.md",
    "VirtualApp",
    "NewBlackbox",
    "Device evidence remains 0",
)
require(
    "README.md",
    "## M5-T15 peripheral and external system-services source baseline",
)
require(
    "docs/ROADMAP.md",
    "## M5-T15 peripheral and external system-services baseline",
)

contracts = (
    "VirtualPeripheralServicesProfileSnapshot",
    "VirtualNfcProfileSnapshot",
    "VirtualUsbProfileSnapshot",
    "VirtualPrintProfileSnapshot",
    "VirtualCompanionDeviceProfileSnapshot",
    "VirtualMediaProjectionProfileSnapshot",
    "VirtualCameraProfileSnapshot",
    "VirtualOemSystemServicesProfileSnapshot",
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
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualNfcProfileSnapshot.java",
    "adapterState",
    "readerModeAllowed",
    "cardEmulationAvailable",
    "maximumReaderSessions",
    "maximumTagOperations",
    "tagIds",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualUsbProfileSnapshot.java",
    "hostSupported",
    "accessorySupported",
    "allowPermissionRequests",
    "allowOpenDevice",
    "maximumOpenDevices",
    "approvedDeviceNames",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualPrintProfileSnapshot.java",
    "printingEnabled",
    "allowPrintJobs",
    "maximumActiveJobs",
    "defaultPrinterId",
    "availablePrintServices",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualCompanionDeviceProfileSnapshot.java",
    "allowAssociation",
    "allowDisassociation",
    "presenceObservationEnabled",
    "maximumAssociations",
    "associationIds",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualMediaProjectionProfileSnapshot.java",
    "projectionAvailable",
    "allowScreenCapture",
    "allowAudioCapture",
    "requireConsent",
    "maximumActiveSessions",
    "virtualWidth",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualCameraProfileSnapshot.java",
    "cameraAvailable",
    "allowOpen",
    "allowTorch",
    "maximumOpenCameras",
    "cameraIds",
    "frontCameraIds",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualOemSystemServicesProfileSnapshot.java",
    "serviceNames",
    "allowedQueryPrefixes",
    "blockedMutationPrefixes",
    "maximumSessions",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
    "getPeripheralServicesProfile",
    "setPeripheralServicesProfile",
    "resetPeripheralServicesProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "getPeripheralServicesProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "onPeripheralServicesProfileChanged",
)

require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPeripheralServicesDefaults.java",
    "VirtualPeripheralServicesProfileSnapshot",
    "MODE_STATIC",
    "VirtualCameraProfileSnapshot",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPeripheralServicesStore.java",
    "PERIPHERAL_SERVICES_PROFILE_VERSION_CONFLICT",
    "deleteScopeBestEffort",
    "scope limit exceeded",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPeripheralServicesStorePersistence.java",
    "CRC32",
    ".corrupt",
    "DurableAtomicFile.replacePrepared",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPeripheralServicesStoreCodec.java",
    "PERIPHERAL_SERVICES_SCOPE_DUPLICATE",
    "maximumReaderSessions",
    "approvedDeviceNames",
    "maximumActiveSessions",
    "maximumOpenCameras",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
    "getPeripheralServicesProfile",
    "setPeripheralServicesProfile",
    "resetPeripheralServicesProfile",
    "notifyPeripheralServicesProfileChanged",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
    "peripheralServicesProfile",
    "setPeripheralServicesProfile",
    "resetPeripheralServicesProfile",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "onPeripheralServicesProfileChanged",
)

require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralServicesInvocationInterceptor.java",
    'case "nfc"',
    'case "usb"',
    'case "print"',
    'case "companiondevice"',
    'case "mediaprojection"',
    'case "camera"',
    'case "oemsystem"',
    "VIRTUAL_NFC_READER_SESSION_LIMIT_EXCEEDED",
    "VIRTUAL_USB_OPEN_DEVICE_LIMIT_EXCEEDED",
    "VIRTUAL_PRINT_JOB_LIMIT_EXCEEDED",
    "VIRTUAL_COMPANION_DISASSOCIATION_DENIED",
    "VIRTUAL_MEDIA_PROJECTION_SESSION_LIMIT_EXCEEDED",
    "VIRTUAL_CAMERA_SESSION_LIMIT_EXCEEDED",
    "VIRTUAL_OEM_SYSTEM_SESSION_LIMIT_EXCEEDED",
    "ensureCompanionAssociations",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralInvocationValues.java",
    "identitySet",
    "addBounded",
    "emptyValue",
    "stringArrayOrList",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/contract/CameraServiceContract.java",
    "media.camera",
    "android.hardware.ICameraService",
    "CameraManager$CameraManagerGlobal",
    "mCameraService",
    "FEATURE_CAMERA_FRONT",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java",
    "PeripheralServicesInvocationInterceptor",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
    'attempt("nfc"',
    'attempt("usb"',
    'attempt("print"',
    'attempt("companionDevice"',
    'attempt("mediaProjection"',
    'attempt("oemSystemServices"',
    'attempt("camera"',
)
for hook in (
    "CameraServiceHook",
    "NfcServiceHook",
    "UsbServiceHook",
    "PrintManagerServiceHook",
    "CompanionDeviceManagerServiceHook",
    "MediaProjectionManagerServiceHook",
    "OemSystemServicesHook",
):
    require(
        f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
        "install",
    )
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/OemSystemServicesHook.java",
    "closeReverse(installed)",
    "OEM_SYSTEM_SERVICES_UNAVAILABLE",
    "addSuppressed",
)
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/service/CameraServiceHookSelfTest.java",
    "testExistingServiceAndCacheRollback",
    "testMissingServiceUsesControlledSyntheticCamera",
    "invalid Camera descriptor",
    "CameraManagerGlobal.mCameraService",
    "PASS Camera service contract self-test",
)

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PeripheralServicesProxyReadiness.java",
    "VIRTUAL_NFC_PROXY_REQUIRED",
    "VIRTUAL_USB_PROXY_REQUIRED",
    "VIRTUAL_PRINT_PROXY_REQUIRED",
    "VIRTUAL_COMPANION_DEVICE_PROXY_REQUIRED",
    "VIRTUAL_MEDIA_PROJECTION_PROXY_REQUIRED",
    "VIRTUAL_CAMERA_PROFILE_PROXY_REQUIRED",
    "VIRTUAL_OEM_SYSTEM_SERVICES_PROXY_REQUIRED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "PeripheralServicesProxyReadiness.require",
    "CAMERA_READY",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "onPeripheralServicesProfileChanged",
    "getPeripheralServicesProfile",
)

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualPeripheralServicesStoreSelfTest.java",
    "per-user peripheral scope isolation",
    "defaults fail closed",
    "optimistic peripheral update",
    "stale peripheral update rejected",
    "profile persisted",
    "corrupt peripheral store quarantined",
    "PASS M5-T15 peripheral-services profile store self-test",
)
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PeripheralServicesVirtualizationSelfTest.java",
    "NFC reader quota enforced",
    "USB open-device quota enforced",
    "print-job quota enforced",
    "disassociation is classified independently",
    "media projection quota enforced",
    "camera session quota enforced",
    "OEM session quota enforced",
    "HOST NFC passes through",
    "PASS M5-T15 peripheral-services virtualization self-test",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/PeripheralServicesProxyReadinessSelfTest.java",
    "missing USB proxy blocks startup",
    "configured OEM service proxy is required",
    "PASS M5-T15 peripheral-services proxy readiness self-test",
)

runner = text("tools/static_android_compile.py")
for class_name in (
    "com.warden.controlledsandbox.VirtualPeripheralServicesStoreSelfTest",
    "com.warden.controlledsandbox.framework.core.PeripheralServicesVirtualizationSelfTest",
    "com.warden.controlledsandbox.framework.service.CameraServiceHookSelfTest",
    "com.warden.controlledsandbox.runtime.guest.PeripheralServicesProxyReadinessSelfTest",
):
    if runner.count(f"'{class_name}'") != 1:
        errors.append(f"static Android compiler must execute {class_name} exactly once")

for rel in (
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralServicesInvocationInterceptor.java",
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralInvocationValues.java",
):
    lines = text(rel).splitlines()
    if len(lines) > 500:
        errors.append(f"M5-T15 production class exceeds 500 lines: {rel} ({len(lines)})")

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

try:
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "8566ccc", "--", "ref/upstream"],
        cwd=ROOT,
        text=True,
    ).splitlines()
    if changed:
        errors.append("M5-T15 modified read-only reference files: " + ", ".join(changed))
except Exception as exc:
    errors.append(f"cannot verify read-only references: {exc}")

try:
    preflight = json.loads(text("verification/m5-t15-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS":
        errors.append("M5-T15 preflight sourceStatus must be PASS")
    if preflight.get("productionStatus") != "PARTIAL":
        errors.append("M5-T15 preflight productionStatus must be PARTIAL")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T15 preflight must not invent device evidence")
    evidence = preflight.get("evidence", {})
    if evidence.get("typedParcelableContracts") != 8:
        errors.append("M5-T15 preflight must record eight typed contracts")
    if evidence.get("newFrameworkHookGroups") != 6:
        errors.append("M5-T15 preflight must record six new hook groups")
    if evidence.get("referenceFilesModified") != 0:
        errors.append("M5-T15 preflight must record zero reference changes")
except Exception as exc:
    errors.append(f"invalid M5-T15 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T15 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
        {"wired": 110, "partial": 2, "not-applicable": 1}
    ):
        errors.append("M5-T15 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
        {"not-tested": 109, "not-applicable": 3, "blocked": 1}
    ):
        errors.append("M5-T15 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t15-peripheral-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T15 gate")

if errors:
    print("FAIL M5-T15 peripheral-services checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)

print(
    "PASS M5-T15 NFC/USB/printing/companion/projection/camera/OEM checks: "
    "source expanded; Android/system-UI/hardware/device limits remain explicit"
)
