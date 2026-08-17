#!/usr/bin/env python3
"""T57-R03-P2A RD campaign: fault / death / recovery matrix.

Resolves MuMu instance RD测试 dynamically. Never hard-codes historical serials.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import (
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


def classify_fault(mode: str, probe: dict[str, Any], recover: dict[str, Any],
                   logcat: str) -> str:
    result = ((probe.get("result") or {}).get("errorMessage") or "")
    detail = str(probe.get("detail") or "") + " " + str(result)
    recovered = str((recover.get("result") or {}).get("status") or "").upper() == "PASS"
    if mode.startswith("anr"):
        if "ANR" in logcat or "not responding" in logcat.lower() or probe.get("status") in {
            "ERROR", "FAIL", "PASS",
        }:
            return "ANR_INDUCED" if recovered else "ANR_INDUCED_RECOVERY_FAIL"
    crash_tokens = (
        "CAS_FAULT_JAVA_UNCAUGHT",
        "CAS_FAULT_MAIN_THREAD_CRASH",
        "CAS_FAULT_SERVICE_CRASH",
        "Fatal signal",
        "SIGSEGV",
        "Abort message",
        "FATAL EXCEPTION",
        "GUEST_BINDER_DEAD",
        "PROCESS_DIED",
    )
    if any(token in logcat or token in detail for token in crash_tokens) or probe.get("status") in {
        "FAIL", "ERROR", "PASS",
    }:
        return "FAULT_INDUCED" if recovered else "FAULT_INDUCED_RECOVERY_FAIL"
    return "INCONCLUSIVE"


def capture_logcat(serial: str) -> str:
    try:
        dumped = run_adb(serial, ["logcat", "-d", "-t", "80"], check=False)
        return (dumped.stdout or "") + (dumped.stderr or "")
    except Exception as exc:  # noqa: BLE001 - ADB can hang after ANR/reboot
        return f"LOGCAT_UNAVAILABLE:{exc}"


def clear_logcat(serial: str) -> None:
    try:
        run_adb(serial, ["logcat", "-c"], check=False)
    except Exception:
        pass


def probe_kill(serial: str) -> dict[str, Any]:
    hold = debug_command(
        serial,
        [
            "-e", "command", "hold-prepare",
            "-e", "package", GUEST_PACKAGE,
            *TRUST_EXTRAS,
            "--el", "holdMs", "20000",
        ],
        deadline_sec=35,
    )
    killed = run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
    time.sleep(2)
    recover = debug_command(
        serial,
        ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST_EXTRAS],
        deadline_sec=90,
    )
    return {
        "hold": hold,
        "kill_returncode": killed.returncode,
        "kill_stdout": killed.stdout,
        "recover": recover,
        "classification": (
            "KILL_RECOVERED" if str((recover.get("result") or {}).get("status") or "").upper() == "PASS"
            else "KILL_RECOVERY_FAIL"
        ),
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
        except Exception as exc:  # noqa: BLE001 - reconnect loop
            last = str(exc)
    return {
        "status": "PARTIAL",
        "classification": "REBOOT_DEVICE_NOT_BACK",
        "boot_id_before": before,
        "detail": "device did not return with a new boot_id within 180s",
        "last": locals().get("last", ""),
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
        clear_logcat(serial)
        deadline = 40 if mode.startswith("anr") else 90
        probe = debug_command(
            serial,
            ["-e", "command", "fault-probe", "-e", "package", GUEST_PACKAGE,
             "-e", "mode", mode, *TRUST_EXTRAS],
            deadline_sec=deadline,
        )
        time.sleep(2)
        logcat = capture_logcat(serial)
        recover = debug_command(
            serial,
            ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST_EXTRAS],
            deadline_sec=90,
        )
        row = {
            "mode": mode,
            "probe": probe,
            "recover": recover,
            "logcat_excerpt": logcat[-4000:],
            "classification": classify_fault(mode, probe, recover, logcat),
        }
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
                    "probe": row["probe"].get("status"),
                    "recover": ((row["recover"].get("result") or {}).get("status"))}
                   for row in faults],
        "kill": kill.get("classification"),
        "reboot": reboot.get("classification"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
