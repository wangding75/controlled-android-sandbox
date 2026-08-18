#!/usr/bin/env python3
"""T57-R03-P2A / FIX01-B: process-grounded fault / death / recovery evidence.

Probe status PASS/FAIL/ERROR is never treated as proof that a fault occurred.
Kill targets the CAS guest stub PID, not the physical guest package.
"""

from __future__ import annotations

import argparse
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
from run_rd_campaign import (
    DEBUG_ACTIVITY,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "T57-R03-P2A"
TRUST_EXTRAS = ("--ez", "trustNativeGuest", "true")

FAULT_MODES = (
    "java",
    "main",
    "service",
    "native-segv",
    "native-abort",
    "isolated-native-segv",
    "anr-activity",
    "anr-service",
    "anr-provider",
)

SYSTEM_ANR_TOKENS = (
    "ANR in ",
    "Application Not Responding",
    "am_anr",
    "Reason: Input dispatching timed out",
    "Reason: executing service",
    "Reason: Broadcast of Intent",
    "Reason: ContentProvider",
)
SYSTEM_CRASH_TOKENS = (
    "FATAL EXCEPTION",
    "Fatal signal",
    "SIGSEGV",
    "SIGABRT",
    "Abort message",
    "Process: " + HOST_PACKAGE,
)
FIXTURE_ONLY_ANR = ("ANR_ACTIVITY", "ANR_SERVICE", "ANR_PROVIDER", "CAS_FAULT_ANR")
NATIVE_UNAVAILABLE_TOKENS = (
    "UnsatisfiedLinkError",
    "dlopen failed",
    "LIBRARY_NOT_FOUND",
    "FIXTURE_NATIVE_UNAVAILABLE",
)


def operation_of(probe: dict[str, Any]) -> dict[str, Any]:
    result = probe.get("result") or {}
    operation = result.get("operation") or {}
    if not operation:
        operation = (result.get("faultOperation") or {})
    return operation if isinstance(operation, dict) else {}


def guest_process_name(slot: int) -> str:
    return f"{HOST_PACKAGE}:guest{slot}"


def pidof(serial: str, name: str) -> list[int]:
    dumped = run_adb(serial, ["shell", "pidof", name], check=False)
    pids: list[int] = []
    for token in (dumped.stdout or "").split():
        if token.isdigit():
            pids.append(int(token))
    if pids:
        return pids
    # Android pidof often ignores ":guestN" process names. Parse ps instead.
    ps = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,NAME"], check=False)
    suffix = name.split(":")[-1] if ":" in name else name
    for line in (ps.stdout or "").splitlines():
        if name not in line and f":{suffix}" not in line:
            continue
        token = line.strip().split(None, 1)[0]
        if token.isdigit():
            pids.append(int(token))
    return pids


def process_alive(serial: str, pid: int) -> bool:
    if pid <= 0:
        return False
    dumped = run_adb(serial, ["shell", f"kill -0 {pid}"], check=False)
    if dumped.returncode == 0:
        return True
    status = run_adb(serial, ["shell", f"cat /proc/{pid}/status"], check=False)
    return "Name:" in (status.stdout or "")


def capture_logcat(serial: str, lines: int = 400) -> str:
    try:
        dumped = run_adb(serial, ["logcat", "-d", "-t", str(lines)], check=False)
        return (dumped.stdout or "") + (dumped.stderr or "")
    except Exception as exc:  # noqa: BLE001
        return f"LOGCAT_UNAVAILABLE:{exc}"


def clear_logcat(serial: str) -> None:
    try:
        run_adb(serial, ["logcat", "-c"], check=False)
    except Exception:
        pass


def dumpsys_exit_info(serial: str) -> str:
    dumped = run_adb(
        serial,
        ["shell", "dumpsys", "activity", "exit-info", HOST_PACKAGE],
        check=False,
    )
    if dumped.returncode == 0 and (dumped.stdout or "").strip():
        return dumped.stdout
    alt = run_adb(serial, ["shell", "dumpsys", "dropbox", "--print"], check=False)
    return (alt.stdout or "")[-8000:]


def dumpsys_anr(serial: str) -> str:
    dumped = run_adb(serial, ["shell", "dumpsys", "activity", "anr"], check=False)
    return (dumped.stdout or "") + (dumped.stderr or "")


def snapshot_identity(serial: str, probe: dict[str, Any]) -> dict[str, Any]:
    operation = operation_of(probe)
    slot = int(operation.get("processSlot") or -1)
    isolated = bool(operation.get("isolatedProcess"))
    isolated_pid = int(operation.get("isolatedPlatformPid") or 0)
    reported_pid = int(operation.get("platformPid") or operation.get("pid") or 0)
    name = guest_process_name(slot) if slot >= 0 and not isolated else ""
    pids = pidof(serial, name) if name else []
    if isolated and isolated_pid > 0:
        pids = [isolated_pid]
    elif reported_pid > 0 and reported_pid not in pids:
        pids = [reported_pid] + pids
    return {
        "package": GUEST_PACKAGE,
        "virtualUserId": int(operation.get("virtualUserId") or 0),
        "sessionId": str(operation.get("sessionId") or ""),
        "generation": int(operation.get("generation") or 0),
        "processSlot": slot,
        "processName": name or str(operation.get("processName") or ""),
        "isolatedProcess": isolated,
        "platformPid": pids[0] if pids else 0,
        "allPids": pids,
        "brokerPids": pidof(serial, f"{HOST_PACKAGE}:sandbox_server"),
        "hostPids": pidof(serial, HOST_PACKAGE),
    }


def system_anr_evidence(logcat: str, anr_dump: str, pid: int, process_name: str) -> bool:
    haystack = f"{logcat}\n{anr_dump}"
    if any(token in haystack for token in SYSTEM_ANR_TOKENS):
        if process_name and process_name in haystack:
            return True
        if pid > 0 and re.search(rf"\b{pid}\b", haystack):
            return True
        if HOST_PACKAGE in haystack and "ANR in" in haystack:
            return True
        if "REASON_ANR" in haystack or "reason=ANR" in haystack:
            return True
    return False


def system_crash_evidence(logcat: str, exit_info: str, pid: int, mode: str) -> dict[str, Any]:
    haystack = f"{logcat}\n{exit_info}"
    pid_mentioned = pid > 0 and re.search(rf"\b{pid}\b", haystack) is not None
    fatal = any(token in haystack for token in SYSTEM_CRASH_TOKENS)
    native = "native-segv" in mode or "native-abort" in mode or "isolated-native" in mode
    if native:
        if any(token in haystack for token in NATIVE_UNAVAILABLE_TOKENS):
            return {"osFatal": False, "pidMentioned": pid_mentioned, "unavailable": True}
        sig = ("SIGSEGV" in haystack or "Fatal signal 11" in haystack) if "segv" in mode \
            else ("SIGABRT" in haystack or "Fatal signal 6" in haystack or "Abort message" in haystack)
        return {"osFatal": bool(sig and (pid_mentioned or fatal)), "pidMentioned": pid_mentioned,
                "unavailable": False}
    java = "FATAL EXCEPTION" in haystack or "AndroidRuntime" in haystack
    return {"osFatal": bool(java or fatal), "pidMentioned": pid_mentioned, "unavailable": False}


def classify_fault(mode: str, evidence: dict[str, Any]) -> str:
    recovered = evidence.get("finalRecovery") == "PASS"
    pid_dead = bool(evidence.get("oldPidDead"))
    os_exit = evidence.get("osExitEvidence") or {}
    if mode.startswith("anr"):
        if evidence.get("systemAnr"):
            return "ANR_INDUCED" if recovered else "ANR_INDUCED_RECOVERY_FAIL"
        return "ANR_NOT_PROVEN"
    if os_exit.get("unavailable"):
        return "FAULT_FIXTURE_UNAVAILABLE" if not pid_dead else "KILL_FALLBACK"
    if os_exit.get("osFatal") and pid_dead:
        return "FAULT_INDUCED" if recovered else "FAULT_INDUCED_RECOVERY_FAIL"
    if pid_dead and not os_exit.get("osFatal"):
        return "PROCESS_DEATH_WITHOUT_OS_FATAL"
    return "INCONCLUSIVE"


def stale_session_rejected(old: dict[str, Any], new: dict[str, Any]) -> bool:
    if not old.get("sessionId") or not new.get("sessionId"):
        return False
    session_changed = old["sessionId"] != new["sessionId"]
    generation_changed = int(old.get("generation") or 0) != int(new.get("generation") or 0)
    pid_changed = int(old.get("platformPid") or 0) != int(new.get("platformPid") or 0)
    return bool(session_changed or generation_changed) and pid_changed


def build_fault_evidence(
    *,
    serial: str,
    mode: str,
    before: dict[str, Any],
    after: dict[str, Any],
    logcat: str,
    anr_dump: str,
    exit_info: str,
    inject_ts: str,
) -> dict[str, Any]:
    old_pid = int(before.get("platformPid") or 0)
    new_pid = int(after.get("platformPid") or 0)
    os_exit = system_crash_evidence(logcat, exit_info, old_pid, mode)
    old_dead = old_pid > 0 and not process_alive(serial, old_pid)
    evidence = {
        "targetPackage": GUEST_PACKAGE,
        "virtualUserId": before.get("virtualUserId"),
        "virtualSessionId": before.get("sessionId"),
        "generation": before.get("generation"),
        "processSlot": before.get("processSlot"),
        "platformPid": old_pid,
        "faultMode": mode,
        "injectTimestamp": inject_ts,
        "osExitEvidence": os_exit,
        "logToken": {
            "systemAnr": system_anr_evidence(logcat, anr_dump, old_pid, str(before.get("processName") or "")),
            "fixtureOnlyAnr": any(token in logcat for token in FIXTURE_ONLY_ANR),
            "excerpt": logcat[-4000:],
        },
        "binderDeathEvidence": "GUEST_BINDER_DEAD" in logcat or "DeathRecipient" in logcat,
        "oldPidDead": old_dead,
        "newPid": new_pid,
        "newSession": after.get("sessionId"),
        "newGeneration": after.get("generation"),
        "staleOldTokenRejected": stale_session_rejected(before, after),
        "finalRecovery": "PASS" if after.get("sessionId") else "FAIL",
        "systemAnr": system_anr_evidence(logcat, anr_dump, old_pid, str(before.get("processName") or "")),
        "brokerSurvived": bool(after.get("brokerPids") or before.get("brokerPids")),
    }
    evidence["classification"] = classify_fault(mode, evidence)
    return evidence


def prepare(serial: str) -> dict[str, Any]:
    return debug_command(
        serial,
        ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST_EXTRAS],
        deadline_sec=90,
    )


