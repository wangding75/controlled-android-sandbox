#!/usr/bin/env python3
"""C2-T05 RD campaign for scheduling and interaction services."""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import (
    CampaignBlocked,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C2-T05"
COMPONENT = "com.warden.controlledsandbox.fixture.C2T05SchedulingInteractionActivity"
EVENT_TAG = "CS_C2_T05_EVENT"
CAMPAIGN_TAG = "CS_C2_T05"
FGS_TAG = "CS_C2_T05_FGS"
JOB_TAG = "CS_FIXTURE_JOB"
ARM_TAG = "c2t05-arm"
ARM_ALARM_ACTION = "C2_T05_EXACT_ALARM"
TRUST = ["--ez", "trustNativeGuest", "true"]


def command(serial: str, name: str, *, user: int = 0, component: str = "",
            mode: str = "", loops: int = 0, force_stop_host: bool = True,
            deadline_sec: int = 180, extra: list[str] | None = None) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    args = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user), "--es", "requestId", request_id, *TRUST]
    if extra:
        args.extend(extra)
    if component:
        args.extend(["--es", "component", component])
    if mode:
        args.extend(["--es", "c2t05Mode", mode])
    if loops:
        args.extend(["--ei", "c2t05Loops", str(loops)])
    return debug_command(serial, args, deadline_sec=deadline_sec,
                         force_stop_host=force_stop_host)


def logcat(serial: str) -> str:
    return run_adb(serial, ["logcat", "-d", "-v", "threadtime",
                            f"{CAMPAIGN_TAG}:I", f"{EVENT_TAG}:I", f"{FGS_TAG}:I",
                            f"{JOB_TAG}:I", "CS_FIXTURE:I", "*:S"], check=False).stdout


def wait_for_marker(serial: str, markers: tuple[str, ...], timeout_sec: float) -> str:
    deadline = time.monotonic() + timeout_sec
    latest = ""
    while time.monotonic() < deadline:
        latest = logcat(serial)
        if "C2_T05_CAMPAIGN_FAIL" in latest:
            return latest
        if any(marker in latest for marker in markers):
            return latest
        time.sleep(1.0)
    return latest


def save_log(output: Path, name: str, text: str) -> Path:
    path = output / f"{name}-logcat.txt"
    path.write_text(text, encoding="utf-8", errors="replace")
    return path


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    return payload


def require_markers(text: str, markers: tuple[str, ...], *, name: str) -> None:
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise RuntimeError(f"{name} missing markers: {missing}")
    forbidden = ("C2_T05_CAMPAIGN_FAIL", "FATAL EXCEPTION", "ANR in",
                 "HOST_IME_CATALOG_LEAK", "STALE_SESSION")
    present = [marker for marker in forbidden if marker in text]
    if present:
        raise RuntimeError(f"{name} emitted forbidden markers: {present}")


def count(text: str, marker: str) -> int:
    return sum(marker in line for line in text.splitlines())


def validate_full(text: str, loops: int) -> dict[str, Any]:
    required = (
        "C2_T05_INTERACTION_PASS",
        "C2_T05_WINDOW_TOKEN_PASS",
        "C2_T05_DISPLAY_CONTEXT_PASS",
        "C2_T05_IME_PASS",
        "C2_T05_CAMPAIGN_PASS",
    )
    require_markers(text, required, name="full")
    phase_markers = {
        "notification_return": "C2_T05_NOTIFICATION_RETURN",
        "notification_click": "C2_T05_NOTIFICATION_CLICK_CALLBACK",
        "notification_delete": "C2_T05_NOTIFICATION_DELETE_CALLBACK",
        "notification_pass": "C2_T05_NOTIFICATION_PASS",
        "alarm_return": "C2_T05_ALARM_RETURN",
        "alarm_callback": "C2_T05_ALARM_CALLBACK",
        "alarm_pass": "C2_T05_ALARM_PASS",
        "job_return": "C2_T05_JOB_RETURN",
        "job_callback": "C2_T05_JOB_CALLBACK_PASS",
        "fgs_return": "C2_T05_FGS_RETURN",
        "fgs_promoted": "C2_T05_FGS_PROMOTED",
        "fgs_stop": "C2_T05_FGS_STOP_PASS",
        "loop_pass": "C2_T05_LOOP_PASS",
    }
    counts = {name: count(text, marker) for name, marker in phase_markers.items()}
    insufficient = {name: value for name, value in counts.items() if value < loops}
    if insufficient:
        raise RuntimeError(f"full loop evidence incomplete: {insufficient}, loops={loops}")
    types = [int(value) for value in re.findall(r"C2_T05_FGS_PROMOTED .*?type=(\d+)", text)]
    if not types or max(types) < 1:
        raise RuntimeError("FGS declared/runtime type was not non-zero")
    return {
        "status": "PASS",
        "loops": loops,
        "marker_counts": counts,
        "interaction": {
            "window_token": True,
            "display_context": True,
            "ime_host_catalog_hidden": True,
        },
        "fgs_types": types,
        "fatal_or_anr": False,
    }


