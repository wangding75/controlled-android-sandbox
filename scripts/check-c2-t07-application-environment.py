#!/usr/bin/env python3
"""Static contract gate for the C2-T07 application-environment campaign."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *tokens: str) -> None:
    text = read(relative)
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing {token!r}")


require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T07ApplicationEnvironmentActivity.java",
    "C2_T07_USER_RETURN", "C2_T07_LAUNCHER_RETURN", "registerCallback",
    "unregisterCallback", "C2_T07_SHORTCUT_RETURN", "addDynamicShortcuts",
    "removeDynamicShortcuts", "C2_T07_WIDGET_RETURN", "C2_T07_USAGE_RETURN",
    "queryUsageStats", "C2_T07_SETTINGS_RETURN", "C2_T07_SETTINGS_GLOBAL_DENIED",
    "registerContentObserver", "unregisterContentObserver",
    "notifyChange(uri, null)",
    "C2_T07_CONTENT_OBSERVER_CALLBACK", "C2_T07_STORAGE_RETURN",
    "C2_T07_LONGTAIL_MATRIX", "C2_T07_LONGTAIL_NEGATIVE",
    "C2_T07_HOST_IDENTITY_GUARDED", "C2_T07_CLEANUP", "C2_T07_CAMPAIGN_PASS",
)
require(
    "fixture-basic/src/main/AndroidManifest.xml",
    "C2T07ApplicationEnvironmentActivity",
)
require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    "componentIntentExtras", "c2t07Mode", "c2t07Loops",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java",
    "case \"usermanager\"", "case \"launcherapps\"", "case \"shortcut\"",
    "case \"appwidget\"", "case \"usagestats\"", "case \"content\"",
    "VIRTUAL_",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkApplicationEnvironmentObjectFactory.java",
    "LauncherActivityInfoInternal", "IncrementalStatesInfo", "launcherServiceActivity",
    "AndroidFuture", "completedFuture", "ParceledListSlice",
)
require(
    "sandbox-native/src/main/cpp/native_policy_jni.cpp",
    "Lcom/android/internal/infra/AndroidFuture;",
    "never open com/android/internal/",
)
for hook, descriptor in {
    "UserManagerServiceHook.java": "android.os.IUserManager",
    "LauncherAppsServiceHook.java": "android.content.pm.ILauncherApps",
    "ShortcutManagerServiceHook.java": "android.content.pm.IShortcutService",
    "AppWidgetManagerServiceHook.java": "com.android.internal.appwidget.IAppWidgetService",
    "UsageStatsManagerServiceHook.java": "android.app.usage.IUsageStatsManager",
}.items():
    require(
        "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/" + hook,
        "managerFieldCandidatesOrServiceManagerBinding", descriptor,
    )
require(
    "docs/review/C2_T07_APPLICATION_ENVIRONMENT_LONG_TAIL_DESIGN.md",
    "request/return", "cross-user", "RD_API32_L3", "NOT_PROVEN",
)
require(
    "docs/review/KNOWN_ISSUES.yaml",
    "KI-R03-041", "status: FIXED",
    "PACKAGE_NEUTRAL_C2_T07_ENVIRONMENT_AND_LONG_TAIL_RD_CAMPAIGN",
    "verification/catch-up/C2-T07/c1-full-regression",
)
require(
    "docs/capability/CAPABILITY_REGISTRY.yaml",
    "docs/review/C2_T07_APPLICATION_ENVIRONMENT_LONG_TAIL_DESIGN.md",
    "verification/catch-up/C2-T07/c2-t07-rd-summary.json",
    "verification/catch-up/C2-T07/c2-t07-local-verification.json",
)
require(
    "docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml",
    "VA-584", "C2-T07 StorageStatsManager profile query",
    "keeps VA PRO equivalence NOT_PROVEN",
)

try:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))
    from run_rd_campaign import FORBIDDEN_SERIALS

    runner = read("tools/capability/run_c2_t07_rd.py")
    for forbidden in FORBIDDEN_SERIALS:
        if forbidden in runner:
            errors.append(f"runner contains historical ADB endpoint {forbidden}")
except Exception as error:  # pragma: no cover
    errors.append(f"could not load RD serial policy: {error}")

if errors:
    print("FAIL C2-T07 application-environment static checks")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)

print("PASS C2-T07 application-environment static checks")
