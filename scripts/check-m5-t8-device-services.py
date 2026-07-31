#!/usr/bin/env python3
from __future__ import annotations

import json
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


require("docs/plans/M5_T8_DEVICE_SERVICES.md", "Location", "device identity", "Telephony",
        "Wi-Fi", "Bluetooth", "Sensor", "BLOCKED", "STATIC", "HOST")
require("docs/M5_T8_DEVELOPMENT_REPORT.md", "Source status: PASS",
        "Production status: PARTIAL", "Device evidence: 0", "read-only VA/NBB")
require("docs/comparisons/M5_T8_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox",
        "Device evidence remains 0", "native Sensor event queue still partial")
require("README.md", "## M5-T8 device-service virtualization source baseline")
require("docs/ROADMAP.md",
        "## M5-T8 Location, device identity, Telephony, Wi-Fi, Bluetooth and Sensor baseline")

contracts = (
    "VirtualLocationProfileSnapshot", "VirtualDeviceIdentitySnapshot",
    "VirtualTelephonySlotSnapshot", "VirtualTelephonyProfileSnapshot",
    "VirtualWifiNetworkSnapshot", "VirtualWifiProfileSnapshot",
    "VirtualBluetoothDeviceSnapshot", "VirtualBluetoothProfileSnapshot",
    "VirtualSensorSnapshot", "VirtualSensorProfileSnapshot",
    "VirtualDeviceServiceProfileSnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable")

require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualLocationProfileSnapshot.java",
        "MODE_BLOCKED", "MODE_STATIC", "MODE_HOST", "satellitesInView", "nmeaSentence")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getDeviceServiceProfile", "setDeviceServiceProfile", "resetDeviceServiceProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getDeviceServiceProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onDeviceServiceProfileChanged")

require("app/src/main/java/com/warden/controlledsandbox/VirtualDeviceServiceDefaults.java",
        "MODE_BLOCKED", "MODE_STATIC", "Virtual Accelerometer", "mac(seed")
require("app/src/main/java/com/warden/controlledsandbox/VirtualDeviceServiceStore.java",
        "VirtualSystemServiceStore.Scope", "VERSION_CONFLICT", "quarantine")
require("app/src/main/java/com/warden/controlledsandbox/VirtualDeviceServiceStoreCodec.java",
        "static final int SCHEMA", 'put("location"', 'put("telephony"', 'put("wifi"',
        'put("bluetooth"', 'put("sensors"')
require("app/src/main/java/com/warden/controlledsandbox/VirtualDeviceServiceStorePersistence.java",
        "AtomicMoveNotSupportedException", "CRC32", "quarantine", "MAX_FILE_BYTES")
require("app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
        "getDeviceServiceProfile", "setDeviceServiceProfile", "resetDeviceServiceProfile",
        "notifyDeviceProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
        "getDeviceServiceProfile", "setDeviceServiceProfile", "resetDeviceServiceProfile")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "notifyDeviceProfileChanged", "onDeviceServiceProfileChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/DeviceServiceInvocationInterceptor.java",
        'case "location"', '"telephonyregistry", "subscription"', '"wifi", "wifiscanner"',
        'case "bluetooth"', 'case "sensor"', "VIRTUAL_TELEPHONY_MUTATION_DENIED",
        "VIRTUAL_WIFI_MUTATION_DENIED", "VIRTUAL_BLUETOOTH_MUTATION_DENIED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkDeviceObjectFactory.java",
        "subscriptionInfo", "wifiInfo", "bluetoothDevice", "sensor(",
        "allocateWithoutConstructor")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/BuildIdentityHook.java",
        '"BOARD"', '"SERIAL"', "restoreAll")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SettingsProviderIdentityHook.java",
        "android_id", "VIRTUAL_ANDROID_ID_MUTATION_DENIED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SensorCatalogHook.java",
        "mFullSensorsList", "handleContainer", "originalList")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("deviceIdentity"', 'attempt("settingsIdentity"', 'attempt("telephonyRegistry"',
        'attempt("subscription"', 'attempt("wifiScanner"', 'attempt("sensorCatalog"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/DeviceServiceProxyReadiness.java",
        "VIRTUAL_DEVICE_SERVICE_PROXY_REQUIRED", '"telephonyRegistry", "subscription"',
        '"wifi", "wifiScanner"', '"sensorCatalog"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "DeviceServiceProxyReadiness.require")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onDeviceServiceProfileChanged", "getDeviceServiceProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualDeviceServiceStoreSelfTest.java",
        "VERSION_CONFLICT", "corrupt file quarantined", "virtual users receive isolated identities")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/DeviceServiceVirtualizationSelfTest.java",
        "static location replaces host result", "subscription list projected", "Wi-Fi scan results projected",
        "Bluetooth remote identity projected", "virtual sensor sample delivered",
        "HOST location mode passes query and cleanup")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/DeviceServiceProxyReadinessSelfTest.java",
        "PASS M5-T8 device-service proxy readiness self-test")

runner = text("tools/static_android_compile.py")
for test in ("VirtualDeviceServiceStoreSelfTest", "DeviceServiceVirtualizationSelfTest",
             "DeviceServiceProxyReadinessSelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

# Product modules must remain clean-room and must not import the read-only reference packages.
for root in ("app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
             "sandbox-native", "sandbox-native-companion"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"product source imports/reference-copies upstream namespace: {path.relative_to(ROOT)}")

try:
    preflight = json.loads(text("verification/m5-t8-source-preflight.json"))
    if preflight.get("stage") != "M5-T8": errors.append("M5-T8 preflight stage is incorrect")
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T8 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T8 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T8 Android build status must remain blocked-toolchain")
    services = preflight.get("deviceServices", {})
    if services.get("modes") != ["BLOCKED", "STATIC", "HOST"]:
        errors.append("M5-T8 device-service mode contract is incorrect")
    if services.get("isolationKey") != ["packageName", "virtualUserId", "packageRevision"]:
        errors.append("M5-T8 isolation key is incorrect")
    persistence = services.get("persistence", {})
    for key in ("atomic", "bounded", "crcVerified", "corruptStateQuarantine", "optimisticVersioning"):
        if persistence.get(key) is not True: errors.append(f"M5-T8 persistence evidence missing: {key}")
    for domain in ("location", "deviceIdentity", "telephony", "wifi", "bluetooth", "sensors"):
        item = services.get(domain, {})
        if item.get("source") != "complete-for-stage": errors.append(f"{domain} source status is incorrect")
        if item.get("device") != "not-tested": errors.append(f"{domain} device status must be not-tested")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T8 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T8 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T8 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T8 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T8 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t8-device-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T8 gate")

if errors:
    print("FAIL M5-T8 device-service checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T8 device-service checks: six source domains expanded; production/device limits remain explicit")
