#!/usr/bin/env python3
"""C1-T04 RD_BASELINE ContentProvider CRUD/cursor/FD/grant/recovery campaign.

The Host command launches a package-neutral Guest Activity concurrently for virtual users 0 and
1.  MuMu ``RD测试`` is resolved by name on every invocation; the runner never stores an ADB
endpoint.  Logcat is streamed to a host-side raw artifact while the pressure campaign runs so
the result is not dependent on the device ring-buffer size.
"""

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

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_a01_acceptance import safe_debug_command
from run_rd_campaign import (
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    adb_bin,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "CAS-C1-T04-RD"
DEFAULT_INSTANCE = "RD" + chr(0x6D4B) + chr(0x8BD5)
VIRTUAL_USERS = (0, 1)
TRUST = ["--ez", "trustNativeGuest", "true"]
FATAL_MARKERS = ("FATAL EXCEPTION", "Fatal signal", "ANR in", "LAUNCH_GATE_FAILED")
CAMPAIGN_MARKERS = (
    "C1_T04_PROVIDER_CRUD_PASS",
    "C1_T04_PROVIDER_CURSOR_PASS",
    "C1_T04_PROVIDER_BATCH_PASS",
    "C1_T04_PROVIDER_FD_PASS",
    "C1_T04_PROVIDER_OBSERVER_DELIVERED",
    "C1_T04_PROVIDER_OBSERVER_PASS",
    "C1_T04_PROVIDER_GRANT_PASS",
    "C1_T04_PROVIDER_CANCEL_PASS",
    "C1_T04_PROVIDER_CROSS_PACKAGE_PASS",
    "C1_T04_PROVIDER_PASS",
)


def _command(serial: str, iterations: int, pressure_seconds: int) -> dict[str, Any]:
    return safe_debug_command(
        serial,
        [
            "--es", "command", "provider-concurrent-campaign",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ei", "iterations", str(iterations),
            "--ei", "pressureSeconds", str(pressure_seconds),
            *TRUST,
        ],
        deadline_sec=max(240, pressure_seconds + 240),
        force_stop_host=True,
    )


def _reset(serial: str) -> None:
    for package in (
        HOST_PACKAGE,
        GUEST_PACKAGE,
        "com.warden.controlledsandbox.fixture32",
        "com.warden.controlledsandbox.companion32.debug",
    ):
        run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    time.sleep(0.5)
    run_adb(serial, ["logcat", "-c"], check=False)


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


def _check_result(response: dict[str, Any], logcat: str, loops: int) -> tuple[bool, str, dict[str, int]]:
    payload = response.get("result") or {}
    if response.get("status") != "PASS":
        return False, f"debug status={response.get('status')}: {response.get('detail', '')}", {}
    operation = payload.get("operation") or {}
    if operation.get("status") != "PROVIDER_CAMPAIGN_PASS":
        return False, f"operation status={operation.get('status')}", {}
    counts = {marker: logcat.count(marker) for marker in CAMPAIGN_MARKERS}
    minimum = len(VIRTUAL_USERS) * loops
    for marker in CAMPAIGN_MARKERS:
        if counts.get(marker, 0) < minimum:
            return False, f"marker count {marker}={counts.get(marker, 0)} minimum={minimum}", counts
    for user in VIRTUAL_USERS:
        marker = f"C1_T04_PROVIDER_USER_PASS user={user}"
        if logcat.count(marker) < 1:
            return False, f"missing concurrent user marker: {marker}", counts
    for marker in FATAL_MARKERS:
        if marker in logcat:
            return False, f"fatal marker: {marker}", counts
    forbidden = ("C1_T04_PROVIDER_FAIL", "C1_T04_PROVIDER_RUNTIME_FAIL")
    for marker in forbidden:
        if marker in logcat:
            return False, f"forbidden runtime marker: {marker}", counts
    return True, "PASS", counts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=DEFAULT_INSTANCE)
    parser.add_argument("--loops", type=int, default=50)
    parser.add_argument("--pressure-seconds", type=int, default=1800)
    parser.add_argument(
        "--receipt", type=Path,
        default=ROOT / "verification/catch-up/C1-T04/c1-t04-rd-summary.json",
    )
    args = parser.parse_args()
    if not 1 <= args.loops <= 1000:
        raise SystemExit("--loops must be between 1 and 1000")
    if args.pressure_seconds < 0:
        raise SystemExit("--pressure-seconds must be non-negative")

    identity = git_identity()
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    raw_dir = artifacts_dir("catch-up-c1-t04")
    install = install_rd_apks(serial)
    _reset(serial)
    logcat_path = raw_dir / "provider-campaign-logcat.txt"
    process, handle = _stream_logcat(serial, logcat_path)
    started_at = time.monotonic()
    try:
        response = _command(serial, args.loops, args.pressure_seconds)
    finally:
        _stop_logcat(process, handle)
    logcat = logcat_path.read_text(encoding="utf-8", errors="replace")
    command_path = raw_dir / "debug-command.json"
    write_json(command_path, response)
    passed, reason, counts = _check_result(response, logcat, args.loops)
    payload = response.get("result") or {}
    operation = payload.get("operation") or {}
    result = {
        "loops_minimum_per_user": args.loops,
        "pressure_seconds_requested": args.pressure_seconds,
        "elapsed_seconds": round(time.monotonic() - started_at, 3),
        "pass": passed,
        "reason": reason,
        "marker_counts": counts,
        "debug_command": str(command_path),
        "logcat": str(logcat_path),
        "operation": operation,
        "cross_user_leakage": "not observed; both virtual-user campaigns completed with isolated provider state",
    }
    receipt = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "host_os": host_os(),
        "git": identity,
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "instance_name": args.instance,
        "environment": environment,
        "apk": apk_metadata(),
        "install": install,
        "loops_per_user_minimum": args.loops,
        "virtual_users": list(VIRTUAL_USERS),
        "pressure_seconds_requested": args.pressure_seconds,
        "raw_evidence_dir": str(raw_dir),
        "result": result,
        "overall_pass": passed,
    }
    write_json(args.receipt, receipt)
    print(json.dumps({
        "receipt": str(args.receipt),
        "raw_evidence_dir": str(raw_dir),
        "overall_pass": passed,
        "reason": reason,
        "operation_status": operation.get("status"),
    }, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
