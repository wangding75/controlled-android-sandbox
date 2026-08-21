#!/usr/bin/env python3
"""C1-T03 RD_BASELINE Broadcast event-model campaign.

The campaign launches a package-neutral Guest Activity which calls the public Context broadcast
APIs.  The Host command controls one Guest generation per iteration; long campaigns are submitted
in bounded batches so the command-side lifecycle deadline cannot truncate the evidence stream.
Receiver result markers, ordered result assertions, permission filtering, and cleanup evidence are
checked from raw logcat.
MuMu ``RD测试`` is resolved by name for every run and its serial is never a script constant.
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

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_a01_acceptance import safe_debug_command
from run_rd_campaign import (
    GUEST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "CAS-C1-T03-RD"
DEFAULT_INSTANCE = "RD" + chr(0x6D4B) + chr(0x8BD5)
VIRTUAL_USERS = (0, 1)
# MuMu's noisy system/audit log can evict several seconds of Guest evidence. Capture each
# generation immediately after its command completes instead of relying on one ring-buffer dump.
BATCH_SIZE = 1
TRUST = ["--ez", "trustNativeGuest", "true"]
FATAL_MARKERS = ("FATAL EXCEPTION", "Fatal signal", "ANR in", "LAUNCH_GATE_FAILED")
CAMPAIGN_MARKERS = (
    "C1_T03_BROADCAST_PASS",
    "C1_T03_EXPLICIT_RECEIVED",
    "C1_T03_IMPLICIT_HIGH_RECEIVED",
    "C1_T03_IMPLICIT_LOW_RECEIVED",
    "C1_T03_ORDERED_HIGH_RECEIVED",
    "C1_T03_ORDERED_LOW_RECEIVED",
    "C1_T03_ABORT_HIGH_RECEIVED",
    "C1_T03_ASYNC_RECEIVED",
    "C1_T03_ASYNC_FINISHED",
    "C1_T03_PERMISSION_HIGH_RECEIVED",
    "C1_T03_PERMISSION_LOW_RECEIVED",
    "C1_T03_PERMISSION_DENIED_FILTERED",
    "C1_T03_DYNAMIC_RECEIVED",
    "GUEST_RECEIVER_FRAMEWORK_REGISTERED",
    "GUEST_RECEIVER_FRAMEWORK_UNREGISTERED",
)


def _safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def _command(serial: str, user: int, iterations: int) -> dict[str, Any]:
    return safe_debug_command(
        serial,
        [
            "--es", "command", "broadcast-campaign",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user),
            "--ei", "iterations", str(iterations),
            *TRUST,
        ],
        deadline_sec=max(180, iterations * 4),
        force_stop_host=True,
    )


def _reset(serial: str) -> None:
    for package in (
        "com.warden.controlledsandbox.debug",
        "com.warden.controlledsandbox.fixture",
        "com.warden.controlledsandbox.fixture32",
        "com.warden.controlledsandbox.companion32.debug",
    ):
        run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    time.sleep(0.5)
    run_adb(serial, ["logcat", "-c"], check=False)


def _count(logcat: str, marker: str) -> int:
    return logcat.count(marker)


def _check_result(payload: dict[str, Any], logcat: str, expected: int) -> tuple[bool, str, dict[str, Any]]:
    if payload.get("status") != "PASS":
        return False, f"debug status={payload.get('status')}: {payload.get('errorMessage', '')}", {}
    operation = payload.get("operation") or {}
    if operation.get("status") != "BROADCAST_CAMPAIGN_LAUNCHED":
        return False, f"operation status={operation.get('status')}", operation

    counts = {marker: _count(logcat, marker) for marker in CAMPAIGN_MARKERS}
    exact = (
        "C1_T03_BROADCAST_PASS",
        "C1_T03_EXPLICIT_RECEIVED",
        "C1_T03_IMPLICIT_HIGH_RECEIVED",
        "C1_T03_IMPLICIT_LOW_RECEIVED",
        "C1_T03_ORDERED_HIGH_RECEIVED",
        "C1_T03_ORDERED_LOW_RECEIVED",
        "C1_T03_ABORT_HIGH_RECEIVED",
        "C1_T03_ASYNC_RECEIVED",
        "C1_T03_ASYNC_FINISHED",
        "C1_T03_PERMISSION_HIGH_RECEIVED",
        "C1_T03_PERMISSION_LOW_RECEIVED",
        "C1_T03_PERMISSION_DENIED_FILTERED",
        "C1_T03_DYNAMIC_RECEIVED",
        "GUEST_RECEIVER_FRAMEWORK_REGISTERED",
        "GUEST_RECEIVER_FRAMEWORK_UNREGISTERED",
    )
    for marker in exact:
        if counts.get(marker, 0) != expected:
            return False, f"marker count {marker}={counts.get(marker, 0)} expected={expected}", counts

    ordered_passes = (
        "C1_T03_ORDERED_RESULT_PASS action=com.warden.controlledsandbox.fixture.C1_T03_ORDERED",
        "C1_T03_ORDERED_RESULT_PASS action=com.warden.controlledsandbox.fixture.C1_T03_ABORT",
        "C1_T03_ORDERED_RESULT_PASS action=com.warden.controlledsandbox.fixture.C1_T03_ASYNC",
        "C1_T03_ORDERED_RESULT_PASS action=com.warden.controlledsandbox.fixture.C1_T03_PERMISSION",
    )
    for marker in ordered_passes:
        if _count(logcat, marker) != expected:
            return False, f"ordered result marker count {marker}={_count(logcat, marker)} expected={expected}", counts

    forbidden = (
        "C1_T03_ABORT_LOW_UNEXPECTED",
        "C1_T03_ASYNC_FINISH_FAILED",
        "C1_T03_BROADCAST_FAIL",
        "C1_T03_BROADCAST_RUNTIME_FAIL",
    )
    for marker in forbidden:
        if marker in logcat:
            return False, f"forbidden runtime marker: {marker}", counts
    for marker in FATAL_MARKERS:
        if marker in logcat:
            return False, f"fatal marker: {marker}", counts

    high = logcat.find("C1_T03_ORDERED_HIGH_RECEIVED")
    low = logcat.find("C1_T03_ORDERED_LOW_RECEIVED")
    if high < 0 or low < 0 or high > low:
        return False, "ordered high-priority Receiver did not precede low-priority Receiver", counts
    return True, "PASS", counts


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=DEFAULT_INSTANCE)
    parser.add_argument("--loops", type=int, default=50)
    parser.add_argument(
        "--receipt", type=Path,
        default=ROOT / "verification/catch-up/C1-T03/c1-t03-rd-summary.json",
    )
    args = parser.parse_args()
    if not 1 <= args.loops <= 100:
        raise SystemExit("--loops must be between 1 and 100")

    identity = git_identity()
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    raw_dir = artifacts_dir("catch-up-c1-t03")
    install = install_rd_apks(serial)
    results: list[dict[str, Any]] = []
    overall_pass = True
    started_at = time.monotonic()
    for user in VIRTUAL_USERS:
        _reset(serial)
        responses: list[dict[str, Any]] = []
        logcat_parts: list[str] = []
        remaining = args.loops
        while remaining > 0:
            run_adb(serial, ["logcat", "-c"], check=False)
            response = _command(serial, user, min(BATCH_SIZE, remaining))
            responses.append(response)
            logcat_parts.append(
                run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
            )
            if response.get("status") != "PASS":
                break
            remaining -= min(BATCH_SIZE, remaining)
        response = responses[-1] if responses else {"status": "ERROR"}
        payload = response.get("result") or {}
        logcat = "\n".join(logcat_parts)
        case_dir = raw_dir / f"user-{user}"
        case_dir.mkdir(parents=True, exist_ok=True)
        command_record = {
            "status": "PASS" if responses and all(
                item.get("status") == "PASS" for item in responses
            ) and len(responses) == (args.loops + BATCH_SIZE - 1) // BATCH_SIZE else "FAIL",
            "batchSize": BATCH_SIZE,
            "requestedLoops": args.loops,
            "batches": responses,
        }
        (case_dir / "debug-command.json").write_text(
            json.dumps(command_record, indent=2, ensure_ascii=False, default=str), encoding="utf-8"
        )
        (case_dir / "logcat-full.txt").write_text(logcat, encoding="utf-8", errors="replace")
        passed, reason, counts = _check_result(payload, logcat, args.loops)
        row = {
            "user": user,
            "loops": args.loops,
            "pass": passed,
            "reason": reason,
            "debug_command": str(case_dir / "debug-command.json"),
            "logcat": str(case_dir / "logcat-full.txt"),
            "marker_counts": counts,
        }
        results.append(row)
        overall_pass = overall_pass and passed
        print(f"user={user} loops={args.loops} pass={passed} reason={reason}", flush=True)

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
        "loops_per_user": args.loops,
        "virtual_users": list(VIRTUAL_USERS),
        "elapsed_seconds": round(time.monotonic() - started_at, 3),
        "raw_evidence_dir": str(raw_dir),
        "results": results,
        "overall_pass": overall_pass,
    }
    write_json(args.receipt, receipt)
    print(json.dumps({
        "receipt": str(args.receipt),
        "raw_evidence_dir": str(raw_dir),
        "overall_pass": overall_pass,
        "user_count": len(results),
    }, ensure_ascii=False, indent=2))
    return 0 if overall_pass else 1


if __name__ == "__main__":
    raise SystemExit(main())
