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


require("docs/plans/M5_T12_WEBVIEW_GMS_OEM_DETECTION.md", "WebView", "Google services",
        "OEM compatibility", "Detection governance", "BLOCKED", "STATIC", "HOST")
require("docs/M5_T12_DEVELOPMENT_REPORT.md", "Source status: PASS",
        "Production status: PARTIAL", "Device evidence: 0", "read-only VA/NBB")
require("docs/comparisons/M5_T12_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox",
        "Device evidence remains 0", "AntiDetection.cpp")
require("README.md", "## M5-T12 WebView, GMS, OEM and detection source baseline")
require("docs/ROADMAP.md", "## M5-T12 WebView, GMS, OEM and detection baseline")

contracts = (
    "VirtualCompatibilityProfileSnapshot", "VirtualWebViewProfileSnapshot",
    "VirtualGoogleServicesProfileSnapshot", "VirtualOemProfileSnapshot",
    "VirtualDetectionPolicySnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java", "Parcelable")

require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualWebViewProfileSnapshot.java",
        "dataDirectorySuffix", "rendererProcessPrefix", "maximumRendererProcesses",
        "safeBrowsingEnabled", "debuggingAllowed")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualGoogleServicesProfileSnapshot.java",
        "playServicesAvailable", "advertisingId", "limitAdTracking", "appSetId", "gsfId",
        "visibleAccountTypes", "enabledApis")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualOemProfileSnapshot.java",
        "propertyKeys", "propertyValues", "availableServices", "blockedPackages", "attributionId")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualDetectionPolicySnapshot.java",
        "hideHostPackage", "sanitizeProcFiles", "maskDebugger", "maskRootArtifacts",
        "sanitizeStackTraces", "maximumSuspiciousQueries", "hiddenClassPrefixes")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getCompatibilityProfile", "setCompatibilityProfile", "resetCompatibilityProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getCompatibilityProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onCompatibilityProfileChanged")

require("app/src/main/java/com/warden/controlledsandbox/VirtualCompatibilityDefaults.java",
        "VirtualCompatibilityProfileSnapshot", "MODE_STATIC", "nameUUIDFromBytes")
require("app/src/main/java/com/warden/controlledsandbox/VirtualCompatibilityStore.java",
        "COMPATIBILITY_PROFILE_VERSION_CONFLICT", "deleteScopeBestEffort", "scope limit exceeded")
require("app/src/main/java/com/warden/controlledsandbox/VirtualCompatibilityStorePersistence.java",
        "CRC32", ".corrupt", "ATOMIC_MOVE")
require("app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
        "getCompatibilityProfile", "setCompatibilityProfile", "resetCompatibilityProfile",
        "notifyCompatibilityProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
        "compatibilityProfile", "setCompatibilityProfile", "resetCompatibilityProfile")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "onCompatibilityProfileChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/CompatibilityInvocationInterceptor.java",
        'case "webviewupdate"', 'case "deviceidentifiers"', 'case "gms"', 'case "oemidentifier"',
        "VIRTUAL_WEBVIEW_MUTATION_DENIED", "VIRTUAL_GOOGLE_SERVICE_UNAVAILABLE")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkCompatibilityObjectFactory.java",
        "webViewPackageInfo", "packageInfo", "applicationInfo", "status")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ServiceManagerBinderHook.java",
        "queryLocalInterface", "installDiscovered", "getInterfaceDescriptor")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("webViewUpdate"', 'attempt("deviceIdentifiers"',
        'attempt("googleServiceBroker"', 'attempt("oemIdentifiers"')
for hook in ("WebViewUpdateServiceHook", "DeviceIdentifiersServiceHook",
             "GoogleServiceBrokerHook", "OemIdentifierServiceHook"):
    require(f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
            "ServiceManagerBinderHook")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/BuildIdentityHook.java",
        "compatibilityProfile", '"DISPLAY"', '"FINGERPRINT"')
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SettingsProviderIdentityHook.java",
        "advertising_id", "limit_ad_tracking", "app_set_id", "gsf_id", "firebase_installation_id")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java",
        "containsHiddenPackage", "hiddenPackageNames", "hideHostPackage")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/WebViewProfileManager.java",
        "setDataDirectorySuffix", "WebViewRendererRegistry", "rendererProcessPrefix",
        "maximumRendererProcesses")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/WebViewRendererRegistry.java",
        "VIRTUAL_WEBVIEW_RENDERER_LIMIT", "VIRTUAL_WEBVIEW_MULTIPROCESS_DISABLED", "release")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestClassLoader.java",
        "configureDetection", "hiddenClassPrefixes", "maximumSuspiciousQueries",
        "Class is hidden by Guest detection policy")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/CompatibilityProxyReadiness.java",
        "VIRTUAL_WEBVIEW_UPDATE_PROXY_REQUIRED", "VIRTUAL_DEVICE_IDENTIFIERS_PROXY_REQUIRED",
        "VIRTUAL_GOOGLE_SERVICE_BROKER_REQUIRED", "VIRTUAL_OEM_IDENTIFIER_PROXY_REQUIRED",
        "VIRTUAL_DETECTION_NATIVE_POLICY_REQUIRED")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "configureDetection", "CompatibilityProxyReadiness.require", "webViewProfile.renderers.close")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onCompatibilityProfileChanged", "getCompatibilityProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualCompatibilityStoreSelfTest.java",
        "per-user Google identity isolation", "optimistic profile update", "stale update rejected",
        "profile persisted", "corrupt store quarantined", "PASS M5-T12 compatibility profile store self-test")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/CompatibilityVirtualizationSelfTest.java",
        "WebView provider projected", "WebView provider response projected",
        "GMS availability and account types projected", "OEM attribution projected",
        "HOST WebView passes through", "PASS M5-T12 compatibility virtualization self-test")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/CompatibilityProxyReadinessSelfTest.java",
        "missing identifiers hook blocks", "available GMS requires broker hook",
        "configured OEM service requires hook", "missing native detection policy blocks",
        "PASS M5-T12 compatibility proxy readiness self-test")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/WebViewProfileSelfTest.java",
        "renderer process prefix", "renderer quota", "renderer shutdown cleanup")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestClassLoaderSelfTest.java",
        "policy-hidden class query", "HOST mode resets detection ledger")

