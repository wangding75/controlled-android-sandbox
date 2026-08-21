#!/usr/bin/env python3
"""C1-T05 RD evidence: system-held PendingIntent, Alarm, Notification and recovery.

The MuMu endpoint is resolved by instance name for every run.  The campaign keeps the
device-side logcat as a raw stream, runs the package-neutral framework probe, performs one
Guest-death recovery, and executes a 50-round sender loop for each virtual user.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_a01_acceptance import safe_debug_command
from run_fix01d_rd import alarm_held, dump_text, live_guest, notification_held
from run_p2a_rd import process_alive, snapshot_identity, stale_session_rejected
from run_rd_campaign import (
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    adb_bin,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "CAS-C1-T05-RD"
PI_ACTIVITY = "com.warden.controlledsandbox.fixture.SystemHolderPendingIntentActivity"
FRAMEWORK_PROBE = "com.warden.controlledsandbox.fixture.FrameworkProbeActivity"
TRUST = ["--ez", "trustNativeGuest", "true"]
VIRTUAL_USERS = (0, 1)
ALARM_DELAY_MS = 5_000


def _debug(serial: str, command: str, user: int = 0, *, force_stop: bool = False,
           component: str = "") -> dict[str, Any]:
    args = ["--es", "command", command, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user)]
    if component:
        args.extend(["--es", "component", component])
    args.extend(TRUST)
    return safe_debug_command(serial, args, deadline_sec=150, force_stop_host=force_stop)


def _stream_logcat(serial: str, target: Path) -> tuple[subprocess.Popen[str], Any]:
    target.parent.mkdir(parents=True, exist_ok=True)
    handle = target.open("w", encoding="utf-8", errors="replace")
    process = subprocess.Popen(
        [adb_bin(), "-s", serial, "logcat", "-v", "threadtime"],
        cwd=ROOT,
        stdout=handle,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return process, handle


def _stop_logcat(process: subprocess.Popen[str], handle: Any) -> None:
    try:
        process.terminate()
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
    finally:
        handle.close()


def _clear_logcat(serial: str) -> None:
    run_adb(serial, ["logcat", "-c"], check=False)


def _wait_for_log_marker(serial: str, marker: str, timeout_seconds: float = 10.0) -> str:
    deadline = time.monotonic() + timeout_seconds
    latest = ""
    while time.monotonic() < deadline:
        latest = dump_text(serial, ["logcat", "-d", "-t", "500"])
        if marker in latest:
            return latest
        time.sleep(0.25)
    return latest


def _marker_count(text: str, marker: str, user: int | None = None) -> int:
    rows = [line for line in text.splitlines() if marker in line]
    if user is not None:
        rows = [line for line in rows if f"virtualUserId={user}" in line]
    return len(rows)


def _kill_guest(serial: str, pid: int) -> dict[str, Any]:
    killed = run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "kill", "-9", str(pid)], check=False)
    time.sleep(0.5)
    if process_alive(serial, pid):
        fallback = run_adb(serial, ["shell", "kill", "-9", str(pid)], check=False)
        if fallback.returncode == 0:
            killed = fallback
    return {
        "pid": pid,
        "command": f"kill -9 {pid}",
        "returncode": killed.returncode,
        "stdout": killed.stdout,
        "stderr": killed.stderr,
        "old_pid_dead": not process_alive(serial, pid),
    }


def _framework_probe(serial: str, output: Path) -> dict[str, Any]:
    # RD API32 can reject a cold physical Activity window even when the framework
    # capability probe itself is healthy.  Warm the fixture process first so the
    # probe observes the same host/Guest path without changing its assertions.
    warm = _debug(serial, "launch", 0, force_stop=True)
    time.sleep(4.0)
    _clear_logcat(serial)
    response = _debug(serial, "launch-component", 0, force_stop=False, component=FRAMEWORK_PROBE)
    time.sleep(7)
    logcat = dump_text(serial, ["logcat", "-d", "-t", "1600"])
    (output / "framework-probe-logcat.txt").write_text(logcat, encoding="utf-8")
    write_json(output / "framework-probe-command.json", response)
    write_json(output / "framework-probe-warm-command.json", warm)
    required = (
        "FRAMEWORK_PROBE_PENDING_INTENT_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS",
        "FRAMEWORK_PROBE_NOTIFICATION_READBACK_PASS",
        "FRAMEWORK_PROBE_ALARM_CLOCK_READBACK_PASS",
        "FRAMEWORK_PROBE_CROSS_PENDING_INTENT_PASS",
    )
    forbidden = ("FRAMEWORK_PROBE_PENDING_INTENT_BINDER_FAIL", "FATAL EXCEPTION")
    markers = {marker: marker in logcat for marker in required}
    return {
        "warm_command": warm,
        "command": response,
        "markers": markers,
        "forbidden_markers": {marker: marker in logcat for marker in forbidden},
        "pass": response.get("status") == "PASS"
        and all(markers.values())
        and not any(marker in logcat for marker in forbidden),
    }


def _recovery(serial: str, output: Path) -> dict[str, Any]:
    _clear_logcat(serial)
    armed = _debug(serial, "pi-system-holder", 0, force_stop=True, component=PI_ACTIVITY)
    write_json(output / "recovery-armed.json", armed)
    armed_identity = snapshot_identity(serial, armed)
    guest = live_guest(serial, int(armed_identity.get("platformPid") or 0))
    if guest.get("pid"):
        armed_identity["platformPid"] = guest["pid"]
        armed_identity["processName"] = guest.get("name") or armed_identity.get("processName")
    write_json(output / "recovery-armed-identity.json", armed_identity)
    notification_before = dump_text(serial, ["shell", "dumpsys", "notification", "--noredact"])
    alarm_before = dump_text(serial, ["shell", "dumpsys", "alarm"])
    (output / "recovery-notification-before.txt").write_text(notification_before, encoding="utf-8")
    (output / "recovery-alarm-before.txt").write_text(alarm_before, encoding="utf-8")
    _wait_for_log_marker(serial, "ALARM_SCHEDULED", timeout_seconds=10.0)
    time.sleep(0.75)
    pid = int(guest.get("pid") or 0)
    kill = _kill_guest(serial, pid) if pid > 0 else {"pid": 0, "old_pid_dead": False}
    write_json(output / "recovery-kill.json", kill)
    # The relay is delivered by a system process, but a cold Guest bind can take several
    # seconds after the sender fires.  Keep the observation window bounded and long enough to
    # distinguish a late recovery from the original pre-fix permission-denial failure.
    time.sleep(25.0)
    logcat = dump_text(serial, ["logcat", "-d", "-t", "1800"])
    (output / "recovery-logcat.txt").write_text(logcat, encoding="utf-8")
    recovered = _debug(serial, "prepare", 0, force_stop=False)
    write_json(output / "recovery-prepare.json", recovered)
    recovered_identity = snapshot_identity(serial, recovered)
    write_json(output / "recovery-identity.json", recovered_identity)
    cancel = _debug(serial, "pi-system-holder-cancel", 0, force_stop=False)
    write_json(output / "recovery-notification-cancel.json", cancel)
    notification_after = dump_text(serial, ["shell", "dumpsys", "notification", "--noredact"])
    alarm_after = dump_text(serial, ["shell", "dumpsys", "alarm"])
    (output / "recovery-notification-after.txt").write_text(notification_after, encoding="utf-8")
    (output / "recovery-alarm-after.txt").write_text(alarm_after, encoding="utf-8")
    active_alarm_dump = alarm_after.split("  App Alarm history:", 1)[0]
    result = {
        "armed": armed,
        "armed_identity": armed_identity,
        "guest_before_kill": guest,
        "kill": kill,
        "guest_live_before_kill": pid > 0,
        "old_pid_dead": bool(kill.get("old_pid_dead")),
        "system_holder_notification_before": notification_held(notification_before),
        "system_holder_alarm_before": alarm_held(alarm_before),
        "relay_seen": "SYSTEM_HOLDER_RELAY" in logcat,
        "delivered_seen": "PENDING_INTENT_BROKER_RELAY_DELIVERED" in logcat,
        "correct_user_seen": "virtualUserId=0" in logcat,
        "permission_denial_seen": "Permission Denial" in logcat,
        "recovered": recovered,
        "new_session_established": bool(recovered_identity.get("sessionId")),
        "stale_old_session_rejected": stale_session_rejected(armed_identity, recovered_identity),
        "notification_cancel": cancel,
        "notification_residue": "id=5703" in notification_after,
        # dumpsys alarm keeps fired alarms in the owner history below "Alarm Stats".  Only
        # the current-batch section is residue evidence; the history line is expected after
        # a successful one-shot delivery.
        "alarm_residue": "RELAY_PENDING_INTENT" in active_alarm_dump
        or "SYSTEM_HOLDER_ALARM" in active_alarm_dump,
    }
    result["pass"] = all((
        result["guest_live_before_kill"], result["old_pid_dead"],
        result["system_holder_notification_before"], result["system_holder_alarm_before"],
        result["relay_seen"], result["delivered_seen"], result["correct_user_seen"],
        not result["permission_denial_seen"], result["new_session_established"],
        result["stale_old_session_rejected"], not result["notification_residue"],
        not result["alarm_residue"],
    ))
    return result


def _user_loops(serial: str, output: Path, user: int, loops: int) -> dict[str, Any]:
    command_rows: list[dict[str, Any]] = []
    for iteration in range(1, loops + 1):
        response = _debug(serial, "pi-system-holder", user, force_stop=iteration == 1,
                          component=PI_ACTIVITY)
        command_rows.append({
            "iteration": iteration,
            "status": response.get("status"),
            "operation": (response.get("result") or {}).get("operation", {}),
        })
        time.sleep((ALARM_DELAY_MS / 1000.0) + 0.5)
    cancel = _debug(serial, "pi-system-holder-cancel", user, force_stop=False)
    write_json(output / f"user-{user}-commands.json", command_rows)
    logcat = dump_text(serial, ["logcat", "-d", "-t", "2500"])
    marker = "PENDING_INTENT_BROKER_RELAY_DELIVERED"
    relay = _marker_count(logcat, marker, user)
    return {
        "user": user,
        "loops_requested": loops,
        "command_passes": sum(row["status"] == "PASS" for row in command_rows),
        "relay_deliveries": relay,
        "guest_deliveries": _marker_count(logcat, "CS_PI_HOLDER: DELIVERED"),
        "cancel": cancel,
        "pass": sum(row["status"] == "PASS" for row in command_rows) == loops and relay >= loops,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--loops", type=int, default=50)
    parser.add_argument("--receipt", type=Path,
                        default=ROOT / "verification/catch-up/C1-T05/c1-t05-rd-summary.json")
    args = parser.parse_args()
    if args.loops < 1 or args.loops > 200:
        raise SystemExit("--loops must be between 1 and 200")

    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    output = artifacts_dir("catch-up-c1-t05")
    install = install_rd_apks(serial)
    write_json(output / "environment.json", environment)
    write_json(output / "install.json", install)
    campaign_logcat_path = output / "campaign-logcat.txt"
    campaign_logcat_process, campaign_logcat_handle = _stream_logcat(
        serial, campaign_logcat_path)
    try:
        framework = _framework_probe(serial, output)
        recovery = _recovery(serial, output)
        users = {str(user): _user_loops(serial, output, user, args.loops)
                 for user in VIRTUAL_USERS}
    finally:
        _stop_logcat(campaign_logcat_process, campaign_logcat_handle)
    campaign_logcat = campaign_logcat_path.read_text(encoding="utf-8", errors="replace")
    for user in VIRTUAL_USERS:
        row = users[str(user)]
        row["relay_deliveries"] = _marker_count(
            campaign_logcat, "PENDING_INTENT_BROKER_RELAY_DELIVERED", user)
        row["pass"] = row["command_passes"] == args.loops and row["relay_deliveries"] >= args.loops
    summary = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "host_os": host_os(),
        "git": git_identity(),
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "instance_name": args.instance,
        "environment": environment,
        "apk": apk_metadata(),
        "raw_evidence_dir": str(output),
        "campaign_logcat": str(campaign_logcat_path),
        "alarm_delay_ms": ALARM_DELAY_MS,
        "framework_probe": framework,
        "guest_death_recovery": recovery,
        "users": users,
        "overall_pass": framework["pass"] and recovery["pass"]
        and all(row["pass"] for row in users.values()),
        "eight_hour_soak": "NOT_RUN_BY_TASKBOOK_1_1",
    }
    write_json(args.receipt, summary)
    print(json.dumps({
        "receipt": str(args.receipt),
        "raw_evidence_dir": str(output),
        "overall_pass": summary["overall_pass"],
        "user_results": users,
        "recovery": recovery,
    }, ensure_ascii=False, indent=2))
    return 0 if summary["overall_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
