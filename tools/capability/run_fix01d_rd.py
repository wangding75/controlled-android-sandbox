#!/usr/bin/env python3
"""FIX01-D: system-held PendingIntent after actual guest stub death.

Does not force-stop the Host/Broker after the senders are armed. The only
kill is `kill -9` of the live `:guestN` PID. Notification cancel and the
AlarmManager trigger are issued by Android, not by a Broker send substitute.
"""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_p2a_rd import guest_process_name, pidof, process_alive, snapshot_identity, stale_session_rejected
from run_rd_campaign import GUEST_PACKAGE, HOST_PACKAGE, apk_metadata, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX01-D"
TRUST = ("--ez", "trustNativeGuest", "true")
DELIVERY_TOKENS = (
    "CS_PI_HOLDER: DELIVERED",
    "PENDING_INTENT_BROKER_RELAY_DELIVERED",
    "SYSTEM_HOLDER_ALARM",
    "SYSTEM_HOLDER_NOTIFICATION",
)


def grant_host_holders(serial: str) -> dict[str, Any]:
    rows: dict[str, Any] = {}
    for args in (
        ["shell", "pm", "grant", HOST_PACKAGE, "android.permission.POST_NOTIFICATIONS"],
        ["shell", "appops", "set", HOST_PACKAGE, "POST_NOTIFICATION", "allow"],
        ["shell", "appops", "set", HOST_PACKAGE, "SCHEDULE_EXACT_ALARM", "allow"],
        ["shell", "cmd", "appops", "set", HOST_PACKAGE, "SCHEDULE_EXACT_ALARM", "allow"],
    ):
        result = run_adb(serial, args, check=False)
        rows[" ".join(args[1:])] = {
            "returncode": result.returncode,
            "stdout": (result.stdout or "")[-300:],
            "stderr": (result.stderr or "")[-300:],
        }
    return rows


def dump_text(serial: str, args: list[str]) -> str:
    result = run_adb(serial, args, check=False)
    return (result.stdout or "") + (result.stderr or "")


def notification_held(text: str) -> bool:
    return "cas.system.holder" in text or "CAS system-holder" in text or "SYSTEM_HOLDER_NOTIFICATION" in text


def alarm_held(text: str) -> bool:
    if any(
        token in text
        for token in (
            "SYSTEM_HOLDER_ALARM",
            "cas.system.holder",
            "RuntimePendingIntentSender",
            "VirtualIntentSender",
        )
    ):
        return True
    # Broker-owned IIntentSender appears as BinderProxy, not PendingIntentRecord.
    if "android.os.BinderProxy" in text and "exactAllowReason=permission" in text:
        return True
    return "RELAY_PENDING_INTENT" in text


def parse_notification_cancels(text: str) -> list[list[str]]:
    commands: list[list[str]] = []
    for line in text.splitlines():
        if "cas.system.holder" not in line and "CAS system-holder" not in line:
            continue
        pkg = re.search(r"pkg=([^\s]+)", line)
        ident = re.search(r"id=(\d+)", line)
        tag = re.search(r"tag=([^\s]+)", line)
        key = re.search(r"key=([^\s|:]+(?:\|[^\s]+)+)", line)
        if pkg and ident:
            package = pkg.group(1)
            notification_id = ident.group(1)
            commands.append(["shell", "cmd", "notification", "cancel", package, notification_id])
            if tag and tag.group(1) not in {"null", "None"}:
                commands.append([
                    "shell", "cmd", "notification", "cancel",
                    "--tag", tag.group(1), package, notification_id,
                ])
            else:
                commands.append([
                    "shell", "cmd", "notification", "cancel", package, notification_id,
                ])
        if key:
            parts = key.group(1).split("|")
            if len(parts) >= 3 and parts[2].isdigit():
                commands.append([
                    "shell", "cmd", "notification", "cancel", parts[1], parts[2],
                ])
    # Unique while preserving order.
    unique: list[list[str]] = []
    seen: set[tuple[str, ...]] = set()
    for command in commands:
        marker = tuple(command)
        if marker in seen:
            continue
        seen.add(marker)
        unique.append(command)
    return unique


def host_uid(serial: str) -> int:
    dumped = dump_text(serial, ["shell", "dumpsys", "package", HOST_PACKAGE])
    match = re.search(r"userId=(\d+)", dumped)
    return int(match.group(1)) if match else 0


