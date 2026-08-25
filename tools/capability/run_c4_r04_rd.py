#!/usr/bin/env python3
"""C4-R04 fail-closed acceptance orchestrator.

Default execution is an offline failure-injection suite.  ``--mode live`` delegates one bounded,
fail-fast live matrix to the C4-R03 readiness collector and records its request-scoped evidence;
the delegate has no automatic launch retry.  Recovery is a separate mode and never changes the
status or artifacts of the first-failure lane.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "capability"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from c4_r04_fail_closed import (  # noqa: E402
    _artifact_index,
    run_failure_injection_suite,
    run_recovery_contract_suite,
    write_json,
)


TASK_ID = "C4-R04"


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def run_live(args: argparse.Namespace) -> dict[str, Any]:
    live_output = args.output / "live-r03"
    command = [
        sys.executable,
        str(TOOLS / "run_c4_r03_rd.py"),
        "--instance-name",
        args.instance_name,
        "--loops",
        str(args.loops),
        "--users",
        args.users,
        "--targets",
        args.targets,
        "--output",
        str(live_output),
    ]
    started = now_iso()
    completed = subprocess.run(command, cwd=ROOT, text=True, encoding="utf-8",
                                errors="replace", capture_output=True, check=False)
    write_json(args.output / "live-command.json", {
        "command": command,
        "startedAt": started,
        "completedAt": now_iso(),
        "returncode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    })
    delegate_summary_path = live_output / "c4-r03-summary.json"
    delegate_summary: dict[str, Any] = {}
    if delegate_summary_path.is_file():
        try:
            delegate_summary = json.loads(delegate_summary_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            delegate_summary = {"parseError": str(error)}
    elif completed.stdout.strip():
        # R03 prints the same machine-readable status after writing its durable
        # summary.  Keep this fallback explicit so a naming drift cannot turn a
        # real PASS into an orchestration failure without evidence.
        try:
            delegate_summary = json.loads(completed.stdout)
        except json.JSONDecodeError as error:
            delegate_summary = {"parseError": str(error)}
    report = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "mode": "live",
        "status": "PASS" if completed.returncode == 0
        and delegate_summary.get("status") == "PASS" else "FAIL",
        "firstFailureStopsLane": True,
        "automaticRetryBudget": 0,
        "fixedSleepReadiness": False,
        "staticMarkersAuthoritative": False,
        "delegate": "tools/capability/run_c4_r03_rd.py",
        "delegateSummary": delegate_summary,
        "artifactRoot": str(live_output.resolve()),
    }
    write_json(args.output / "live-summary.json", report)
    write_json(args.output / "artifact-index.json", {"schemaVersion": 1,
                                                       "root": str(args.output.resolve()),
                                                       "artifacts": _artifact_index(args.output)})
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("failure-injection", "recovery", "live"),
                        default="failure-injection")
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--loops", type=int, default=1)
    parser.add_argument("--users", default="0,1")
    parser.add_argument("--targets", default="fixture,dingtalk,quark,hongguo,fanqie")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "verification/catch-up/C4-R04/acceptance")
    args = parser.parse_args()
    if not 1 <= args.loops <= 50:
        raise SystemExit("--loops must be between 1 and 50")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.mode == "failure-injection":
        report = run_failure_injection_suite(args.output / "failure-injection")
    elif args.mode == "recovery":
        report = run_recovery_contract_suite(args.output / "recovery")
    else:
        report = run_live(args)
    write_json(args.output / "runner-summary.json", report)
    return 0 if report.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