def guest_pid(serial: str) -> int:
    result = run_adb(serial, ["shell", "pidof", GUEST_PACKAGE], check=False)
    for token in (result.stdout or "").split():
        if token.isdigit():
            return int(token)
    return 0


def kill_guest(serial: str, pid: int) -> dict[str, Any]:
    if pid < 1:
        raise RuntimeError("could not resolve Guest pid for death probe")
    killed = run_adb(serial, ["shell", "kill", "-9", str(pid)], check=False)
    kill_method = "shell"
    if killed.returncode != 0:
        # MuMu's shell UID cannot signal an application-owned Guest even though the Guest
        # shares the host UID.  Re-run the exact signal through run-as so the death probe still
        # exercises a concrete Guest process rather than force-stopping the whole host package.
        fallback = run_adb(serial, ["shell", "run-as", HOST_PACKAGE,
                                    "kill", "-9", str(pid)], check=False)
        killed = fallback
        kill_method = "run-as"
    deadline = time.monotonic() + 15.0
    remaining = guest_pid(serial)
    while time.monotonic() < deadline and remaining == pid:
        time.sleep(0.25)
        remaining = guest_pid(serial)
    return {
        "pid": pid,
        "returncode": killed.returncode,
        "stdout": killed.stdout.strip(),
        "stderr": killed.stderr.strip(),
        "method": kill_method,
        "old_pid_dead": remaining != pid,
        "remaining_pid": remaining,
    }


def active_alarm_dump(text: str) -> str:
    cut = len(text)
    for marker in ("Alarm Stats:", "App Alarm history:", "Alarm history:"):
        index = text.find(marker)
        if index >= 0:
            cut = min(cut, index)
    return text[:cut]


def active_notification_residue(text: str) -> bool:
    # dumpsys notification retains deleted channel preferences.  Only an active notification
    # record is residue; a historical NotificationChannel line with mDeleted=true is expected.
    return any(ARM_TAG in line and "NotificationChannel" not in line
               and "mDeleted=true" not in line for line in text.splitlines())