def live_guest(serial: str, reported_pid: int = 0) -> dict[str, Any]:
    found: list[tuple[int, int, str]] = []
    uid = host_uid(serial)
    ps = dump_text(serial, ["shell", "ps", "-A", "-o", "PID,UID,NAME"])
    reserved = {
        HOST_PACKAGE,
        f"{HOST_PACKAGE}:sandbox_server",
        f"{HOST_PACKAGE}:sandbox_package",
        f"{HOST_PACKAGE}:sandbox_server32",
    }
    for line in ps.splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        pid_text, uid_text, name = parts[0], parts[1], parts[2]
        if not pid_text.isdigit() or not uid_text.isdigit():
            continue
        pid = int(pid_text)
        process_uid = int(uid_text)
        if uid and process_uid != uid:
            continue
        if name in reserved:
            continue
        spoofed = name == GUEST_PACKAGE or name.startswith(f"{GUEST_PACKAGE}:")
        stub = ":guest" in name and name.startswith(HOST_PACKAGE)
        if spoofed or stub:
            slot = -1
            slot_match = re.search(r"guest(\d+)", name)
            if slot_match:
                slot = int(slot_match.group(1))
            found.append((slot, pid, name))
    if reported_pid > 0 and process_alive(serial, reported_pid) and not any(
        pid == reported_pid for _, pid, _ in found
    ):
        found.insert(0, (-1, reported_pid, "reported-platformPid"))
    if not found:
        return {"pid": 0, "slot": -1, "name": "", "uid": uid, "all": []}
    slot, pid, name = found[0]
    return {
        "pid": pid,
        "slot": slot,
        "name": name,
        "uid": uid,
        "all": [{"slot": item[0], "pid": item[1], "name": item[2]} for item in found],
    }


def collect_logcat(serial: str) -> str:
    chunks: list[str] = []
    for args in (
        ["logcat", "-d", "-s", "CS_PI_HOLDER:I", "CS_RUNTIME:I", "CS_COMMAND:I", "CS_PENDING_INTENT:W"],
        ["logcat", "-d", "-t", "400"],
    ):
        try:
            dumped = run_adb(serial, args, check=False)
            chunks.append((dumped.stdout or "") + (dumped.stderr or ""))
        except Exception as exc:
            chunks.append(f"LOGCAT_UNAVAILABLE:{args}:{exc}")
    return "\n".join(chunks)


def delivery_hits(logcat: str) -> list[str]:
    hits: list[str] = []
    for line in logcat.splitlines():
        if "CS_PI_HOLDER" in line or "PENDING_INTENT_BROKER_RELAY" in line:
            hits.append(line[-400:])
    return hits[-80:]


def kinds_from_log(logcat: str, delivered_text: str = "") -> set[str]:
    kinds: set[str] = set()
    haystack = f"{logcat}\n{delivered_text}"
    if "CS_PI_HOLDER: DELIVERED" in haystack and "kind=notification" in haystack:
        kinds.add("notification")
    if "CS_PI_HOLDER: DELIVERED" in haystack and "kind=alarm" in haystack:
        kinds.add("alarm")
    if '"kind":"notification"' in haystack or '"kind": "notification"' in haystack:
        kinds.add("notification")
    if '"kind":"alarm"' in haystack or '"kind": "alarm"' in haystack:
        kinds.add("alarm")
    if "PENDING_INTENT_BROKER_RELAY_DELIVERED" in haystack:
        kinds.add("broker_relay")
    if "SYSTEM_HOLDER_RELAY" in haystack:
        kinds.add("broker_relay")
    return kinds


def classify(evidence: dict[str, Any]) -> str:
    if not evidence.get("guest_live_before_kill"):
        return "KILL_TARGET_PID_MISSING"
    if not evidence.get("old_pid_dead"):
        return "KILL_PID_STILL_ALIVE"
    if not evidence.get("broker_survived"):
        return "KILL_BROKER_DIED"
    if evidence.get("host_force_stopped_after_arm"):
        return "HOST_FORCE_STOPPED_AFTER_ARM"
    notif = evidence.get("notification_delivered_after_kill")
    alarm = evidence.get("alarm_delivered_after_kill")
    if not evidence.get("notification_held_before_kill"):
        return "NOTIFICATION_NOT_SYSTEM_HELD"
    if not evidence.get("alarm_held_before_kill"):
        return "ALARM_NOT_SYSTEM_HELD"
    if notif and alarm and evidence.get("new_session_established") and evidence.get("stale_old_session"):
        return "SYSTEM_HOLDER_PI_RECOVERED"
    if notif or alarm:
        return "SYSTEM_HOLDER_PI_PARTIAL"
    return "SYSTEM_HOLDER_PI_NOT_DELIVERED"


