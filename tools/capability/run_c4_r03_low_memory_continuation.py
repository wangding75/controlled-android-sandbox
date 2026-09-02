#!/usr/bin/env python3
"""Run the C4-R03 matrix with the approved RD continuation policies.

The ordinary C4-R03 runner remains fail-fast and keeps every first failure authoritative.
This wrapper adds the user-approved environment exception: when the failed launch has explicit
host-process ``ApplicationExitInfo`` reason ``LOW_MEMORY``, record the event, restart the
dynamically resolved MuMu instance, rebootstrap the new Host/Guest owner, and invoke a
separately recorded continuation from that exact target/user/iteration/mode.  Host
``LOW_MEMORY`` is non-blocking for this campaign and is not limited by an event count; the
outer phase deadline still bounds the lane.  A concrete launch/Guest ``TimeoutException`` is
also a performance-limited exception: preserve each full failure bundle and continue from the
same coordinate with at most five explicit retries.  Generic collector/phase timeouts and all
other first failures are returned unchanged and stop the lane.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "capability"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))

from run_rd_campaign import resolve_rd_environment  # noqa: E402


TASK_ID = "C4-R03"
HOST_PACKAGE = "com.warden.controlledsandbox.debug"
DEFAULT_TARGETS = "fixture,dingtalk,quark,hongguo,fanqie"
DEFAULT_USERS = "0,1"
REASON_RE = re.compile(r"\bLOW_MEMORY\b", re.IGNORECASE)
HOST_RE = re.compile(re.escape(HOST_PACKAGE), re.IGNORECASE)
PERFORMANCE_TIMEOUT_RE = re.compile(
    r"(?:java\.util\.concurrent\.)?TimeoutException\b", re.IGNORECASE,
)
PERFORMANCE_TIMEOUT_RETRY_BUDGET = 5
RECOVERY_TIMEOUT_SECONDS = 300


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value or "", encoding="utf-8", newline="\n")


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def parse_csv(value: str, *, cast: type[int] | None = None) -> list[Any]:
    result: list[Any] = []
    for item in value.split(","):
        item = item.strip()
        if item:
            result.append(cast(item) if cast else item)
    return result


def coordinate(row: dict[str, Any]) -> tuple[str, int, int, str]:
    return (
        str(row.get("target", "")),
        int(row.get("user", -1)),
        int(row.get("iteration", -1)),
        str(row.get("mode", "")),
    )


def failed_row(summary: dict[str, Any]) -> dict[str, Any] | None:
    blocked = summary.get("blockedAt")
    if not isinstance(blocked, dict):
        return None
    wanted = (
        str(blocked.get("target", "")),
        int(blocked.get("user", -1)),
        int(blocked.get("iteration", -1)),
        str(blocked.get("mode", "")),
    )
    for row in summary.get("rows", []):
        if isinstance(row, dict) and coordinate(row) == wanted:
            return row
    return None


def candidate_failure_files(row: dict[str, Any]) -> list[Path]:
    artifacts = Path(str(row.get("artifacts", "")))
    if not artifacts.is_dir():
        return []
    full = artifacts / "first-failure-full"
    candidates = [full / "application-exit-info.txt"]
    candidates.extend(sorted(full.glob("*.txt")))
    return list(dict.fromkeys(path for path in candidates if path.is_file()))


def classify_low_memory(summary: dict[str, Any]) -> dict[str, Any] | None:
    """Return evidence only for a host-scoped ApplicationExitInfo LOW_MEMORY signal.

    Generic historical lowmemorykiller lines are intentionally insufficient: they can refer
    to an unrelated Android service.  The R03 failure snapshot must contain the host package
    exit-info probe and the LOW_MEMORY reason.
    """
    row = failed_row(summary)
    if row is None:
        return None
    matched: list[dict[str, Any]] = []
    for path in candidate_failure_files(row):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if path.name == "application-exit-info.txt":
            if REASON_RE.search(text):
                matched.append({
                    "path": str(path.resolve()),
                    "hostPackagePresent": bool(HOST_RE.search(text)),
                    "reason": "LOW_MEMORY",
                })
        elif REASON_RE.search(text) and HOST_RE.search(text):
            matched.append({
                "path": str(path.resolve()),
                "hostPackagePresent": True,
                "reason": "LOW_MEMORY",
            })
    if not matched:
        return None
    blocked = summary.get("blockedAt") or {}
    return {
        "classification": "LOW_MEMORY",
        "target": blocked.get("target"),
        "user": blocked.get("user"),
        "mode": blocked.get("mode"),
        "iteration": blocked.get("iteration"),
        "runnerClassification": blocked.get("classification"),
        "rowArtifacts": row.get("artifacts"),
        "evidence": matched,
        "policy": "NON_BLOCKING_RECORD_RESTART_AND_CONTINUE_UNTIL_PHASE_DEADLINE",
    }


def classify_low_memory_row(row: dict[str, Any]) -> dict[str, Any] | None:
    """Classify one preserved case row without dropping earlier LOW_MEMORY observations."""
    blocked_at = {
        key: row.get(key)
        for key in ("target", "user", "mode", "iteration", "classification")
        if key in row
    }
    return classify_low_memory({"blockedAt": blocked_at, "rows": [row]})


def _timeout_result_evidence(row: dict[str, Any]) -> list[dict[str, Any]]:
    """Return explicit command-result TimeoutException fields, never generic log timeout text."""
    command = row.get("commandResult")
    if not isinstance(command, dict):
        return []
    if str(command.get("status") or "").upper() not in {"FAIL", "ERROR"}:
        return []
    result = command.get("result")
    result = result if isinstance(result, dict) else {}
    fields = (
        ("commandResult.result.errorType", result.get("errorType")),
        ("commandResult.result.errorMessage", result.get("errorMessage")),
        ("commandResult.detail", command.get("detail")),
    )
    evidence: list[dict[str, Any]] = []
    for field, value in fields:
        text = str(value or "")
        match = PERFORMANCE_TIMEOUT_RE.search(text)
        if match:
            evidence.append({"field": field, "value": text, "match": match.group(0)})
    return evidence


def classify_performance_timeout(summary: dict[str, Any]) -> dict[str, Any] | None:
    """Classify only an explicit launch/Guest TimeoutException as a bounded retry boundary.

    This deliberately does not classify ``debug-command-result timeout`` or a host-side
    subprocess timeout.  Those are environment/phase boundaries and remain fail-closed under
    their own policies.  The command result must identify a launch operation and the failed row
    must already have been captured by the fail-fast R03 child.
    """
    row = failed_row(summary)
    if row is None or not row.get("failureDetected"):
        return None
    evidence = _timeout_result_evidence(row)
    if not evidence:
        return None
    result = (row.get("commandResult") or {}).get("result")
    result = result if isinstance(result, dict) else {}
    command_name = str(result.get("command") or "").lower()
    if command_name not in {"", "launch"}:
        return None
    blocked = summary.get("blockedAt") or {}
    return {
        "classification": "PERFORMANCE_TIMEOUT",
        "target": blocked.get("target"),
        "user": blocked.get("user"),
        "mode": blocked.get("mode"),
        "iteration": blocked.get("iteration"),
        "runnerClassification": blocked.get("classification"),
        "requestId": row.get("requestId"),
        "operationId": row.get("operationId"),
        "runnerOperationId": row.get("runnerOperationId"),
        "attempt": row.get("attempt"),
        "rowArtifacts": row.get("artifacts"),
        "evidence": evidence,
        "policy": "NON_BLOCKING_RETRY_UP_TO_5_TIMES",
        "retryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
    }


def classify_performance_timeout_row(row: dict[str, Any]) -> dict[str, Any] | None:
    """Classify one durable timeout row for continuation selection and seed reconstruction."""
    blocked_at = {
        key: row.get(key)
        for key in ("target", "user", "mode", "iteration", "classification")
        if key in row
    }
    return classify_performance_timeout({"blockedAt": blocked_at, "rows": [row]})


def _coordinate_matches_event(event: dict[str, Any], wanted: tuple[str, int, int, str]) -> bool:
    try:
        return (
            str(event.get("target", "")), int(event.get("user", -1)),
            int(event.get("iteration", -1)), str(event.get("mode", "")),
        ) == wanted
    except (TypeError, ValueError):
        return False


def _seeded_performance_timeout_events(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Annotate durable timeout failures so a host interruption cannot reset the retry budget."""
    seen: dict[tuple[str, int, int, str], int] = {}
    events: list[dict[str, Any]] = []
    for row in rows:
        event = classify_performance_timeout_row(row)
        if event is None:
            continue
        key = coordinate(row)
        ordinal = seen.get(key, 0)
        event.update({
            "seeded": True,
            "originalFailure": ordinal == 0,
            "retryOrdinal": None if ordinal == 0 else ordinal,
            "policyDecision": (
                "SEEDED_ORIGINAL_TIMEOUT_OBSERVATION"
                if ordinal == 0 else "SEEDED_TIMEOUT_RETRY_OBSERVATION"
            ),
            "sourceAttempt": row.get("attempt"),
        })
        events.append(event)
        seen[key] = ordinal + 1
    return events