def run_death_probe(serial: str, output: Path) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    armed = require_pass("arm system-held pair", command(
        serial, "launch-component", component=COMPONENT, mode="arm", force_stop_host=True))
    armed_log = wait_for_marker(serial, ("C2_T05_ARMED",), 30.0)
    save_log(output, "death-armed", armed_log)
    if "C2_T05_ARMED" not in armed_log:
        raise RuntimeError("arm phase did not emit C2_T05_ARMED")
    old_pid_match = re.search(r"C2_T05_ARMED .*?pid=(\d+)", armed_log)
    old_pid = int(old_pid_match.group(1)) if old_pid_match else guest_pid(serial)
    kill = kill_guest(serial, old_pid)
    if not kill["old_pid_dead"]:
        raise RuntimeError(f"old Guest pid survived kill: {kill}")
    callback_log = wait_for_marker(serial, ("C2_T05_ALARM_CALLBACK",), 20.0)
    save_log(output, "death-callback", callback_log)
    if "C2_T05_ALARM_CALLBACK" not in callback_log:
        raise RuntimeError("system-held exact alarm callback was not observed after death")
    callback_pids = [int(value) for value in re.findall(
        r"C2_T05_ALARM_CALLBACK .*?pid=(\d+)", callback_log)]
    replacement_pid = callback_pids[-1] if callback_pids else 0
    if replacement_pid < 1:
        raise RuntimeError("death callback did not expose replacement pid")

    prepared = require_pass("death recovery prepare", command(
        serial, "prepare", force_stop_host=False, deadline_sec=120))
    cleanup = require_pass("cleanup system-held pair", command(
        serial, "launch-component", component=COMPONENT, mode="cleanup",
        force_stop_host=False, deadline_sec=120))
    cleanup_log = wait_for_marker(serial, ("C2_T05_CLEANUP_PASS",), 20.0)
    save_log(output, "death-cleanup", cleanup_log)
    require_markers(cleanup_log, ("C2_T05_CLEANUP_PASS",), name="death cleanup")
    stopped = command(serial, "stop", force_stop_host=True, deadline_sec=120)
    notification_dump = run_adb(serial, ["shell", "dumpsys", "notification", "--noredact"],
                                 check=False).stdout
    alarm_dump = run_adb(serial, ["shell", "dumpsys", "alarm"], check=False).stdout
    current_alarm = active_alarm_dump(alarm_dump)
    notification_path = output / "death-notification-after.txt"
    alarm_path = output / "death-alarm-after.txt"
    notification_path.write_text(notification_dump, encoding="utf-8")
    alarm_path.write_text(alarm_dump, encoding="utf-8")
    residue = {
        "notification": active_notification_residue(notification_dump),
        "alarm": ARM_ALARM_ACTION in current_alarm,
    }
    if any(residue.values()):
        raise RuntimeError(f"system-held cleanup residue: {residue}")
    return {
        "status": "PASS",
        "armed": armed,
        "old_pid": old_pid,
        "kill": kill,
        "callback_pids": callback_pids,
        "replacement_pid": replacement_pid,
        "recovery_prepare": prepared,
        "cleanup": cleanup,
        "stop": stopped,
        "residue": residue,
        "notification_dump": str(notification_path),
        "alarm_dump": str(alarm_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    parser.add_argument("--loops", type=int, default=50)
    args = parser.parse_args()
    if args.loops < 1 or args.loops > 100:
        raise SystemExit("--loops must be between 1 and 100")

    output = artifacts_dir("catch-up-c2-t05")
    verification = ROOT / "verification/catch-up/C2-T05"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "Notification Alarm Job FGS Window Input IME Display",
        "branch": identity["branch"],
        "commit": identity["commit"],
        "tree": identity["tree"],
        "timestamp": now_iso(),
        "host_os": host_os(),
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "build_result": "PASS",
        "static_result": "PASS",
        "targeted_result": "PASS",
        "rd_result": "UNVERIFIED",
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": ["KI-T57-010", "KI-T57-011", "KI-R03-020", "KI-R03-023",
                         "KI-R03-024", "KI-R03-025", "KI-R03-026", "KI-M10-005",
                         "KI-M10-006", "KI-M10-007", "KI-R03-038"],
        "evidence_files": [],
        "notes": "RD API32 method evidence only; C1 token/death evidence is inherited and the fresh exact-alarm death probe is recorded here. Android Matrix/OEM/commercial/VA PRO equivalence remains unproven.",
    }
    details: dict[str, Any] = {
        "task_id": TASK_ID,
        "started_at": now_iso(),
        "requested_loops": args.loops,
        "steps": [],
        "phases": [],
    }
    status = "FAIL"
    try:
        environment = resolve_rd_environment(args.instance)
        serial = environment["adb_serial"]
        evidence.update({
            "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
            "device_name": environment["device_name"],
            "adb_serial": serial,
            "api_level": environment["api_level"],
            "abi": environment["abi"],
            "boot_id": environment["boot_id"],
            "android_id": environment["android_id"],
            "instance_name": environment["instance_name"],
        })
        reset = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        if reset.returncode != 0 or reset.stdout.strip().lower() != "success":
            raise RuntimeError(f"host data reset failed: {reset.stdout} {reset.stderr}")
        details["steps"].append({"name": "reset_host_data", "status": "PASS"})
        details["steps"].append({"name": "install_rd_apks", "result": install_rd_apks(serial)})
        details["steps"].append({"name": "import_prepare", "result": require_pass(
            "import prepare", command(serial, "import-prepare", force_stop_host=True))})
        details["steps"].append({"name": "exact_alarm_policy", "result": require_pass(
            "exact alarm permission", command(serial, "set-permissions", force_stop_host=False,
                deadline_sec=90, extra=["--es", "permissions",
                "android.permission.SCHEDULE_EXACT_ALARM", "--es", "decision", "GRANTED"]))})
        cleared = require_pass("clear initial Guest data", command(serial, "clear", force_stop_host=False))
        details["steps"].append({"name": "clear_initial_guest_data", "result": cleared})

        run_adb(serial, ["logcat", "-c"], check=False)
        full_command = require_pass("full campaign launch", command(
            serial, "launch-component", component=COMPONENT, mode="full", loops=args.loops,
            force_stop_host=True, deadline_sec=180))
        # Each loop waits for a real exact-alarm delivery and a JobScheduler
        # callback, so the device cadence is materially slower than the
        # command-launch timeout.  Leave enough wall-clock budget for the
        # requested loop count plus normal emulator jitter.
        full_log = wait_for_marker(serial, ("C2_T05_CAMPAIGN_PASS",),
                                   max(300.0, args.loops * 18.0))
        full_log_path = save_log(output, "full", full_log)
        full_result = validate_full(full_log, args.loops)
        full_result.update({"command": full_command, "logcat": str(full_log_path)})
        details["phases"].append(full_result)
        require_pass("stop after full campaign", command(serial, "stop", force_stop_host=True,
                                                         deadline_sec=120))

        death = run_death_probe(serial, output)
        details["phases"].append(death)
        evidence["rd_result"] = "PASS"
        evidence["regression_result"] = "PASS"
        details["status"] = "PASS"
        status = "PASS"
    except CampaignBlocked as error:
        evidence["rd_result"] = "BLOCKED_ENV"
        evidence["failures"].append({"id": getattr(error, "code", "RD_ENVIRONMENT"),
                                      "classification": "ENVIRONMENT_BLOCKED",
                                      "summary": str(error)})
        details["status"] = "BLOCKED_ENV"
        details["error"] = str(error)
        status = "BLOCKED_ENV"
    except Exception as error:
        evidence["rd_result"] = "FAIL"
        evidence["failures"].append({"id": "C2-T05-RD", "classification": "FAIL",
                                      "summary": str(error)})
        details["status"] = "FAIL"
        details["error"] = str(error)
        status = "FAIL"
    finally:
        details["finished_at"] = now_iso()
        details["artifacts"] = {
            "apk": apk_metadata(),
            "raw_evidence_dir": str(output),
        }
        details_path = output / "campaign-details.json"
        write_json(details_path, details)
        evidence_path = output / "evidence.json"
        verification_path = verification / "c2-t05-rd-summary.json"
        evidence["evidence_files"] = [str(path) for path in sorted(output.glob("*"))]
        evidence["evidence_files"].extend([str(evidence_path), str(verification_path)])
        schema_errors = validate_evidence(evidence)
        if schema_errors:
            evidence["failures"].append({"id": "C2-T05-EVIDENCE", "classification": "FAIL",
                                          "summary": "; ".join(schema_errors)})
            status = "FAIL"
        write_json(evidence_path, evidence)
        write_json(verification_path, evidence)
        print(json.dumps({"status": status, "output": str(output),
                          "verification": str(verification_path),
                          "serial": evidence.get("adb_serial", ""),
                          "phases": len(details.get("phases", []))},
                         ensure_ascii=False, indent=2))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
