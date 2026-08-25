#!/usr/bin/env python3
"""C4-R02 fail-fast package mutation acceptance on dynamically resolved RD测试."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import time
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "capability"))

from run_c4_r01_rd import capture_snapshot, discover_commercial_samples, now_iso, write_json, write_text
from run_p1_00_rd import debug_command
from run_rd_campaign import GUEST_PACKAGE, HOST_PACKAGE, resolve_rd_environment, run_adb

TASK_ID = "C4-R02"
OUTPUT = ROOT / "verification" / "catch-up" / TASK_ID / "rd-acceptance"
TRUST = ["--ez", "trustNativeGuest", "true"]


def package_dump_value(blob: str, key: str) -> str:
    for raw in blob.splitlines():
        line = raw.strip()
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return ""


def discover_dingtalk(serial: str) -> dict[str, Any]:
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"]).stdout
    matches: list[dict[str, Any]] = []
    for line in packages.splitlines():
        package_name = line.removeprefix("package:").strip()
        if not package_name:
            continue
        dump = run_adb(serial, ["shell", "dumpsys", "package", package_name], check=False).stdout
        version_name = package_dump_value(dump, "versionName")
        version_code_line = next((row.strip() for row in dump.splitlines()
                                  if row.strip().startswith("versionCode=")), "")
        version_code = version_code_line.removeprefix("versionCode=").split()[0]
        if version_name != "7.8.10" or version_code != "1178":
            continue
        paths = [row.removeprefix("package:").strip() for row in
                 run_adb(serial, ["shell", "pm", "path", package_name]).stdout.splitlines()
                 if row.startswith("package:")]
        matches.append({
            "label": "DingTalk (identified by required version, package not predeclared)",
            "package": package_name,
            "version_name": version_name,
            "version_code": version_code,
            "base_and_splits": [{"kind": "base" if path.endswith("/base.apk") else "split",
                                  "path": path} for path in paths],
            "split_count": max(0, len(paths) - 1),
            "primary_cpu_abi": package_dump_value(dump, "primaryCpuAbi"),
            "secondary_cpu_abi": package_dump_value(dump, "secondaryCpuAbi"),
            "discovery": "third-party packages -> dumpsys exact required version -> pm path",
        })
    if len(matches) != 1:
        raise RuntimeError(f"DINGTALK_DYNAMIC_DISCOVERY_EXPECTED_ONE:found={len(matches)}")
    return matches[0]


def trace_of(payload: dict[str, Any]) -> dict[str, Any]:
    return ((payload.get("result") or {}).get("packageOperationTrace") or {})


def require_operation(payload: dict[str, Any], expected_request: str, operation: str) -> dict[str, Any]:
    if str(payload.get("status") or "").upper() != "PASS":
        result = payload.get("result") or {}
        raise RuntimeError(f"{operation}_FAILED:{result.get('errorMessage') or payload.get('detail')}")
    trace = trace_of(payload)
    if not trace or trace.get("requestId") != expected_request:
        raise RuntimeError(f"{operation}_TRACE_MISSING_OR_WRONG_REQUEST")
    if trace.get("status") != "SUCCEEDED" or int(trace.get("attempt", -1)) != 1:
        raise RuntimeError(f"{operation}_TRACE_TERMINAL_INVALID:{trace}")
    if int(trace.get("retryBudget", -1)) != 0 or trace.get("retryable") is not False:
        raise RuntimeError(f"{operation}_HIDDEN_RETRY_POLICY:{trace}")
    timings = trace.get("stageTimingsMs") or {}
    if not timings:
        raise RuntimeError(f"{operation}_STAGE_TIMINGS_EMPTY")
    return trace


def command(serial: str, command_name: str, package_name: str, request_id: str,
            deadline: int = 240) -> dict[str, Any]:
    return debug_command(serial, ["--es", "command", command_name,
                                  "--es", "package", package_name,
                                  "--ei", "user", "0",
                                  "--es", "requestId", request_id, *TRUST],
                         deadline_sec=deadline, force_stop_host=True)


def residue(serial: str) -> dict[str, Any]:
    probes = {
        "install": ["shell", "run-as", HOST_PACKAGE, "find", "files", "-name", ".install-*"],
        "transactions": ["shell", "run-as", HOST_PACKAGE, "cat",
                         "files/package-lifecycle-transactions.json"],
        "catalog": ["shell", "run-as", HOST_PACKAGE, "cat", "files/sandbox-catalog.json"],
    }
    out: dict[str, Any] = {}
    for name, args in probes.items():
        result = run_adb(serial, args, check=False)
        out[name] = {"returncode": result.returncode, "stdout": result.stdout,
                     "stderr": result.stderr}
    install_rows = [row for row in out["install"]["stdout"].splitlines() if row.strip()]
    transaction_blob = out["transactions"]["stdout"].strip()
    active_transactions = []
    if transaction_blob:
        try:
            parsed = json.loads(transaction_blob)
            transactions = parsed if isinstance(parsed, list) else parsed.get("transactions", [])
            in_flight_states = {"UPDATING_PREPARE", "UPDATING_SWITCH", "ROLLBACK_PENDING",
                                "RESETTING", "DELETING"}
            active_transactions = [row for row in transactions
                                   if str(row.get("state", "")).upper() in in_flight_states]
        except Exception:
            active_transactions = ["UNPARSEABLE"]
    out["installResidue"] = install_rows
    out["activeTransactions"] = active_transactions
    out["pass"] = not install_rows and not active_transactions
    return out


def cycle(serial: str, sample: dict[str, Any], index: int, rows: list[dict[str, Any]]) -> None:
    package_name = str(sample["package"])
    # adb shell's argv transport does not preserve embedded spaces without an additional
    # quoting layer. Request IDs are deliberately package-derived and shell-token safe.
    safe_package = str(sample["package"]).replace(".", "-")
    prefix = f"c4-r02-{safe_package}-{index:03d}"
    for step, command_name in (("add", "import-prepare"), ("delete", "delete"),
                               ("readd", "import-prepare")):
        request_id = f"{prefix}-{step}-{uuid.uuid4()}"
        started = time.monotonic()
        payload = command(serial, "import-only" if command_name == "import-prepare"
                          else command_name, package_name, request_id)
        trace = require_operation(payload, request_id, f"{sample['label']}:{index}:{step}")
        rows.append({"sample": sample["label"], "package": package_name, "cycle": index,
                     "step": step, "requestId": request_id,
                     "wallMs": round((time.monotonic() - started) * 1000), "trace": trace})


def cycle_resume_state(sample: dict[str, Any], index: int,
                       rows: list[dict[str, Any]]) -> str:
    observed = {str(row.get("step", "")) for row in rows
                if row.get("package") == sample["package"] and row.get("cycle") == index}
    required = {"add", "delete", "readd"}
    if observed == required:
        return "COMPLETE"
    if observed:
        raise RuntimeError(f"PARTIAL_CYCLE_REQUIRES_INVESTIGATION:"
                           f"{sample['package']}:{index}:{sorted(observed)}")
    return "PENDING"


def percentile(values: list[int], percentile_value: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1,
                       max(0, math.ceil(percentile_value * len(ordered)) - 1))]


def main() -> int:
    global OUTPUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--smoke-only", action="store_true")
    parser.add_argument("--skip-first-smoke", action="store_true")
    parser.add_argument("--output", type=Path, default=OUTPUT,
                        help="durable evidence directory; defaults to the C4-R02 lane")
    args = parser.parse_args()
    OUTPUT = args.output if args.output.is_absolute() else ROOT / args.output
    OUTPUT.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance_name)
    serial = str(environment["adb_serial"])
    write_json(OUTPUT / "environment.json", environment)
    samples = discover_commercial_samples(serial, OUTPUT / "discovery")
    samples.append(discover_dingtalk(serial))
    fixture_dump = run_adb(serial, ["shell", "dumpsys", "package", GUEST_PACKAGE]).stdout
    fixture_paths = [row.removeprefix("package:").strip() for row in
                     run_adb(serial, ["shell", "pm", "path", GUEST_PACKAGE]).stdout.splitlines()
                     if row.startswith("package:")]
    fixture = {"label": "package-neutral fixture", "package": GUEST_PACKAGE,
               "version_name": package_dump_value(fixture_dump, "versionName"),
               "version_code": next((row.strip().removeprefix("versionCode=").split()[0]
                                      for row in fixture_dump.splitlines()
                                      if row.strip().startswith("versionCode=")), ""),
               "base_and_splits": fixture_paths, "split_count": max(0, len(fixture_paths) - 1),
               "primary_cpu_abi": package_dump_value(fixture_dump, "primaryCpuAbi")}
    write_json(OUTPUT / "sample-inventory.json", [fixture, *samples])

    rows: list[dict[str, Any]] = []
    if args.skip_first_smoke and (OUTPUT / "operations.json").is_file():
        rows = json.loads((OUTPUT / "operations.json").read_text(encoding="utf-8"))
    summary: dict[str, Any] = {"task": TASK_ID, "startedAt": now_iso(),
                               "environment": environment, "samples": [fixture, *samples],
                               "attempt": 1, "retryBudget": 0, "status": "IN_PROGRESS"}
    write_json(OUTPUT / "summary.json", summary)
    current = fixture
    try:
        # Mandatory first-attempt smoke for the two formerly failing samples; never retry here.
        if not args.skip_first_smoke:
            smoke = [row for row in samples if row["label"] in ("红果免费短剧", "番茄免费小说")]
            for sample in smoke:
                current = sample
                request_id = f"c4-r02-first-{sample['label']}-{uuid.uuid4()}"
                payload = command(serial, "import-only", sample["package"], request_id)
                trace = require_operation(payload, request_id, f"first:{sample['label']}")
                rows.append({"sample": sample["label"], "package": sample["package"], "cycle": 0,
                             "step": "first-attempt", "requestId": request_id, "trace": trace})
                write_json(OUTPUT / f"first-attempt-{sample['package']}.json", payload)
        if not args.smoke_only:
            matrix = [(fixture, 50), *[(sample, 10) for sample in samples]]
            for sample, loops in matrix:
                current = sample
                for index in range(1, loops + 1):
                    if cycle_resume_state(sample, index, rows) == "COMPLETE":
                        continue
                    cycle(serial, sample, index, rows)
                    write_json(OUTPUT / "operations.json", rows)
            concurrent_sample = next(row for row in samples if row["label"] == "番茄免费小说")
            request_id = f"c4-r02-concurrent-{uuid.uuid4()}"
            concurrent = command(serial, "c4-r02-concurrent-add",
                                 concurrent_sample["package"], request_id)
            write_json(OUTPUT / "concurrent-add.json", concurrent)
            if concurrent.get("status") != "PASS":
                raise RuntimeError("CONCURRENT_ADD_DEVICE_GATE_FAILED")
        residue_result = residue(serial)
        write_json(OUTPUT / "residue.json", residue_result)
        if not residue_result["pass"]:
            raise RuntimeError("PACKAGE_TRANSACTION_RESIDUE")
        elapsed = [int(row["trace"].get("elapsedMs", 0)) for row in rows]
        summary.update({"status": "PASS", "completedAt": now_iso(),
                        "operationCount": len(rows), "firstFailureCount": 0,
                        "latencyMs": {"min": min(elapsed) if elapsed else 0,
                                      "median": int(statistics.median(elapsed)) if elapsed else 0,
                                      "p95": percentile(elapsed, .95),
                                      "max": max(elapsed) if elapsed else 0},
                        "residue": residue_result})
        write_json(OUTPUT / "operations.json", rows)
        write_json(OUTPUT / "summary.json", summary)
        return 0
    except Exception as error:
        failure_dir = OUTPUT / "first-failure"
        snapshot = capture_snapshot(serial, failure_dir, str(current.get("package", "")))
        summary.update({"status": "FAIL", "completedAt": now_iso(), "error": str(error),
                        "failedSample": current, "retryDecision": "NO_RETRY",
                        "snapshot": snapshot})
        write_json(OUTPUT / "operations.json", rows)
        write_json(OUTPUT / "summary.json", summary)
        write_text(OUTPUT / "first-failure.txt", str(error) + "\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
