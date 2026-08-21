#!/usr/bin/env python3
"""C1-T02 RD_BASELINE Service/FGS/Job lifecycle campaign.

The runner resolves MuMu ``RD测试`` dynamically, exercises a package-neutral Guest Service
through the real RuntimeClient/Broker route for both virtual users, and fails closed on missing
ownership, Binder, foreground, cleanup, or generation evidence. Framework-owned FGS transport,
JobWorkItem transport, and process-death recovery remain companion probes because the debug
command deliberately covers the Broker-owned lifecycle path.
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

CAMPAIGN_ID = "CAS-C1-T02-RD"
DEFAULT_INSTANCE = "RD" + chr(0x6D4B) + chr(0x8BD5)
SERVICE_COMPONENT = "com.warden.controlledsandbox.fixture.FixtureService"
VIRTUAL_USERS = (0, 1)
TRUST = ["--ez", "trustNativeGuest", "true"]
FATAL_MARKERS = ("FATAL EXCEPTION", "Fatal signal", "ANR in", "LAUNCH_GATE_FAILED")


def _safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def _command(serial: str, user: int, iterations: int) -> dict[str, Any]:
    return safe_debug_command(
        serial,
        [
            "--es", "command", "service-lifecycle-suite",
            "--es", "package", GUEST_PACKAGE,
            "--es", "serviceComponent", SERVICE_COMPONENT,
            "--ei", "user", str(user),
            "--ei", "iterations", str(iterations),
            *TRUST,
        ],
        deadline_sec=180,
        force_stop_host=True,
    )


def _reset(serial: str) -> None:
    for package in ("com.warden.controlledsandbox.debug", GUEST_PACKAGE):
        run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    time.sleep(0.25)
    run_adb(serial, ["logcat", "-c"], check=False)


def _check_cycle_result(payload: dict[str, Any], logcat: str, expected: int) -> tuple[bool, str]:
    if payload.get("status") != "PASS":
        return False, f"debug status={payload.get('status')}: {payload.get('errorMessage', '')}"
    operation = payload.get("operation") or {}
    if operation.get("status") != "SERVICE_LIFECYCLE_PASS":
        return False, f"operation status={operation.get('status')}"
    cycles = payload.get("serviceLifecycleCycles") or []
    if len(cycles) != expected:
        return False, f"cycle count={len(cycles)} expected={expected}"
    for cycle in cycles:
        if not cycle.get("staleStartIdPreserved"):
            return False, "stale start-id ownership was not preserved"
        if not cycle.get("firstBinder") or not cycle.get("secondBinder"):
            return False, "bound Service Binder missing"
        if not cycle.get("foregroundPromoted") or not cycle.get("foregroundDemoted"):
            return False, "foreground state did not converge"
        if cycle.get("stoppedState") != "DESTROYED":
            return False, f"stopped state={cycle.get('stoppedState')}"
    required_markers = (
        "CS_FIXTURE: SERVICE_CREATE",
        "CS_FIXTURE: SERVICE_START",
        "CS_FIXTURE: SERVICE_BIND",
        "CS_FIXTURE: SERVICE_UNBIND",
        "CS_FIXTURE: SERVICE_DESTROY",
    )
    for marker in required_markers:
        if marker not in logcat:
            return False, f"missing logcat marker: {marker}"
    for marker in FATAL_MARKERS:
        if marker in logcat:
            return False, f"fatal marker: {marker}"
    return True, "PASS"


def run_campaign(
    serial: str,
    environment: dict[str, Any],
    raw_dir: Path,
    loops: int,
    batch_iterations: int,
    pressure_seconds: int,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    started_at = time.monotonic()
    pressure_seconds_per_user = (
        max(1, pressure_seconds // len(VIRTUAL_USERS)) if pressure_seconds > 0 else 0
    )
    for user in VIRTUAL_USERS:
        completed = 0
        user_started_at = time.monotonic()
        target_deadline = user_started_at + pressure_seconds_per_user
        while completed < loops or (
                pressure_seconds_per_user > 0 and time.monotonic() < target_deadline):
            remaining = max(1, loops - completed)
            batch = min(batch_iterations, remaining) if completed < loops else batch_iterations
            if pressure_seconds_per_user > 0 and time.monotonic() >= target_deadline:
                break
            case_dir = raw_dir / f"user-{user}" / f"batch-{len(results) + 1:04d}"
            case_dir.mkdir(parents=True, exist_ok=True)
            _reset(serial)
            response = _command(serial, user, batch)
            payload = response.get("result") or {}
            logcat = run_adb(
                serial, ["logcat", "-d", "-v", "threadtime"], check=False
            ).stdout or ""
            (case_dir / "logcat-full.txt").write_text(logcat, encoding="utf-8", errors="replace")
            (case_dir / "debug-command.json").write_text(
                json.dumps(response, indent=2, ensure_ascii=False, default=str),
                encoding="utf-8",
            )
            passed, reason = _check_cycle_result(payload, logcat, batch)
            row = {
                "user": user,
                "first_cycle": completed + 1,
                "cycle_count": batch,
                "status": response.get("status"),
                "pass": passed,
                "reason": reason,
                "debug_command": str(case_dir / "debug-command.json"),
                "logcat": str(case_dir / "logcat-full.txt"),
            }
            results.append(row)
            print(
                f"user={user} first_cycle={completed + 1:03d} count={batch:02d} "
                f"pass={passed} reason={reason}",
                flush=True,
            )
            completed += batch
            if not passed:
                # Continue only to preserve the batch evidence needed for classification; the
                # aggregate remains fail-closed and the caller cannot accidentally accept it.
                if pressure_seconds <= 0:
                    break
        if completed < loops:
            results.append({
                "user": user,
                "first_cycle": completed + 1,
                "cycle_count": 0,
                "pass": False,
                "reason": "PRESSURE_DEADLINE_BEFORE_REQUIRED_LOOPS",
            })

    expected = len(VIRTUAL_USERS) * loops
    completed_cycles = sum(row.get("cycle_count", 0) for row in results)
    failures = [row for row in results if row.get("pass") is not True]
    return {
        "environment": environment,
        "virtual_users": list(VIRTUAL_USERS),
        "loops_per_user_minimum": loops,
        "batch_iterations": batch_iterations,
        "pressure_seconds_requested": pressure_seconds,
        "pressure_seconds_per_user": pressure_seconds_per_user,
        "pressure_seconds_observed": round(time.monotonic() - started_at, 3),
        "expected_cycle_count": expected,
        "observed_cycle_count": completed_cycles,
        "batch_count": len(results),
        "failed_batch_count": len(failures),
        "overall_pass": completed_cycles >= expected and not failures,
        "failures": failures,
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=DEFAULT_INSTANCE)
    parser.add_argument("--loops", type=int, default=50)
    parser.add_argument("--batch-iterations", type=int, default=5)
    parser.add_argument("--pressure-seconds", type=int, default=0)
    parser.add_argument(
        "--receipt", type=Path,
        default=ROOT / "verification/catch-up/C1-T02/c1-t02-rd-summary.json",
    )
    args = parser.parse_args()
    if not 1 <= args.loops <= 1000:
        raise SystemExit("--loops must be between 1 and 1000")
    if not 1 <= args.batch_iterations <= 100:
        raise SystemExit("--batch-iterations must be between 1 and 100")
    if args.pressure_seconds < 0:
        raise SystemExit("--pressure-seconds must be non-negative")

    identity = git_identity()
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    raw_dir = artifacts_dir("catch-up-c1-t02")
    install = install_rd_apks(serial)
    matrix = run_campaign(
        serial, environment, raw_dir, args.loops, args.batch_iterations, args.pressure_seconds
    )
    receipt = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "host_os": host_os(),
        "git": identity,
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "instance_name": args.instance,
        "service_component": SERVICE_COMPONENT,
        "apk": apk_metadata(),
        "install": install,
        "raw_evidence_dir": str(raw_dir),
        "matrix": matrix,
    }
    write_json(args.receipt, receipt)
    print(json.dumps({
        "receipt": str(args.receipt),
        "raw_evidence_dir": str(raw_dir),
        "overall_pass": matrix["overall_pass"],
        "observed_cycle_count": matrix["observed_cycle_count"],
        "expected_cycle_count": matrix["expected_cycle_count"],
        "failed_batch_count": matrix["failed_batch_count"],
    }, ensure_ascii=False, indent=2))
    return 0 if matrix["overall_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