runner = text("tools/static_android_compile.py")
for test in ("VirtualCompatibilityStoreSelfTest", "CompatibilityVirtualizationSelfTest",
             "CompatibilityProxyReadinessSelfTest", "WebViewProfileSelfTest", "GuestClassLoaderSelfTest"):
    if runner.count("'" + "com.warden.controlledsandbox" + (".framework.core." if test == "CompatibilityVirtualizationSelfTest" else ".runtime.guest." if test in {"CompatibilityProxyReadinessSelfTest", "WebViewProfileSelfTest", "GuestClassLoaderSelfTest"} else ".") + test + "'") != 1:
        errors.append(f"static Android compiler must execute {test} exactly once")

for root in ("app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
             "sandbox-native", "sandbox-native-companion"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            # M5-T12 policy defaults/tests may contain upstream namespace strings solely as values to hide.
            if path.name not in {"VirtualCompatibilityDefaults.java", "GuestClassLoaderSelfTest.java"}:
                errors.append(f"product source imports/reference-copies upstream namespace: {path.relative_to(ROOT)}")
        if "VirtualApp" in value:
            errors.append(f"product source contains ambiguous upstream token VirtualApp: {path.relative_to(ROOT)}")

try:
    preflight = json.loads(text("verification/m5-t12-source-preflight.json"))
    if preflight.get("stage") != "M5-T12": errors.append("M5-T12 preflight stage is incorrect")
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T12 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T12 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T12 Android build status must remain blocked-toolchain")
    compatibility = preflight.get("compatibility", {})
    if compatibility.get("modes") != ["BLOCKED", "STATIC", "HOST"]:
        errors.append("M5-T12 mode contract is incorrect")
    if compatibility.get("isolationKey") != ["packageName", "virtualUserId"]:
        errors.append("M5-T12 isolation key is incorrect")
    if compatibility.get("runtimeRevisionBound") is not True:
        errors.append("M5-T12 Runtime access must remain revision-bound")
    persistence = compatibility.get("persistence", {})
    for key in ("atomic", "bounded", "crcVerified", "corruptStateQuarantine", "optimisticVersioning"):
        if persistence.get(key) is not True: errors.append(f"M5-T12 persistence evidence missing: {key}")
    for domain in ("webView", "googleServices", "oemServices", "detectionGovernance"):
        item = compatibility.get(domain, {})
        if item.get("source") != "complete-for-stage": errors.append(f"{domain} source status is incorrect")
        if item.get("device") != "not-tested": errors.append(f"{domain} device status must be not-tested")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T12 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T12 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T12 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T12 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T12 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t12-webview-gms-oem-detection.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T12 gate")

if errors:
    print("FAIL M5-T12 WebView/GMS/OEM/detection checks", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T12 WebView/GMS/OEM/detection checks: source expanded; Android/GMS/OEM/device limits remain explicit")
