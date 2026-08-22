#!/usr/bin/env python3
"""Static contract gate for the C2-T05 scheduling and interaction campaign."""

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
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T05SchedulingInteractionActivity.java",
    "NotificationChannel", "notify(tag, id, notification)", "getActiveNotifications",
    "getNotificationChannel", "PendingIntent", "setExactAndAllowWhileIdle",
    "JobInfo.Builder", "setRequiredNetworkType", "getPendingJob", "FixtureJobService",
    "startForegroundService", "C2_T05_FGS_RETURN", "getWindowToken",
    "InputMethodManager", "getEnabledInputMethodList", "DisplayManager",
    "createDisplayContext", "C2_T05_INTERACTION_CLEANUP", "C2_T05_ALARM_CALLBACK",
    "C2_T05_NOTIFICATION_CLICK_CALLBACK",
)
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T05EventReceiver.java",
    "ACTION_NOTIFICATION_CLICK", "ACTION_NOTIFICATION_DELETE", "ACTION_EXACT_ALARM",
    "C2_T05_NOTIFICATION_CLICK_CALLBACK", "C2_T05_ALARM_CALLBACK",
)
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/FixtureService.java",
    "C2_T05_FGS", "C2_T05_FGS_PROMOTED", "foregroundType",
)
require(
    "fixture-basic/src/main/AndroidManifest.xml",
    "C2T05SchedulingInteractionActivity", "C2T05EventReceiver",
    "android:foregroundServiceType=\"dataSync\"",
)
require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    "c2t05Mode", "c2t05Loops", "componentIntentExtras",
)
require(
    "tools/capability/run_c2_t05_rd.py",
    "resolve_rd_environment", "install_rd_apks", "C2_T05_CAMPAIGN_PASS",
    "C2_T05_ALARM_CALLBACK", "C2_T05_FGS_PROMOTED", "old_pid_dead",
    "dumpsys", "--loops",
)
require(
    "docs/review/C2_T05_SCHEDULING_INTERACTION_DESIGN.md",
    "request/return/callback/death", "exact alarm", "JobInfo", "Window", "IME",
    "Display", "RD_BASELINE", "NOT_PROVEN",
)
for relative, tokens in {
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/VirtualSystemServiceInterceptor.java": (
        'case "alarm"', 'case "notification"', 'case "jobscheduler"',
        "pendingIntentTokenId", "rewriteActiveNotifications", "rewriteSingleJobResult",
    ),
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InteractionServiceInvocationInterceptor.java": (
        'case "window"', 'case "inputmethod"', 'case "display"',
        "VIRTUAL_WINDOW_TOKEN_NOT_OWNED",
        "VIRTUAL_DISPLAY_MUTATION_DENIED",
    ),
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/GuestInteractionState.java": (
        "closeSession", "inputMethods", "displays", "windowCount",
        "VIRTUAL_INPUT_SESSION_LIMIT_EXCEEDED",
    ),
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java": (
        "scheduleAlarm", "commitNotification", "commitJob", "deleteScopeBestEffort",
    ),
}.items():
    require(relative, *tokens)

try:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))
    from run_rd_campaign import FORBIDDEN_SERIALS

    runner = read("tools/capability/run_c2_t05_rd.py")
    for forbidden in FORBIDDEN_SERIALS:
        if forbidden in runner:
            errors.append(f"runner contains historical ADB endpoint {forbidden}")
except Exception as error:  # pragma: no cover
    errors.append(f"could not load RD serial policy: {error}")

if errors:
    print("FAIL C2-T05 scheduling/interaction static checks")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)

print("PASS C2-T05 scheduling/interaction static checks")