def main() -> int:
    try:
        environment = resolve_rd_environment(RD_INSTANCE_NAME)
    except Exception as exc:
        devices = run_adb(None, ["devices", "-l"], check=False).stdout
        serial = ""
        for line in devices.splitlines():
            if "\tdevice" in line or " device " in line:
                serial = line.split()[0]
                if serial != "List":
                    break
        if not serial:
            raise
        environment = {
            "instance_name": "FALLBACK_ADB_DEVICE",
            "adb_serial": serial,
            "rd_resolution_error": str(exc),
            "adb_devices": devices,
        }
    serial = environment["adb_serial"]
    output = artifacts_dir("fix01d")
    write_json(output / "environment.json", environment)
    grants = grant_host_holders(serial)
    write_json(output / "grants.json", grants)

    run_adb(serial, ["logcat", "-c"], check=False)
    armed_at = time.time()
    armed = debug_command(
        serial,
        ["-e", "command", "pi-system-holder", "-e", "package", GUEST_PACKAGE, *TRUST],
        deadline_sec=150,
        force_stop_host=True,
    )
    write_json(output / "armed.json", armed)
    armed_identity = snapshot_identity(serial, armed)
    write_json(output / "armed-identity.json", armed_identity)

    notifications_before = dump_text(serial, ["shell", "dumpsys", "notification", "--noredact"])
    alarms_before = dump_text(serial, ["shell", "dumpsys", "alarm"])
    (output / "notification-before.txt").write_text(notifications_before, encoding="utf-8")
    (output / "alarm-before.txt").write_text(alarms_before, encoding="utf-8")
    arm_logcat = collect_logcat(serial)
    (output / "logcat-after-arm.txt").write_text(arm_logcat[-20000:], encoding="utf-8")
    held_notification = notification_held(notifications_before)
    held_alarm = alarm_held(alarms_before)

    guest = live_guest(serial, int(armed_identity.get("platformPid") or 0))
    if guest.get("pid"):
        armed_identity["platformPid"] = guest["pid"]
        armed_identity["processName"] = guest.get("name") or armed_identity.get("processName")
    broker_before = pidof(serial, f"{HOST_PACKAGE}:sandbox_server")
    host_before = pidof(serial, HOST_PACKAGE)
    ps_before = dump_text(serial, ["shell", "ps", "-A", "-o", "PID,NAME"])
    (output / "ps-before-kill.txt").write_text(ps_before, encoding="utf-8")

    pid = int(guest.get("pid") or 0)
    kill: dict[str, Any]
    if pid <= 0:
        kill = {"classification": "KILL_TARGET_PID_MISSING", "pid": 0}
    else:
        killed = run_adb(
            serial,
            ["shell", "run-as", HOST_PACKAGE, "kill", "-9", str(pid)],
            check=False,
        )
        if killed.returncode != 0 or process_alive(serial, pid):
            fallback = run_adb(serial, ["shell", "su", "0", "kill", "-9", str(pid)], check=False)
            if fallback.returncode == 0:
                killed = fallback
        if process_alive(serial, pid):
            killed = run_adb(serial, ["shell", "kill", "-9", str(pid)], check=False)
        time.sleep(1.2)
        kill = {
            "classification": "KILLED",
            "pid": pid,
            "command": f"kill -9 {pid}",
            "returncode": killed.returncode,
            "stdout": killed.stdout,
            "stderr": killed.stderr,
            "oldPidDead": not process_alive(serial, pid),
        }
    write_json(output / "kill.json", kill)
    broker_after_kill = pidof(serial, f"{HOST_PACKAGE}:sandbox_server")
    host_after_kill = pidof(serial, HOST_PACKAGE)
    guest_after_kill = live_guest(serial)
    ps_after = dump_text(serial, ["shell", "ps", "-A", "-o", "PID,NAME"])
    (output / "ps-after-kill.txt").write_text(ps_after, encoding="utf-8")

    cancel_attempts: list[dict[str, Any]] = []
    for cancel_cmd in parse_notification_cancels(notifications_before):
        cancelled = run_adb(serial, cancel_cmd, check=False)
        cancel_attempts.append({
            "command": cancel_cmd,
            "returncode": cancelled.returncode,
            "stdout": (cancelled.stdout or "")[-500:],
            "stderr": (cancelled.stderr or "")[-500:],
        })
    host_cancel = debug_command(
        serial,
        ["-e", "command", "pi-system-holder-cancel"],
        deadline_sec=45,
        force_stop_host=False,
    )
    cancel_attempts.append({"host_debug_cancel": host_cancel})
    write_json(output / "notification-cancel.json", cancel_attempts)

    time.sleep(25)
    logcat = collect_logcat(serial)
    (output / "logcat-after.txt").write_text(logcat[-80000:], encoding="utf-8")

    recover = debug_command(
        serial,
        ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST],
        deadline_sec=90,
        force_stop_host=False,
    )
    write_json(output / "recover.json", recover)
    recover_identity = snapshot_identity(serial, recover)
    write_json(output / "recover-identity.json", recover_identity)

    delivered_file = run_adb(
        serial,
        [
            "shell", "run-as", HOST_PACKAGE, "cat",
            f"files/instances/u0/{GUEST_PACKAGE}/data/files/system-holder-delivered.json",
        ],
        check=False,
    )
    if recover_identity.get("platformPid") in (0, None):
        recovered_guest = live_guest(serial)
        if recovered_guest.get("pid"):
            recover_identity["platformPid"] = recovered_guest["pid"]
            recover_identity["processName"] = recovered_guest.get("name") or recover_identity.get("processName")
    hits = delivery_hits(logcat)
    kinds = kinds_from_log(logcat, delivered_file.stdout or "")
    notifications_after = dump_text(serial, ["shell", "dumpsys", "notification", "--noredact"])
    alarms_after = dump_text(serial, ["shell", "dumpsys", "alarm"])

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "grants": grants,
        "armed": armed,
        "armed_identity": armed_identity,
        "notification_held_before_kill": held_notification,
        "alarm_held_before_kill": held_alarm,
        "guest_before_kill": guest,
        "guest_live_before_kill": pid > 0 and process_alive(serial, pid) is False and bool(kill.get("oldPidDead")),
        "kill": kill,
        "old_pid_dead": bool(kill.get("oldPidDead")),
        "broker_pids_before": broker_before,
        "broker_pids_after_kill": broker_after_kill,
        "host_pids_before": host_before,
        "host_pids_after_kill": host_after_kill,
        "broker_survived": bool(broker_before) and bool(broker_after_kill)
            and bool(set(broker_before) & set(broker_after_kill)),
        "host_survived": bool(host_before) and bool(host_after_kill),
        "host_force_stopped_after_arm": False,
        "guest_after_kill": guest_after_kill,
        "notification_cancel": cancel_attempts,
        "delivery_log_hits": hits,
        "delivery_kinds": sorted(kinds),
        "notification_delivered_after_kill": "notification" in kinds or "broker_relay" in kinds,
        "alarm_delivered_after_kill": "alarm" in kinds or "broker_relay" in kinds,
        "recover": recover,
        "recover_identity": recover_identity,
        "new_session_established": bool(recover_identity.get("sessionId")),
        "stale_old_session": stale_session_rejected(armed_identity, recover_identity),
        "delivered_file": {
            "returncode": delivered_file.returncode,
            "text": (delivered_file.stdout or "")[-2000:],
        },
        "notification_dump_after": notifications_after[-2000:],
        "alarm_dump_after": alarms_after[-2000:],
        "guest_process_name": guest.get("name") or guest_process_name(54),
        "host_package": HOST_PACKAGE,
    }
    # guest_live_before_kill must reflect the pre-kill snapshot, not the post-kill alive check.
    evidence["guest_live_before_kill"] = pid > 0
    evidence["classification"] = classify(evidence)
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "classification": evidence["classification"],
        "held_notification": held_notification,
        "held_alarm": held_alarm,
        "guest_pid": pid,
        "old_pid_dead": evidence["old_pid_dead"],
        "broker_survived": evidence["broker_survived"],
        "delivery_kinds": evidence["delivery_kinds"],
        "recover": ((recover.get("result") or {}).get("status")),
        "stale_old_session": evidence["stale_old_session"],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