def start_hold_prepare(serial: str, hold_ms: int = 25000) -> None:
    """Keep the Guest session live so the stub PID can be killed in-process."""
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(
        serial,
        [
            "shell", "am", "start", "-W", "-f", "0x10008000", "-n", DEBUG_ACTIVITY,
            "-e", "command", "hold-prepare",
            "-e", "package", GUEST_PACKAGE,
            "--ez", "trustNativeGuest", "true",
            "--el", "holdMs", str(hold_ms),
        ],
        check=False,
    )


def probe_kill(serial: str) -> dict[str, Any]:
    start_hold_prepare(serial)
    time.sleep(8)
    slot = 54
    name = guest_process_name(slot)
    pids = pidof(serial, name)
    if not pids:
        for candidate in range(64):
            found = pidof(serial, guest_process_name(candidate))
            if found:
                slot = candidate
                name = guest_process_name(candidate)
                pids = found
                break
    pid = pids[0] if pids else 0
    before = {
        "package": GUEST_PACKAGE,
        "virtualUserId": 0,
        "sessionId": "HOLD_PREPARE_LIVE",
        "generation": 0,
        "processSlot": slot,
        "processName": name,
        "isolatedProcess": False,
        "platformPid": pid,
        "allPids": pids,
        "brokerPids": pidof(serial, f"{HOST_PACKAGE}:sandbox_server"),
        "hostPids": pidof(serial, HOST_PACKAGE),
    }
    broker_before = list(before.get("brokerPids") or [])
    if pid <= 0:
        return {
            "before": before,
            "classification": "KILL_TARGET_PID_MISSING",
            "kill": None,
            "recover": None,
        }
    killed = run_adb(serial, ["shell", f"kill -9 {pid}"], check=False)
    time.sleep(2)
    pid_dead = not process_alive(serial, pid)
    broker_after_kill = pidof(serial, f"{HOST_PACKAGE}:sandbox_server")
    recover = prepare(serial)
    after = snapshot_identity(serial, recover)
    classification = "KILL_RECOVERED"
    if not pid_dead:
        classification = "KILL_PID_STILL_ALIVE"
    elif not after.get("sessionId"):
        classification = "KILL_RECOVERY_FAIL"
    elif not stale_session_rejected(before, after):
        classification = "KILL_RECOVERED_SESSION_FENCE_UNPROVEN"
    if broker_before and not broker_after_kill:
        classification = "KILL_BROKER_DIED"
    return {
        "before": before,
        "kill_command": f"kill -9 {pid}",
        "kill_returncode": killed.returncode,
        "kill_stdout": killed.stdout,
        "kill_stderr": killed.stderr,
        "oldPidDead": pid_dead,
        "brokerPidsAfterKill": broker_after_kill,
        "recover": recover,
        "after": after,
        "staleOldTokenRejected": stale_session_rejected(before, after),
        "classification": classification,
    }