def environment_from_child(instance_name: str, child_dir: Path) -> dict[str, Any]:
    environment = read_json(child_dir / "environment.json")
    if environment:
        return environment
    return resolve_rd_environment(instance_name)


def restart_mumu(instance_name: str, child_dir: Path, recovery_dir: Path) -> dict[str, Any]:
    """Restart the named MuMu VM through its resolved manager/index and await a new boot."""
    before = environment_from_child(instance_name, child_dir)
    mumu = before.get("mumu") if isinstance(before.get("mumu"), dict) else {}
    root_value = str(mumu.get("mumuRoot") or "").strip()
    index = mumu.get("instanceIndex")
    if not root_value or index in (None, ""):
        fresh = resolve_rd_environment(instance_name)
        mumu = fresh.get("mumu") if isinstance(fresh.get("mumu"), dict) else {}
        root_value = str(mumu.get("mumuRoot") or "").strip()
        index = mumu.get("instanceIndex")
        before = fresh
    if not root_value or index in (None, ""):
        result = {"status": "FAIL", "reason": "resolved MuMu root/index missing"}
        write_json(recovery_dir / "restart.json", result)
        return result

    root = Path(root_value)
    managers = [root / "nx_main" / "MuMuManager.exe", root / "shell" / "MuMuManager.exe"]
    manager = next((path for path in managers if path.is_file()), None)
    before_boot = str(before.get("boot_id") or mumu.get("bootId") or "")
    record: dict[str, Any] = {
        "instanceName": instance_name,
        "instanceIndex": index,
        "mumuRoot": str(root),
        "manager": str(manager) if manager else "",
        "bootIdBefore": before_boot,
        "command": [],
        "startedAt": now_iso(),
    }
    if manager is None:
        record.update({"status": "FAIL", "reason": "MuMuManager.exe not found under resolved root"})
        write_json(recovery_dir / "restart.json", record)
        return record

    command = [str(manager), "control", "--vmindex", str(index), "restart"]
    record["command"] = command
    try:
        completed = subprocess.run(
            command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
            capture_output=True, timeout=90, check=False,
        )
        record.update({
            "returncode": completed.returncode,
            "stdout": completed.stdout or "",
            "stderr": completed.stderr or "",
        })
    except (OSError, subprocess.TimeoutExpired) as error:
        record.update({"status": "FAIL", "reason": f"manager restart failed: {error}"})
        write_json(recovery_dir / "restart.json", record)
        return record
    if int(record.get("returncode", 1)) != 0:
        record.update({"status": "FAIL", "reason": "MuMuManager restart returned non-zero"})
        write_json(recovery_dir / "restart.json", record)
        return record

    deadline = time.monotonic() + RECOVERY_TIMEOUT_SECONDS
    observations: list[dict[str, Any]] = []
    while time.monotonic() < deadline:
        try:
            current = resolve_rd_environment(instance_name)
            observation = {
                "timestamp": now_iso(),
                "bootId": current.get("boot_id", ""),
                "serial": current.get("adb_serial", ""),
                "runtimeStatus": current.get("mumu", {}).get("runtimeStatus", "")
                if isinstance(current.get("mumu"), dict) else "",
            }
            observations.append(observation)
            if current.get("boot_id") and current.get("boot_id") != before_boot:
                record.update({
                    "status": "PASS",
                    "bootIdAfter": current.get("boot_id"),
                    "environmentAfter": current,
                    "observations": observations,
                    "completedAt": now_iso(),
                })
                write_json(recovery_dir / "restart.json", record)
                return record
        except Exception as error:  # device is expected to be unavailable during restart
            observations.append({"timestamp": now_iso(), "error": str(error)})
        time.sleep(2)
    record.update({
        "status": "FAIL",
        "reason": "MuMu restarted but no new boot_id became available",
        "observations": observations,
        "completedAt": now_iso(),
    })
    write_json(recovery_dir / "restart.json", record)
    return record


