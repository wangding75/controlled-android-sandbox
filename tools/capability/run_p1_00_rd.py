#!/usr/bin/env python3
"""T57-R03-P1-00 RD campaign: ClassLoader, high-slot transport, PI system-holder.

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
from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_rd_campaign import (
    CampaignBlocked,
    DEBUG_ACTIVITY,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    read_debug_command_result,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "T57-R03-P1-00"
ORDINARY_TARGETS = (0, 1, 7, 8, 31, 32, 62, 63)
ISOLATED_TARGETS = (0, 7, 8, 14, 15)


def debug_command(
    serial: str,
    extras: list[str],
    deadline_sec: int = 90,
    *,
    force_stop_host: bool = True,
) -> dict[str, Any]:
    def extra_value(key: str) -> str:
        try:
            return extras[extras.index(key) + 1]
        except (ValueError, IndexError):
            return ""

    if force_stop_host:
        run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
        for _ in range(50):
            process = run_adb(serial, ["shell", "pidof", HOST_PACKAGE], check=False)
            if not process.stdout.strip():
                break
            time.sleep(0.1)
    for _ in range(50):
        run_adb(
            serial,
            ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"],
            check=False,
        )
        probe = run_adb(
            serial,
            ["shell", "run-as", HOST_PACKAGE, "test", "-f", "files/debug-command-result.json"],
            check=False,
        )
        if probe.returncode != 0:
            break
        time.sleep(0.1)
    start_args = ["shell", "am", "start"]
    if force_stop_host:
        start_args.append("-S")
    start_args.extend(["-W", "-f", "0x10008000", "-n", DEBUG_ACTIVITY, *extras])
    started = run_adb(serial, start_args, check=False)
    try:
        result = read_debug_command_result(
            serial, deadline_sec=deadline_sec,
            expected_command=extra_value("command"),
            expected_package=extra_value("package"),
            expected_request_id=extra_value("requestId"),
        )
        status = str(result.get("status") or "").upper()
        return {
            "status": status,
            "returncode": 0 if status == "PASS" else 1,
            "result": result,
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }
    except CampaignBlocked as exc:
        return {
            "status": "ERROR",
            "returncode": 1,
            "detail": str(exc),
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }


def probe_classloader(serial: str) -> dict[str, Any]:
    payload = debug_command(
        serial,
        [
            "--es", "command", "native-adversarial",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
        ],
        deadline_sec=90,
    )
    result = payload.get("result") or {}
    message = str(result.get("errorMessage") or "")
    if "DexPathList" in message and "controlledsandbox.debug" in message:
        payload["classification"] = "CURRENT_DEFECT"
    elif payload.get("status") == "PASS":
        payload["classification"] = "FIXED"
    else:
        payload["classification"] = "FAIL"
    payload["probe"] = "guest-service-defining-loader"
    return payload


def probe_ordinary_slots(serial: str) -> dict[str, Any]:
    rows = []
    for slot in ORDINARY_TARGETS:
        command = [
            "--es", "command", "slot-campaign",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
            "--ei", "slotTarget", str(slot),
            "--ez", "startService", "true",
        ]
        if slot > 0:
            command.extend(["--es", "processName", f"{GUEST_PACKAGE}:remote"])
        payload = debug_command(
            serial,
            command,
            deadline_sec=120,
        )
        operation = ((payload.get("result") or {}).get("operation") or {})
        observed = operation.get("processSlot", (payload.get("result") or {}).get("operation", {}))
        if isinstance(operation, dict):
            observed = operation.get("processSlot", -1)
        else:
            observed = -1
        rows.append(
            {
                "target": slot,
                "observed": observed,
                "status": payload.get("status"),
                "detail": payload.get("detail") or (payload.get("result") or {}).get("errorMessage"),
                "raw": payload.get("result"),
            }
        )
        run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
        time.sleep(1)
    exhaustion = debug_command(
        serial,
        [
            "--es", "command", "slot-campaign",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
            "--ei", "slotPadCount", "64",
            "--ez", "startService", "false",
        ],
        deadline_sec=60,
    )
    return {"targets": rows, "exhaustion": exhaustion}


def probe_isolated_slots(serial: str) -> dict[str, Any]:
    rows = []
    for slot in ISOLATED_TARGETS:
        payload = debug_command(
            serial,
            [
                "--es", "command", "isolated-service",
                "--es", "package", GUEST_PACKAGE,
                "--ei", "user", "0",
                "--ez", "trustNativeGuest", "true",
                "--es", "component",
                "com.warden.controlledsandbox.fixture.IsolatedFixtureService",
                "--es", "serviceOperation", "start",
                "--ei", "slotTarget", str(slot),
            ],
            deadline_sec=90,
        )
        operation = (payload.get("result") or {}).get("operation") or {}
        observed = operation.get("processSlot", -1) if isinstance(operation, dict) else -1
        rows.append(
            {
                "target": slot,
                "observed": observed,
                "status": payload.get("status"),
                "detail": payload.get("detail") or (payload.get("result") or {}).get("errorMessage"),
            }
        )
        run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
        time.sleep(1)
    seventeenth = debug_command(
        serial,
        [
            "--es", "command", "isolated-service",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
            "--es", "component",
            "com.warden.controlledsandbox.fixture.IsolatedFixtureService",
            "--es", "serviceOperation", "start",
            "--ei", "slotPadCount", "16",
        ],
        deadline_sec=60,
    )
    return {"targets": rows, "seventeenth": seventeenth}


def probe_system_holder(serial: str) -> dict[str, Any]:
    armed = debug_command(
        serial,
        [
            "--es", "command", "pi-system-holder",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
        ],
        deadline_sec=150,
    )
    notifications = run_adb(
        serial, ["shell", "dumpsys", "notification", "--noredact"], check=False
    )
    held = "cas.system.holder" in (notifications.stdout or "") or "CAS system-holder" in (
        notifications.stdout or ""
    )
    pids = run_adb(serial, ["shell", "pidof", GUEST_PACKAGE], check=False)
    if (pids.stdout or "").strip():
        run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
    time.sleep(10)
    delivered = run_adb(
        serial,
        [
            "shell",
            "run-as",
            GUEST_PACKAGE,
            "cat",
            "files/system-holder-delivered.json",
        ],
        check=False,
    )
    after = run_adb(serial, ["shell", "dumpsys", "notification", "--noredact"], check=False)
    return {
        "armed": armed,
        "notification_held_before_kill": held,
        "notification_dump_before": (notifications.stdout or "")[-4000:],
        "delivered_after_kill": {
            "returncode": delivered.returncode,
            "text": (delivered.stdout or "")[-2000:],
        },
        "notification_dump_after": (after.stdout or "")[-2000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("p1-00")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True}
    if not args.skip_install:
        install = install_rd_apks(serial)
    write_json(output / "install.json", install)
    classloader = probe_classloader(serial)
    write_json(output / "classloader.json", classloader)
    ordinary = probe_ordinary_slots(serial)
    write_json(output / "ordinary_slots.json", ordinary)
    isolated = probe_isolated_slots(serial)
    write_json(output / "isolated_slots.json", isolated)
    holder = probe_system_holder(serial)
    write_json(output / "system_holder.json", holder)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "classloader": classloader,
        "ordinary_slots": ordinary,
        "isolated_slots": isolated,
        "system_holder": holder,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({"output": str(output), "serial": serial, "api": environment.get("api_level")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