def probe_reboot(serial: str, environment: dict[str, Any]) -> dict[str, Any]:
    before = environment.get("boot_id")
    rebooted = run_adb(serial, ["reboot"], check=False)
    if rebooted.returncode != 0:
        return {
            "status": "SKIPPED",
            "classification": "REBOOT_NOT_ISSUED",
            "detail": (rebooted.stderr or rebooted.stdout or "").strip(),
        }
    deadline = time.time() + 180
    last = ""
    while time.time() < deadline:
        time.sleep(5)
        try:
            env = resolve_rd_environment(RD_INSTANCE_NAME)
            if env.get("boot_id") and env.get("boot_id") != before:
                recover = debug_command(
                    env["adb_serial"],
                    ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST_EXTRAS],
                    deadline_sec=120,
                )
                return {
                    "status": "EXECUTED",
                    "classification": (
                        "REBOOT_RECOVERED"
                        if str((recover.get("result") or {}).get("status") or "").upper() == "PASS"
                        else "REBOOT_RECOVERY_FAIL"
                    ),
                    "boot_id_before": before,
                    "boot_id_after": env.get("boot_id"),
                    "recover": recover,
                    "environment_after": env,
                }
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
    return {
        "status": "PARTIAL",
        "classification": "REBOOT_DEVICE_NOT_BACK",
        "boot_id_before": before,
        "detail": "device did not return with a new boot_id within 180s",
        "last": last,
    }