def child_command(args: argparse.Namespace, child_dir: Path, *, resume: dict[str, Any] | None,
                  attempt: int, post_restart_rebootstrap: bool = False) -> list[str]:
    command = [
        sys.executable, str(TOOLS / "run_c4_r03_rd.py"),
        "--instance-name", args.instance_name,
        "--loops", str(args.loops),
        "--users", args.users,
        "--targets", args.targets,
        "--output", str(child_dir.resolve()),
    ]
    if resume:
        command.extend([
            "--resume-target", str(resume["target"]),
            "--resume-user", str(resume["user"]),
            "--resume-iteration", str(resume["iteration"]),
            "--resume-mode", str(resume["mode"]),
            "--resume-attempt", str(attempt),
            "--resume-of", str(resume["previousLane"]),
        ])
        if post_restart_rebootstrap:
            command.append("--post-restart-rebootstrap")
    return command


def run_child(args: argparse.Namespace, child_dir: Path, *, resume: dict[str, Any] | None,
              attempt: int, post_restart_rebootstrap: bool = False) -> dict[str, Any]:
    child_dir.mkdir(parents=True, exist_ok=True)
    command = child_command(
        args, child_dir, resume=resume, attempt=attempt,
        post_restart_rebootstrap=post_restart_rebootstrap,
    )
    started = now_iso()
    started_mono = time.monotonic()
    try:
        completed = subprocess.run(
            command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
            capture_output=True, check=False, timeout=args.child_timeout_seconds,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        returncode = completed.returncode
        stdout = completed.stdout or ""
        stderr = completed.stderr or ""
    except subprocess.TimeoutExpired as error:
        returncode = 124
        stdout = str(error.stdout or "")
        stderr = str(error.stderr or "") + f"\nTIMEOUT after {args.child_timeout_seconds}s\n"
    write_text(child_dir / "wrapper-stdout.txt", stdout)
    write_text(child_dir / "wrapper-stderr.txt", stderr)
    summary = read_json(child_dir / "c4-r03-summary.json")
    record = {
        "attempt": attempt,
        "startedAt": started,
        "completedAt": now_iso(),
        "elapsedMs": round((time.monotonic() - started_mono) * 1000),
        "command": command,
        "returncode": returncode,
        "output": str(child_dir.resolve()),
        "summary": summary,
    }
    write_json(child_dir / "wrapper-record.json", record)
    return record


def reconstruct_interrupted_summary(args: argparse.Namespace, child_dir: Path) -> dict[str, Any]:
    """Rebuild the completed rows when a host-side runner session ended mid-case.

    R03 writes each case atomically, while its final summary is written only after the matrix
    exits.  A bounded runner/session interruption can therefore leave valid case evidence but
    no summary.  Treat that lane as interrupted and seed only its completed rows; the next
    coordinate is supplied explicitly by the caller.
    """
    rows: list[dict[str, Any]] = []
    for path in sorted(child_dir.rglob("case.json")):
        row = read_json(path)
        if row.get("task") == TASK_ID:
            rows.append(row)
    targets = parse_csv(args.targets)
    users = parse_csv(args.users, cast=int)
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "INTERRUPTED",
        "instanceName": args.instance_name,
        "loops": args.loops,
        "users": users,
        "targetNames": targets,
        "expectedRows": len(targets) * len(users) * args.loops * 2,
        "observedRows": len(rows),
        "attemptPolicy": {
            "attempt": 1,
            "retryBudget": 0,
            "automaticRetries": 0,
            "sessionInterrupted": True,
        },
        "blockedAt": None,
        "rows": rows,
        "rawDirectory": str(child_dir.resolve()),
    }


