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


require("docs/plans/M5_T11_APPLICATION_ENVIRONMENT.md", "UserManager", "LauncherApps",
        "ShortcutManager", "AppWidgetManager", "UsageStatsManager", "Settings Provider",
        "BLOCKED", "STATIC", "HOST")
require("docs/M5_T11_DEVELOPMENT_REPORT.md", "Source status: PASS",
        "Production status: PARTIAL", "Device evidence: 0", "read-only VA/NBB")
require("docs/comparisons/M5_T11_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox",
        "Device evidence remains 0", "ISettingsProviderProxy")
require("README.md", "## M5-T11 User, launcher, shortcut, widget, usage and settings source baseline")
require("docs/ROADMAP.md", "## M5-T11 User, launcher, shortcut, widget, usage and settings baseline")

contracts = (
    "ApplicationEnvironmentProfileSnapshot", "VirtualUserProfileSnapshot", "VirtualLauncherProfileSnapshot",
    "VirtualShortcutPolicySnapshot", "VirtualShortcutSnapshot", "VirtualWidgetPolicySnapshot",
    "VirtualWidgetSnapshot", "VirtualUsageStatsPolicySnapshot", "VirtualUsageEventSnapshot",
    "VirtualSettingsProfileSnapshot", "VirtualSettingSnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java", "Parcelable")

require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualUserProfileSnapshot.java",
        "serialNumber", "quietMode", "restrictions", "applicationRestriction")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualLauncherProfileSnapshot.java",
        "allowStartMainActivity", "allowPackageCallbacks", "maximumListeners", "visiblePackages")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualShortcutPolicySnapshot.java",
        "maximumShortcutsPerActivity", "maximumDynamicShortcuts", "remainingCallCount", "allowPinRequests")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualWidgetPolicySnapshot.java",
        "maximumWidgets", "maximumHosts", "allowBind", "exposeInstalledProviders")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualUsageStatsPolicySnapshot.java",
        "retentionMs", "maximumEvents", "allowReportEvents", "includeOtherPackages")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualSettingsProfileSnapshot.java",
        "NAMESPACE_SECURE", "NAMESPACE_SYSTEM", "NAMESPACE_GLOBAL", "writeAllowed", "keyBlocked")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getApplicationEnvironmentProfile", "setApplicationEnvironmentProfile",
        "resetApplicationEnvironmentProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getApplicationEnvironmentProfile", "listShortcuts", "replaceDynamicShortcuts",
        "allocateAppWidgetId", "reportUsageEvent", "queryUsageEvents", "getSetting", "putSetting")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onApplicationEnvironmentProfileChanged", "onApplicationEnvironmentDataChanged")

require("app/src/main/java/com/warden/controlledsandbox/ApplicationEnvironmentDefaults.java",
        "ApplicationEnvironmentProfileSnapshot", "MODE_STATIC")
require("app/src/main/java/com/warden/controlledsandbox/ApplicationEnvironmentStore.java",
        "APPLICATION_ENV_PROFILE_VERSION_CONFLICT", "replaceDynamicShortcuts", "allocateAppWidgetId",
        "reportUsageEvent", "putSetting", "USAGE_EVENT_PACKAGE_DENIED")
require("app/src/main/java/com/warden/controlledsandbox/ApplicationEnvironmentStorePersistence.java",
        "CRC32", ".corrupt", "ATOMIC_MOVE")