def run_fault_mode(serial: str, mode: str) -> dict[str, Any]:
    clear_logcat(serial)
    baseline = prepare(serial)
    before = snapshot_identity(serial, baseline)
    inject_ts = now_iso()
    deadline = 50 if mode.startswith("anr") else 90
    probe = debug_command(
        serial,
        ["-e", "command", "fault-probe", "-e", "package", GUEST_PACKAGE,
         "-e", "mode", mode, *TRUST_EXTRAS],
        deadline_sec=deadline,
    )
    time.sleep(2)
    logcat = capture_logcat(serial)
    anr_dump = dumpsys_anr(serial) if mode.startswith("anr") else ""
    exit_info = dumpsys_exit_info(serial)
    recover = prepare(serial)
    after = snapshot_identity(serial, recover)
    if before.get("platformPid") == 0:
        before = snapshot_identity(serial, probe) if operation_of(probe) else before
    evidence = build_fault_evidence(
        serial=serial,
        mode=mode,
        before=before,
        after=after,
        logcat=logcat,
        anr_dump=anr_dump,
        exit_info=exit_info,
        inject_ts=inject_ts,
    )
    return {
        "mode": mode,
        "before": before,
        "probe": probe,
        "recover": recover,
        "after": after,
        "anr_dump_excerpt": anr_dump[-3000:],
        "exit_info_excerpt": exit_info[-3000:],
        "faultEvidence": evidence,
        "classification": evidence["classification"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--skip-reboot", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("p2a")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True}
    if not args.skip_install:
        install = install_rd_apks(serial)
    write_json(output / "install.json", install)

    faults: list[dict[str, Any]] = []
    for mode in FAULT_MODES:
        row = run_fault_mode(serial, mode)
        faults.append(row)
        write_json(output / f"fault-{mode}.json", row)

    kill = probe_kill(serial)
    write_json(output / "kill.json", kill)
    reboot = {"skipped": True, "classification": "REBOOT_SKIPPED"}
    if not args.skip_reboot:
        reboot = probe_reboot(serial, environment)
    write_json(output / "reboot.json", reboot)

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "faults": faults,
        "kill": kill,
        "reboot": reboot,
        "classifier": "process-grounded; probe status is not an induced-fault signal",
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "serial": serial,
        "api": environment.get("api_level"),
        "faults": [{"mode": row["mode"], "classification": row["classification"],
                    "pid": (row.get("before") or {}).get("platformPid"),
                    "oldPidDead": (row.get("faultEvidence") or {}).get("oldPidDead"),
                    "recover": ((row["recover"].get("result") or {}).get("status"))}
                   for row in faults],
        "kill": kill.get("classification"),
        "reboot": reboot.get("classification"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