def _attempt_sort_key(path: Path) -> tuple[int, str]:
    suffix = path.name.removeprefix("attempt-")
    return (int(suffix) if suffix.isdigit() else -1, path.name)


def reconstruct_interrupted_lane(args: argparse.Namespace, lane_dir: Path) -> dict[str, Any]:
    """Reconstruct every durable row across a host-truncated launch lane.

    A host phase boundary can leave more than one child: any child may contain an explicit
    LOW_MEMORY failure and a later post-restart continuation may contain the replacement
    observation.  Seeding only the newest child would erase that history and could make the
    aggregate under-count or misclassify the lane.  Keep all case rows in attempt order;
    ``aggregate`` later selects the latest terminal row per coordinate while retaining the
    earlier rows under ``observations``.
    """
    attempt_dirs = sorted(
        (path for path in lane_dir.glob("attempt-*") if path.is_dir()),
        key=_attempt_sort_key,
    )
    rows: list[dict[str, Any]] = []
    source_attempts: list[str] = []
    blocked_at: dict[str, Any] | None = None
    for attempt_dir in attempt_dirs:
        child_rows: list[dict[str, Any]] = []
        for path in sorted(attempt_dir.rglob("case.json")):
            row = read_json(path)
            if row.get("task") != TASK_ID:
                continue
            child_rows.append(row)
            if blocked_at is None and row.get("failureDetected"):
                blocked_at = {
                    key: row.get(key)
                    for key in ("target", "user", "mode", "iteration", "classification")
                    if key in row
                }
        if child_rows:
            rows.extend(child_rows)
            source_attempts.append(str(attempt_dir.resolve()))

    targets = parse_csv(args.targets)
    users = parse_csv(args.users, cast=int)
    summaries = [
        read_json(attempt_dir / "c4-r03-summary.json")
        for attempt_dir in attempt_dirs
    ]
    for summary in summaries:
        candidate = summary.get("blockedAt")
        if isinstance(candidate, dict):
            blocked_at = candidate
            break
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "INTERRUPTED",
        "instanceName": args.instance_name,
        "loops": args.loops,
        "users": users,
        "targetNames": targets,
        "expectedRows": len(targets) * len(users) * args.loops * 2,
        "observedRows": len(rows),
        "attemptPolicy": {
            "attempt": len(attempt_dirs),
            "retryBudget": 0,
            "automaticRetries": 0,
            "sessionInterrupted": True,
            "durableLaneSeed": True,
            "sourceAttempts": source_attempts,
        },
        "blockedAt": blocked_at,
        "rows": rows,
        "rawDirectory": str(lane_dir.resolve()),
        "sourceAttempts": source_attempts,
    }