require("app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
        "getApplicationEnvironmentProfile", "setApplicationEnvironmentProfile",
        "resetApplicationEnvironmentProfile", "notifyApplicationEnvironmentProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "onApplicationEnvironmentProfileChanged", "onApplicationEnvironmentDataChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java",
        'case "usermanager"', 'case "launcherapps"', 'case "shortcut"', 'case "appwidget"',
        'case "usagestats"', 'case "content"', "VIRTUAL_APPLICATION_ENVIRONMENT")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkApplicationEnvironmentObjectFactory.java",
        "userInfo", "launcherActivity", "shortcut", "appWidgetInfo", "usageEvent")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("userManager"', 'attempt("launcherApps"', 'attempt("shortcut"',
        'attempt("appWidget"', 'attempt("usageStats"', 'attempt("content"')
for hook in ("UserManagerServiceHook", "LauncherAppsServiceHook", "ShortcutManagerServiceHook",
             "AppWidgetManagerServiceHook", "UsageStatsManagerServiceHook", "ContentServiceHook"):
    require(f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
            "ReflectiveServiceHook")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SettingsProviderIdentityHook.java",
        "NAMESPACE_SECURE", "NAMESPACE_SYSTEM", "NAMESPACE_GLOBAL", "putSetting", "deleteSetting")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/ApplicationEnvironmentProxyReadiness.java",
        "VIRTUAL_APPLICATION_ENVIRONMENT_PROXY_REQUIRED", '"userManager"', '"launcherApps"',
        '"shortcut"', '"appWidget"', '"usageStats"', '"content"', '"settingsIdentity"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "ApplicationEnvironmentProxyReadiness.require")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onApplicationEnvironmentProfileChanged", "onApplicationEnvironmentDataChanged",
        "getApplicationEnvironmentProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/ApplicationEnvironmentStoreSelfTest.java",
        "shortcut scope isolation", "deterministic widget namespace", "cross-package usage must be denied",
        "blocked setting write", "optimistic version conflict", "corrupt store must fail closed",
        "PASS M5-T11 application-environment store self-test")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentVirtualizationSelfTest.java",
        "virtual user handle", "launcher activity projection", "shortcut usage count", "widget allocation",
        "usage query", "content observer delivery", "sync adapters fail closed", "HOST user manager passthrough",
        "PASS M5-T11 application-environment virtualization self-test")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/ApplicationEnvironmentProxyReadinessSelfTest.java",
        "missing application-environment hooks must block launch",
        "PASS M5-T11 application-environment proxy readiness self-test")

runner = text("tools/static_android_compile.py")
for test in ("ApplicationEnvironmentStoreSelfTest", "ApplicationEnvironmentVirtualizationSelfTest",
             "ApplicationEnvironmentProxyReadinessSelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

for root in ("app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
             "sandbox-native", "sandbox-native-companion"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"product source imports/reference-copies upstream namespace: {path.relative_to(ROOT)}")
        if "VirtualApp" in value:
            errors.append(f"product source contains ambiguous upstream token VirtualApp: {path.relative_to(ROOT)}")

try:
    preflight = json.loads(text("verification/m5-t11-source-preflight.json"))
    if preflight.get("stage") != "M5-T11": errors.append("M5-T11 preflight stage is incorrect")
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T11 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T11 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T11 Android build status must remain blocked-toolchain")
    services = preflight.get("applicationEnvironment", {})
    if services.get("modes") != ["BLOCKED", "STATIC", "HOST"]:
        errors.append("M5-T11 mode contract is incorrect")
    if services.get("isolationKey") != ["packageName", "virtualUserId"]:
        errors.append("M5-T11 isolation key is incorrect")
    persistence = services.get("persistence", {})
    for key in ("atomic", "bounded", "crcVerified", "corruptStateQuarantine", "optimisticVersioning"):
        if persistence.get(key) is not True: errors.append(f"M5-T11 persistence evidence missing: {key}")
    for domain in ("userManager", "launcherApps", "shortcut", "appWidget", "usageStats", "settingsContent"):
        item = services.get(domain, {})
        if item.get("source") != "complete-for-stage": errors.append(f"{domain} source status is incorrect")
        if item.get("device") != "not-tested": errors.append(f"{domain} device status must be not-tested")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T11 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T11 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T11 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T11 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T11 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t11-application-environment.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T11 gate")

if errors:
    print("FAIL M5-T11 application-environment checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T11 application-environment checks: User/Launcher/Shortcut/Widget/Usage/Settings source expanded; device limits remain explicit")
