#!/usr/bin/env python3
"""Static gate for C4-T05 SX F1-F5 / DingTalk / 100-round business."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
errors: list[str] = []


def require(relative: str, tokens: tuple[str, ...]) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing {token}")
    for serial in FORBIDDEN_SERIALS:
        if serial in text:
            errors.append(f"{relative} contains historical ADB serial")
    return text


def main() -> int:
    require(
        "docs/review/C4_T05_SX_BUSINESS_DESIGN.md",
        ("DISCOVER", "CLASSIFY", "F1 camera", "F2 location", "F4 network",
         "F5 bluetooth", "F3 device", "7.8.10", "1178", "100",
         "PrivacyPolicy", "NOT_PROVEN", "default-off", "KI-R03-050"),
    )
    debug = require(
        "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
        ("c4-t05-sx-business", "c4-t05-dingtalk", "CameraCampaignActivity",
         "LocationCampaignActivity", "C2T06DeviceNetworkMediaActivity",
         "C2T05SchedulingInteractionActivity", "RemoteActivity",
         "DINGTALK_SPECIALIZATION_LEAKED_ONTO_FIXTURE",
         "GENERIC_PROFILE_MUTATED_AFTER_SPECIALIZATION_OFF",
         "DUMPSYS_REQUIRED", "RUNNER_HOME", "getInt(\"loops\", 100)",
         "skipSurfaces", "skipLoops", "android.permission.CAMERA",
         "ACCESS_FINE_LOCATION"),
    )
    if "BlackBoxCore" in debug.split("c4-t05-sx-business", 1)[-1].split(
            "c4-t03-migrate", 1)[0]:
        errors.append("c4-t05 commands must not reference BlackBoxCore")
    require(
        "app/src/main/java/com/warden/controlledsandbox/compatibility/dingtalk/DingTalkCompatibilityManager.java",
        ("7.8.10", "1178", "com.alibaba.android.rimet", "DEFAULT_OFF"),
    )
    runner = ROOT / "tools/capability/run_c4_t05_rd.py"
    if not runner.is_file():
        errors.append("missing tools/capability/run_c4_t05_rd.py")
    else:
        text = runner.read_text(encoding="utf-8")
        for token in ("c4-t05-sx-business", "c4-t05-dingtalk", "loops", "100",
                      "PrivacyPolicyActivity", "FORBIDDEN_SERIALS", "RD测试",
                      "skipSurfaces", "skipLoops", "StubActivity",
                      "GUEST_ACTIVITY_CREATE"):
            if token not in text:
                errors.append(f"tools/capability/run_c4_t05_rd.py missing {token}")
        if "FORBIDDEN_SERIALS" not in text:
            errors.append("runner must declare FORBIDDEN_SERIALS")
        for serial in FORBIDDEN_SERIALS:
            uses = [line for line in text.splitlines() if serial in line]
            unexpected = [line for line in uses if "FORBIDDEN_SERIALS" not in line
                          and "forbidden" not in line.lower()]
            if unexpected:
                errors.append("runner uses historical ADB serial outside the forbid list")
    require(
        "docs/review/KNOWN_ISSUES.yaml",
        ("KI-R03-050", "C4-T05"),
    )
    if errors:
        print("FAIL C4-T05 SX F1-F5 / DingTalk business")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C4-T05 SX F1-F5 / DingTalk business")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