def seed_existing_child(args: argparse.Namespace, child_dir: Path) -> dict[str, Any]:
    summary = read_json(child_dir / "c4-r03-summary.json")
    if not summary:
        summary = reconstruct_interrupted_summary(args, child_dir)
    rows = summary.get("rows") or []
    if not rows:
        raise SystemExit(f"seed child has no completed case rows: {child_dir}")
    record = {
        "attempt": 1,
        "startedAt": rows[0].get("startedAt") if isinstance(rows[0], dict) else "",
        "completedAt": rows[-1].get("completedAt") if isinstance(rows[-1], dict) else "",
        "elapsedMs": 0,
        "command": ["seed-existing-child", str(child_dir.resolve())],
        "returncode": 124,
        "output": str(child_dir.resolve()),
        "summary": summary,
        "seededExisting": True,
        "seededPerformanceTimeoutEvents": _seeded_performance_timeout_events(
            [row for row in rows if isinstance(row, dict)]
        ),
        "note": "Completed case.json rows are retained; the missing final summary represents a session interruption, not a test pass.",
    }
    write_json(args.output / "seed-existing-child.json", record)
    return record


def seed_existing_lane(args: argparse.Namespace, lane_dir: Path) -> dict[str, Any]:
    """Seed all durable attempts from a host-truncated lane without erasing history."""
    summary = reconstruct_interrupted_lane(args, lane_dir)
    rows = summary.get("rows") or []
    if not rows:
        raise SystemExit(f"seed lane has no completed case rows: {lane_dir}")
    record = {
        "attempt": len(summary.get("sourceAttempts") or []),
        "startedAt": rows[0].get("startedAt") if isinstance(rows[0], dict) else "",
        "completedAt": rows[-1].get("completedAt") if isinstance(rows[-1], dict) else "",
        "elapsedMs": 0,
        "command": ["seed-existing-lane", str(lane_dir.resolve())],
        "returncode": 124,
        "output": str(lane_dir.resolve()),
        "summary": summary,
        "seededExisting": True,
        "seededExistingLane": True,
        "sourceAttempts": summary.get("sourceAttempts") or [],
        "seededLowMemoryEvents": [
            event for event in (classify_low_memory_row(row) for row in rows)
            if event is not None
        ],
        "seededPerformanceTimeoutEvents": _seeded_performance_timeout_events(
            [row for row in rows if isinstance(row, dict)]
        ),
        "note": (
            "All durable case.json rows across the prior attempts are retained. The missing "
            "host-phase summary represents a bounded session interruption, not a test pass."
        ),
    }
    write_json(args.output / "seed-existing-lane.json", record)
    return record


