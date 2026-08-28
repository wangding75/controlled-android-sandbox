#!/usr/bin/env python3
"""C4-T05 RD campaign: SX F1-F5 call surfaces, DingTalk 7.8.10/1178, 100 loops."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    CampaignBlocked,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C4-T05"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C4-T05"
DINGTALK_PACKAGE = "com.alibaba.android.rimet"
LOGIN_MARKERS = (
    "PrivacyPolicyActivity",
    "LoginActivity",
    "UserLoginActivity",
    "SignUpActivity",
    "HomeActivity",
    "LaunchHomeActivity",
)
HOSTED_MARKERS = (
    "StubActivity",
    ":guest",
    "GUEST_ACTIVITY_CREATE",
)
F_SURFACE_MARKERS = {
    "f1_camera": ("C2_T04_CAMERA_SMOKE_PASS",),
    "f2_location": ("C2_T03_LOCATION_CALLBACK", "C2_T03_LOCATION_PROBE"),
    "f4_network": ("C2_T06_CAMPAIGN_PASS", "C2_T06_LOOP_PASS"),
    "f5_bluetooth": ("C2_T06_BLUETOOTH_RETURN", "C2_T06_BLUETOOTH_UNSUPPORTED"),
    "f3_device": ("C2_T06_CAMPAIGN_PASS", "profileHash="),
    "fgs": ("C2_T05_FGS_STOP_PASS", "C2_T05_FGS_RETURN"),
    "notification": ("C2_T05_NOTIFICATION_PASS", "C2_T05_NOTIFICATION_RETURN"),
    "job": ("C2_T05_JOB_CALLBACK_PASS", "C2_T05_JOB_RETURN"),
    "webview": ("ACTIVITY_CREATE", "ACTIVITY_RESUME"),
}


def run_cmd(args: list[str], gate: str) -> dict[str, Any]:
    completed = subprocess.run(
        args, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
        capture_output=True, check=False,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    )
    return {
        "gate": gate,
        "returncode": completed.returncode,
        "stdout": (completed.stdout or "")[-4000:],
        "stderr": (completed.stderr or "")[-2000:],
    }


def logcat_dump(serial: str) -> str:
    return run_adb(
        serial,
        ["logcat", "-d", "-v", "threadtime",
         "CS_C2_T04_CAMERA:I", "CS_C2_T03_LOCATION:I", "CS_C2_T06:I",
         "CS_C2_T05:I", "CS_FIXTURE:I", "CS_COMMAND:I", "AndroidRuntime:E",
         "*:S"],
        check=False,
    ).stdout


def dumpsys_activities(serial: str) -> str:
    return run_adb(
        serial, ["shell", "dumpsys", "activity", "activities"], check=False,
    ).stdout


def wait_for_activity_evidence(serial: str, predicate: Any, *, deadline_sec: float = 10.0) -> str:
    """Poll activity evidence until the caller's dynamic predicate is satisfied.

    This is deliberately readiness-based: a fixed delay is not evidence that a retained
    hosted task or foreground re-entry is ready.  The last dump is returned so the caller
    can preserve it even when the deadline expires.
    """
    deadline = time.monotonic() + deadline_sec
    latest = ""
    while True:
        latest = dumpsys_activities(serial)
        if predicate(latest):
            return latest
        if time.monotonic() >= deadline:
            return latest
        time.sleep(0.25)


def require_markers(blob: str, required: dict[str, tuple[str, ...]]) -> list[str]:
    missing: list[str] = []
    for name, markers in required.items():
        if not any(marker in blob for marker in markers):
            missing.append(f"{name} missing any of {markers}")
    return missing


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    parser.add_argument("--verification-dir", type=Path, default=VERIFICATION)
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c4-t05")
    verification = args.verification_dir
    verification.mkdir(parents=True, exist_ok=True)
    historical_output = verification.resolve() == VERIFICATION.resolve()
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    surfaces: dict[str, Any] = {}
    business: dict[str, Any] = {}
    dingtalk: dict[str, Any] = {}
    login_dump = ""
    bg_dump = ""
    fg_dump = ""
    dt_log = ""
    log_text = ""
    assembled = {"returncode": 1, "gate": "assembleDebug device APKs"}
    try:
        static_rows.append(run_cmd(
            [sys.executable, str(ROOT / "scripts/check-c4-t05-sx-business.py")],
            "scripts/check-c4-t05-sx-business.py",
        ))
        assembled = run_cmd(
            [
                str(ROOT / "gradlew.bat"),
                ":app:assembleDebug",
                ":fixture-basic:assembleDebug",
                ":sandbox-companion32:assembleDebug",
                ":fixture-compat32:assembleDebug",
                "--no-daemon",
            ],
            "assembleDebug device APKs",
        )
        static_rows.append(assembled)
        static_rows.append(run_cmd(
            [sys.executable, str(ROOT / "scripts/check-c4-t04-cas-only-runtime.py")],
            "scripts/check-c4-t04-cas-only-runtime.py",
        ))
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        write_json(output / "environment.json", environment)
        serial = str(environment["adb_serial"])
        install_rd_apks(serial)
        run_adb(
            serial,
            ["shell", "pm", "grant", HOST_PACKAGE, "android.permission.CAMERA"],
            check=False,
        )
        run_adb(serial, ["logcat", "-G", "16M"], check=False)
        run_adb(serial, ["logcat", "-c"], check=False)
        surfaces = debug_command(
            serial,
            [
                "--es", "command", "c4-t05-sx-business",
                "--es", "package", GUEST_PACKAGE,
                "--ei", "user", "0",
                "--ei", "loops", "0",
                "--ez", "skipLoops", "true",
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=240,
        )
        write_json(output / "c4-t05-sx-surfaces.json", surfaces)
        log_text = logcat_dump(serial)
        (output / "c4-t05-sx-business-logcat.txt").write_text(log_text, encoding="utf-8")
        if str(surfaces.get("status") or "").upper() != "PASS":
            raise RuntimeError(
                f"c4-t05-sx-business surfaces failed: {json.dumps(surfaces, ensure_ascii=False)[:2500]}"
            )
        surface_campaign = (surfaces.get("result") or {}).get("c4t05") or {}
        if not surface_campaign.get("pass"):
            raise RuntimeError(f"sx surface campaign did not pass: {surface_campaign}")
        if surface_campaign.get("dingTalkEnabledOnFixture") is not False:
            raise RuntimeError("DingTalk specialization leaked onto generic fixture")
        missing = require_markers(log_text, F_SURFACE_MARKERS)
        if missing:
            raise RuntimeError("F1-F5/call-surface markers missing: " + "; ".join(missing))
        business = debug_command(
            serial,
            [
                "--es", "command", "c4-t05-sx-business",
                "--es", "package", GUEST_PACKAGE,
                "--ei", "user", "0",
                "--ei", "loops", "100",
                "--ez", "skipSurfaces", "true",
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=1500,
        )
        write_json(output / "c4-t05-sx-business.json", business)
        if str(business.get("status") or "").upper() != "PASS":
            raise RuntimeError(
                f"c4-t05-sx-business loops failed: {json.dumps(business, ensure_ascii=False)[:2500]}"
            )
        campaign = (business.get("result") or {}).get("c4t05") or {}
        if not campaign.get("pass"):
            raise RuntimeError(f"sx business campaign did not pass: {campaign}")
        if int(campaign.get("loops") or 0) != 100:
            raise RuntimeError(f"expected 100 loops, got {campaign.get('loops')}")
        if campaign.get("dingTalkEnabledOnFixture") is not False:
            raise RuntimeError("DingTalk specialization leaked onto 100-loop fixture")
        dingtalk = debug_command(
            serial,
            [
                "--es", "command", "c4-t05-dingtalk",
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=240,
        )
        write_json(output / "c4-t05-dingtalk.json", dingtalk)
        if str(dingtalk.get("status") or "").upper() != "PASS":
            raise RuntimeError(
                f"c4-t05-dingtalk failed: {json.dumps(dingtalk, ensure_ascii=False)[:2500]}"
            )
        dt_campaign = (dingtalk.get("result") or {}).get("c4t05DingTalk") or {}
        if str(dt_campaign.get("versionName")) != "7.8.10":
            raise RuntimeError(f"DingTalk versionName {dt_campaign.get('versionName')}")
        if int(dt_campaign.get("versionCode") or 0) != 1178:
            raise RuntimeError(f"DingTalk versionCode {dt_campaign.get('versionCode')}")
        if dt_campaign.get("defaultEnabled") is not False:
            raise RuntimeError("DingTalk compatibility was not default-off")
        for key in ("cold", "hot", "upgrade"):
            if not dt_campaign.get(key):
                raise RuntimeError(f"DingTalk missing {key}: {dt_campaign}")
        login_dump = dumpsys_activities(serial)
        (output / "c4-t05-dingtalk-dumpsys-fg.txt").write_text(login_dump, encoding="utf-8")
        dt_log = run_adb(
            serial,
            ["logcat", "-d", "-v", "threadtime", "CS_RUNTIME:I", "*:S"],
            check=False,
        ).stdout
        (output / "c4-t05-dingtalk-runtime-logcat.txt").write_text(dt_log, encoding="utf-8")
        hosted = ("StubActivity" in login_dump and ":guest" in login_dump)
        login_hit = any(marker in dt_log or marker in login_dump for marker in LOGIN_MARKERS)
        package_hit = DINGTALK_PACKAGE in dt_log or DINGTALK_PACKAGE in login_dump
        if not hosted:
            raise RuntimeError("dumpsys after DingTalk launch has no CAS guest stub task")
        if not login_hit and not package_hit:
            raise RuntimeError(
                "DingTalk login/pre-login surface not observed in stub dumpsys or CS_RUNTIME log"
            )
        if "GUEST_ACTIVITY_CREATE" not in dt_log and not login_hit:
            raise RuntimeError("no GUEST_ACTIVITY_CREATE for DingTalk launch")
        run_adb(serial, ["shell", "input", "keyevent", "KEYCODE_HOME"], check=False)
        bg_dump = wait_for_activity_evidence(
            serial,
            lambda dump: "StubActivity" in dump or DINGTALK_PACKAGE in dump,
        )
        (output / "c4-t05-dingtalk-dumpsys-bg.txt").write_text(bg_dump, encoding="utf-8")
        if "StubActivity" not in bg_dump and DINGTALK_PACKAGE not in bg_dump:
            raise RuntimeError("DingTalk hosted task disappeared after HOME")
        fg = debug_command(
            serial,
            [
                "--es", "command", "launch",
                "--es", "package", DINGTALK_PACKAGE,
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=120,
            force_stop_host=False,
        )
        write_json(output / "c4-t05-dingtalk-fg-reentry.json", fg)
        if str(fg.get("status") or "").upper() != "PASS":
            raise RuntimeError(f"DingTalk foreground re-entry failed: {fg}")
        operation = ((fg.get("result") or {}).get("operation") or {})
        if operation.get("firstFrameDrawn") is not True:
            raise RuntimeError(f"DingTalk foreground re-entry missing FIRST_FRAME_DRAWN: {operation}")
        if not operation.get("windowEvidence"):
            raise RuntimeError(f"DingTalk foreground re-entry missing window evidence: {operation}")
        fg_dump = wait_for_activity_evidence(
            serial,
            lambda dump: "StubActivity" in dump or any(marker in dump for marker in LOGIN_MARKERS),
        )
        (output / "c4-t05-dingtalk-dumpsys-reentry.txt").write_text(fg_dump, encoding="utf-8")
        if "StubActivity" not in fg_dump and not any(marker in fg_dump for marker in LOGIN_MARKERS):
            raise RuntimeError("DingTalk foreground re-entry has no hosted stub/login surface")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "evidence_status": "SUPERSEDED" if historical_output else "CURRENT_REGRESSION",
        "historical_only": historical_output,
        "usable_for_c4_closure": False,
        "superseded_by": "C4-R01" if historical_output else "",
        "evidence_role": "HISTORICAL_C4_T05" if historical_output else "C4_R05_REGRESSION_COMPONENT",
        "capability": "package_lifecycle_clear_delete_reinstall",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
        "device_name": str(environment.get("device_name") or ""),
        "adb_serial": str(environment.get("adb_serial") or ""),
        "api_level": environment.get("api_level"),
        "abi": str(environment.get("abi") or ""),
        "boot_id": str(environment.get("boot_id") or ""),
        "android_id": str(environment.get("android_id") or ""),
        "instance_name": str(environment.get("instance_name") or args.instance),
        "build_result": "PASS" if assembled["returncode"] == 0 else "FAIL",
        "static_result": "PASS" if static_rows and all(row["returncode"] == 0 for row in static_rows) else "FAIL",
        "targeted_result": "PASS" if not errors else "FAIL",
        "rd_result": "PASS" if not errors else "FAIL",
        "regression_result": "NOT_APPLICABLE",
        "failures": [
            {"id": "C4-T05-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": ["KI-R03-050 C4-T05 SX F1-F5 DingTalk 100-loop"],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "C4-T05 runs package-neutral F1-F5 call surfaces, FileProvider, shortcut, "
            "FGS/Job/notification/WebView/multi-process, 100 launch/stop rounds, and "
            "DingTalk 7.8.10/1178 cold/hot/upgrade/login-surface/fg-bg on CAS-only Host."
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C4-T05-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence["rd_result"] = "PASS" if not errors else "FAIL"
    evidence["targeted_result"] = evidence["rd_result"]
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "static-gates.json"),
        str(evidence_path),
        str(report_path),
        str(verification / "c4-t05-rd-summary.json"),
        str(verification / "c4-t05-local-verification.json"),
        str(ROOT / "docs/review/C4_T05_SX_BUSINESS_DESIGN.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(verification / "c4-t05-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "evidence_status": "SUPERSEDED" if historical_output else "CURRENT_REGRESSION",
        "historical_only": historical_output,
        "usable_for_c4_closure": False,
        "superseded_by": "C4-R01" if historical_output else "",
        "evidence_role": "HISTORICAL_C4_T05" if historical_output else "C4_R05_REGRESSION_COMPONENT",
        "status": "PASS" if not errors else "FAIL",
        "environment": environment,
        "static_gates": [
            {"gate": row["gate"], "returncode": row["returncode"]}
            for row in static_rows
        ],
        "surfaces": {
            "status": surfaces.get("status"),
            "campaign": (surfaces.get("result") or {}).get("c4t05"),
        },
        "business": {
            "status": business.get("status"),
            "campaign": (business.get("result") or {}).get("c4t05"),
        },
        "dingtalk": {
            "status": dingtalk.get("status"),
            "campaign": (dingtalk.get("result") or {}).get("c4t05DingTalk"),
            "login_markers_present": [m for m in LOGIN_MARKERS if m in login_dump or m in dt_log],
            "hosted_stub": "StubActivity" in login_dump,
            "background_retained": "StubActivity" in bg_dump or DINGTALK_PACKAGE in bg_dump,
            "foreground_reentry_markers": [m for m in LOGIN_MARKERS if m in fg_dump],
            "guest_activity_create": "GUEST_ACTIVITY_CREATE" in dt_log,
        },
        "apk_metadata": apk_metadata(),
        "errors": errors,
        "non_claims": [
            "not 8-hour soak",
            "not DingTalk account credentials",
            "not VA Pro equivalence",
            "not Android matrix / OEM PASS",
        ],
    }
    write_json(verification / "c4-t05-local-verification.json", local)
    report_path.write_text(
        f"# C4-T05 SX F1-F5 / DingTalk\n\n- status: {evidence['rd_result']}\n",
        encoding="utf-8",
    )
    print(f"{'PASS' if not errors else 'FAIL'} {TASK_ID} rd_result={evidence['rd_result']}")
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