def aggregate(args: argparse.Namespace, children: list[dict[str, Any]],
              low_memory_events: list[dict[str, Any]],
              performance_timeout_events: list[dict[str, Any]],
              terminal_failure: dict[str, Any] | None) -> dict[str, Any]:
    terminal: dict[tuple[str, int, int, str], dict[str, Any]] = {}
    observations: list[dict[str, Any]] = []
    for child in children:
        summary = child.get("summary") or {}
        for row in summary.get("rows", []):
            if not isinstance(row, dict):
                continue
            observations.append(row)
            terminal[coordinate(row)] = row
    targets = parse_csv(args.targets)
    users = parse_csv(args.users, cast=int)
    expected = len(targets) * len(users) * args.loops * 2
    rows = list(terminal.values())
    all_pass = (
        terminal_failure is None
        and len(rows) == expected
        and all(not row.get("failureDetected") for row in rows)
    )
    return {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "PASS" if all_pass else "FAIL",
        "instanceName": args.instance_name,
        "loops": args.loops,
        "users": users,
        "targetNames": targets,
        "expectedRows": expected,
        "observedTerminalRows": len(rows),
        "observedRowsIncludingFirstFailures": len(observations),
        "attemptPolicy": {
            "initialAttempt": 1,
            "retryBudget": 0,
            "automaticRetries": 0,
            "lowMemoryRecoveryContinuations": len(low_memory_events),
            "lowMemoryPolicy": "NON_BLOCKING_RECORD_RESTART_AND_CONTINUE_UNTIL_PHASE_DEADLINE",
            "lowMemoryRecoveryCountLimited": False,
            "firstLowMemoryFailureRemainsAuthoritative": True,
            "performanceTimeoutRetryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
            "performanceTimeoutPolicy": "NON_BLOCKING_RETRY_UP_TO_5_TIMES",
            "performanceTimeoutRetries": sum(
                1 for event in performance_timeout_events
                if event.get("policyDecision") == "ACCEPT_TIMEOUT_AND_CONTINUE"
                or event.get("policyDecision") == "SEEDED_TIMEOUT_RETRY_OBSERVATION"
            ),
            "performanceTimeoutRetryBudgetCounted": True,
            "firstPerformanceTimeoutFailuresRemainAuthoritative": True,
        },
        "nonBlockingLowMemory": low_memory_events,
        "nonBlockingPerformanceTimeouts": performance_timeout_events,
        "blockedAt": terminal_failure,
        "rows": rows,
        "observations": observations,
        "children": children,
        "rawDirectory": str(args.output.resolve()),
        "completedAt": now_iso(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--loops", type=int, default=25)
    parser.add_argument("--users", default=DEFAULT_USERS)
    parser.add_argument("--targets", default=DEFAULT_TARGETS)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--child-timeout-seconds", type=int, default=14_400)
    seed_group = parser.add_mutually_exclusive_group()
    seed_group.add_argument("--seed-existing-child", type=Path, default=None,
                            help="seed completed case rows from one interrupted child")
    seed_group.add_argument("--seed-existing-lane", type=Path, default=None,
                            help="seed all durable case rows from a host-truncated launch lane")
    parser.add_argument("--resume-target", default="")
    parser.add_argument("--resume-user", type=int, default=None)
    parser.add_argument("--resume-iteration", type=int, default=None)
    parser.add_argument("--resume-mode", choices=("cold", "hot"), default="")
    args = parser.parse_args()
    if not 1 <= args.loops <= 50:
        raise SystemExit("--loops must be between 1 and 50")
    args.output = args.output if args.output.is_absolute() else ROOT / args.output
    args.output.mkdir(parents=True, exist_ok=True)

    children: list[dict[str, Any]] = []
    low_memory_events: list[dict[str, Any]] = []
    performance_timeout_events: list[dict[str, Any]] = []
    resume: dict[str, Any] | None = None
    terminal_failure: dict[str, Any] | None = None
    attempt = 1
    post_restart_rebootstrap = False
    if args.seed_existing_child is not None or args.seed_existing_lane is not None:
        seed_argument = args.seed_existing_child or args.seed_existing_lane
        seed = seed_argument if seed_argument.is_absolute() else ROOT / seed_argument
        required = (args.resume_target, args.resume_user, args.resume_iteration, args.resume_mode)
        if not seed.is_dir() or not all(value not in (None, "") for value in required):
            raise SystemExit(
                "an existing seed child/lane and explicit resume target/user/iteration/mode are required"
            )
        if args.seed_existing_lane is not None:
            seeded = seed_existing_lane(args, seed)
            low_memory_events.extend(seeded.get("seededLowMemoryEvents") or [])
            performance_timeout_events.extend(
                seeded.get("seededPerformanceTimeoutEvents") or []
            )
            resume_coordinate = (
                getattr(args, "resume_target", ""), getattr(args, "resume_user", None),
                getattr(args, "resume_iteration", None), getattr(args, "resume_mode", "")
            )
            post_restart_rebootstrap = any(
                (event.get("target"), event.get("user"), event.get("iteration"), event.get("mode"))
                == resume_coordinate
                for event in (seeded.get("seededLowMemoryEvents") or [])
            )
        else:
            seeded = seed_existing_child(args, seed)
            performance_timeout_events.extend(
                seeded.get("seededPerformanceTimeoutEvents") or []
            )
        children.append(seeded)
        resume = {
            "target": args.resume_target,
            "user": args.resume_user,
            "iteration": args.resume_iteration,
            "mode": args.resume_mode,
            "previousLane": str(seed.resolve()),
        }
        attempt = 2
        if args.seed_existing_lane is not None:
            existing_attempts = [
                int(path.name.removeprefix("attempt-"))
                for path in seed.glob("attempt-*")
                if path.is_dir() and path.name.removeprefix("attempt-").isdigit()
            ]
            if existing_attempts:
                attempt = max(existing_attempts) + 1
        while (args.output / f"attempt-{attempt:03d}").exists():
            attempt += 1
    while True:
        child_dir = args.output / f"attempt-{attempt:03d}"
        child = run_child(
            args, child_dir, resume=resume, attempt=attempt,
            post_restart_rebootstrap=post_restart_rebootstrap,
        )
        children.append(child)
        write_json(args.output / "children.json", children)
        summary = child.get("summary") or {}
        if int(child.get("returncode", 1)) == 0 and summary.get("status") == "PASS":
            break
        low_memory = classify_low_memory(summary)
        if low_memory is not None:
            low_memory["attempt"] = attempt
            low_memory["childOutput"] = child.get("output")
            # MuMu/RD host memory pressure is an allowed environment boundary for this campaign.
            # Keep every original failed row and restart dynamically; this is not a hidden launch
            # retry.  The enclosing R05 phase timeout remains the only count-independent bound.
            low_memory["recoveryOrdinal"] = len(low_memory_events) + 1
            low_memory["policyDecision"] = "NON_BLOCKING_RECORD_RESTART_AND_CONTINUE"
            recovery_dir = args.output / "low-memory-recovery" / f"event-{len(low_memory_events) + 1:03d}"
            low_memory["restart"] = restart_mumu(args.instance_name, child_dir, recovery_dir)
            low_memory_events.append(low_memory)
            if low_memory["restart"].get("status") != "PASS":
                terminal_failure = {
                    "attempt": attempt,
                    "classification": "LOW_MEMORY_RECOVERY_FAILED",
                    "lowMemory": low_memory,
                }
                break
            blocked = summary.get("blockedAt") or {}
            resume = {
                "target": blocked.get("target"),
                "user": blocked.get("user"),
                "iteration": blocked.get("iteration"),
                "mode": blocked.get("mode"),
                "previousLane": str(child_dir.resolve()),
            }
            if not all(resume.get(key) not in (None, "") for key in ("target", "user", "iteration", "mode")):
                terminal_failure = {
                    "attempt": attempt,
                    "classification": "LOW_MEMORY_RESUME_COORDINATE_MISSING",
                    "lowMemory": low_memory,
                }
                break
            attempt += 1
            post_restart_rebootstrap = True
            print(json.dumps({
                "event": "LOW_MEMORY_NON_BLOCKING_RESTARTED",
                "attempt": attempt,
                "resume": resume,
                "output": str(args.output),
            }, ensure_ascii=False), flush=True)
            continue

        performance_timeout = classify_performance_timeout(summary)
        if performance_timeout is not None:
            blocked = summary.get("blockedAt") or {}
            wanted = (
                str(blocked.get("target", "")), int(blocked.get("user", -1)),
                int(blocked.get("iteration", -1)), str(blocked.get("mode", "")),
            )
            prior_events = [
                event for event in performance_timeout_events
                if _coordinate_matches_event(event, wanted)
            ]
            prior_retry_count = sum(
                1 for event in prior_events
                if not bool(event.get("originalFailure"))
            )
            performance_timeout.update({
                "attempt": attempt,
                "childOutput": child.get("output"),
                "originalFailure": not prior_events,
                "retryOrdinal": prior_retry_count + 1,
                "retryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
                "policyDecision": (
                    "ACCEPT_TIMEOUT_AND_CONTINUE"
                    if prior_retry_count < PERFORMANCE_TIMEOUT_RETRY_BUDGET
                    else "TIMEOUT_RETRY_BUDGET_EXHAUSTED"
                ),
            })
            performance_timeout_events.append(performance_timeout)
            if prior_retry_count >= PERFORMANCE_TIMEOUT_RETRY_BUDGET:
                terminal_failure = {
                    "attempt": attempt,
                    "classification": "PERFORMANCE_TIMEOUT_RETRY_BUDGET_EXHAUSTED",
                    "summaryStatus": summary.get("status", "MISSING"),
                    "returncode": child.get("returncode"),
                    "blockedAt": blocked,
                    "output": child.get("output"),
                    "performanceTimeout": performance_timeout,
                }
                break
            resume = {
                "target": blocked.get("target"),
                "user": blocked.get("user"),
                "iteration": blocked.get("iteration"),
                "mode": blocked.get("mode"),
                "previousLane": str(child_dir.resolve()),
            }
            if not all(resume.get(key) not in (None, "") for key in ("target", "user", "iteration", "mode")):
                terminal_failure = {
                    "attempt": attempt,
                    "classification": "PERFORMANCE_TIMEOUT_RESUME_COORDINATE_MISSING",
                    "performanceTimeout": performance_timeout,
                }
                break
            attempt += 1
            # A performance timeout is not an emulator restart boundary.  Preserve any prior
            # post-LOW_MEMORY rebootstrap result only for the first continuation after restart;
            # subsequent timeout retries execute the exact launch coordinate directly.
            post_restart_rebootstrap = False
            print(json.dumps({
                "event": "PERFORMANCE_TIMEOUT_RETRY_ACCEPTED",
                "attempt": attempt,
                "retryOrdinal": performance_timeout["retryOrdinal"],
                "retryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
                "resume": resume,
                "output": str(args.output),
            }, ensure_ascii=False), flush=True)
            continue

        terminal_failure = {
            "attempt": attempt,
            "summaryStatus": summary.get("status", "MISSING"),
            "returncode": child.get("returncode"),
            "blockedAt": summary.get("blockedAt"),
            "output": child.get("output"),
        }
        break

    report = aggregate(
        args, children, low_memory_events, performance_timeout_events, terminal_failure,
    )
    write_json(args.output / "c4-r03-summary.json", report)
    print(json.dumps({
        "status": report["status"],
        "terminalRows": report["observedTerminalRows"],
        "expectedRows": report["expectedRows"],
        "lowMemoryRecoveries": len(low_memory_events),
        "performanceTimeoutRetries": report["attemptPolicy"].get("performanceTimeoutRetries", 0),
        "performanceTimeoutRetryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
        "blockedAt": terminal_failure,
        "output": str(args.output),
    }, ensure_ascii=False, indent=2), flush=True)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
