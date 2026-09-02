#!/usr/bin/env python3
"""C4-R05 formal RD revalidation and closure orchestrator.

The orchestrator owns the C4-R05 evidence boundary.  It builds one commit, resolves the
MuMu instance by name, runs the configured stage or overall acceptance rounds, and stops at the
first non-PASS phase.  The launch-matrix child delegates its individual observations to
``run_c4_r03_rd.py`` and has two explicit performance/environment boundaries: a host-scoped
LOW_MEMORY exit is recorded, the emulator is restarted, and the matrix continues from a
separately recorded coordinate; a concrete launch/Guest TimeoutException is preserved and
retried from that exact coordinate at most five times.  Child campaigns keep their own
request-scoped raw evidence; this runner records the command, summary, commit and phase
decision without hiding a retry or changing the launch readiness SLO.  If the bounded host-side
phase envelope expires, the complete child process tree is terminated before durable-lane
classification or continuation so a stale nested child cannot overlap the next APK
install/rebootstrap operation.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "capability"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from run_p1_00_rd import debug_command  # noqa: E402
from run_c4_r03_rd import capture_snapshot  # noqa: E402
from run_c4_r03_low_memory_continuation import (  # noqa: E402
    PERFORMANCE_TIMEOUT_RETRY_BUDGET,
    classify_performance_timeout_row,
    restart_mumu,
)
from run_rd_campaign import (  # noqa: E402
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)


TASK_ID = "C4-R05"
FIRST_FRAME_STAGE = "FIRST_FRAME_DRAWN"
DEFAULT_OUTPUT = ROOT / "verification" / "catch-up" / TASK_ID
DEFAULT_PHASE_TIMEOUT_SECONDS = 12 * 60 * 60
APK_PATHS = {
    "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
    "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
    "companion32": ROOT / "sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk",
    "fixture32": ROOT / "fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk",
}
LOW_MEMORY_RE = re.compile(r"\bLOW_MEMORY\b", re.IGNORECASE)
HOST_PACKAGE_RE = re.compile(re.escape(HOST_PACKAGE), re.IGNORECASE)
ENVIRONMENT_INTERRUPTION_RE = re.compile(
    r"RD_ENVIRONMENT_RESOLUTION_BLOCKED|device\s+(?:offline|not found)|"
    r"cannot identify image file",
    re.IGNORECASE,
)


class PhaseFailure(RuntimeError):
    """A durable first failure that must stop the formal lane."""

    def __init__(self, phase: str, detail: str, evidence: dict[str, Any] | None = None):
        super().__init__(f"{phase}: {detail}")
        self.phase = phase
        self.detail = detail
        self.evidence = evidence or {}


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_value(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, encoding="utf-8",
                            errors="replace", capture_output=True, check=False)
    return (result.stdout or "").strip()


def artifact_index(root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not root.exists():
        return rows
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        rows.append({"path": relative, "bytes": path.stat().st_size,
                     "sha256": sha256_file(path)})
    return rows


def safe_name(value: str) -> str:
    return "".join(char if char.isalnum() or char in "-_" else "_" for char in value)


def _process_output(value: Any) -> str:
    """Normalize subprocess output from both text and timeout exception paths."""
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def terminate_process_tree(pid: int) -> dict[str, Any]:
    """Terminate a timed-out phase and every nested runner it owns.

    ``subprocess.run(timeout=...)`` only terminates its direct child on Windows.  R05 nests
    the low-memory wrapper and R03 runner, so leaving descendants alive permits a continuation's
    ``install -r`` to race the old child at the same device.  The termination is deliberately
    bounded and fully recorded; it is a process-boundary cleanup, never a test retry.
    """
    record: dict[str, Any] = {"attempted": True, "pid": pid}
    if pid <= 0:
        record.update({"status": "SKIPPED", "reason": "invalid pid"})
        return record
    if os.name == "nt":
        command = ["taskkill", "/PID", str(pid), "/T", "/F"]
        record["command"] = command
        try:
            completed = subprocess.run(
                command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
                capture_output=True, check=False, timeout=30,
            )
            record.update({
                "status": "PASS" if completed.returncode == 0 else "NOT_FOUND_OR_FAILED",
                "returncode": completed.returncode,
                "stdout": completed.stdout or "",
                "stderr": completed.stderr or "",
            })
        except (OSError, subprocess.TimeoutExpired) as error:
            record.update({"status": "ERROR", "error": f"{type(error).__name__}:{error}"})
        return record

    import signal

    try:
        process_group = os.getpgid(pid)
        os.killpg(process_group, signal.SIGTERM)
        record.update({"status": "PASS", "signal": "SIGTERM", "processGroup": process_group})
    except OSError as error:
        record.update({"status": "NOT_FOUND_OR_FAILED", "error": f"{type(error).__name__}:{error}"})
    return record


def read_summary(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {"status": "MISSING", "summaryPath": str(path)}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return {"status": "INVALID_JSON", "summaryPath": str(path), "error": str(error)}
    if not isinstance(payload, dict):
        return {"status": "INVALID_SCHEMA", "summaryPath": str(path)}
    return payload


def is_environment_interruption(row: dict[str, Any]) -> bool:
    """Recognize only a durable device-loss observation that may be resumed once.

    A launch/readiness failure remains terminal by default.  The exception is deliberately
    narrow: the command must report the explicit environment-resolution block and either the
    device snapshot must prove that no screenshot/surface was available or the durable failure
    snapshot must prove a host-scoped LOW_MEMORY exit.  This keeps real CAS/App launch failures
    fail-closed while allowing only the documented environment continuation after an emulator
    restart, without deleting or rewriting the original failed row.
    """
    if not row.get("failureDetected"):
        return False
    command = row.get("commandResult") or {}
    detail = str(command.get("detail") or "")
    device = row.get("device") or {}
    screenshot = device.get("screenshot") or {}
    device_loss = (
        bool(ENVIRONMENT_INTERRUPTION_RE.search(detail))
        and str(command.get("status") or "").upper() == "ERROR"
        and device.get("surfaceNonEmpty") is False
        and bool(screenshot.get("error"))
    )
    if device_loss:
        return True
    return is_host_low_memory_interruption(row)


def host_low_memory_evidence(row: dict[str, Any]) -> list[str]:
    artifacts = Path(str(row.get("artifacts", "")))
    full = artifacts / "first-failure-full"
    if not full.is_dir():
        return []
    paths = [full / "application-exit-info.txt"]
    paths.extend(sorted(full.glob("*.txt")))
    matches: list[str] = []
    for path in dict.fromkeys(paths):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if LOW_MEMORY_RE.search(text) and HOST_PACKAGE_RE.search(text):
            matches.append(str(path.resolve()))
    return matches


def is_host_low_memory_interruption(row: dict[str, Any]) -> bool:
    """Allow a continuation only for an explicit host LOW_MEMORY environment boundary."""
    if not row.get("failureDetected"):
        return False
    command = row.get("commandResult") or {}
    detail = str(command.get("detail") or "")
    return (
        bool(ENVIRONMENT_INTERRUPTION_RE.search(detail))
        and str(command.get("status") or "").upper() == "ERROR"
        and bool(host_low_memory_evidence(row))
    )


def is_performance_timeout_interruption(row: dict[str, Any]) -> bool:
    """Allow only an explicit launch/Guest TimeoutException as a bounded case continuation."""
    return classify_performance_timeout_row(row) is not None


def find_command_record(output: Path, label: str) -> dict[str, Any]:
    for path in sorted((output / "commands").glob("*.json")):
        record = read_summary(path)
        if record.get("label") == label:
            return record
    return {}


def launch_continuation(lane: Path, targets: str, users: str, loops: int) -> dict[str, Any]:
    """Find the first missing coordinate in an interrupted, ordered launch lane."""
    attempt_dirs = sorted(
        (path for path in lane.glob("attempt-*") if path.is_dir()),
        key=lambda path: (int(path.name.removeprefix("attempt-"))
                          if path.name.removeprefix("attempt-").isdigit() else -1,
                          path.name),
    )
    if not attempt_dirs:
        raise PhaseFailure("launch-continuation", "no durable attempt lane is present",
                           {"lane": str(lane.resolve())})
    observed: dict[tuple[str, int, int, str], dict[str, Any]] = {}
    sources: dict[tuple[str, int, int, str], Path] = {}
    duplicates: list[tuple[str, int, int, str]] = []
    recovered_environment_coordinates: list[tuple[str, int, int, str]] = []
    recovered_performance_timeout_coordinates: list[tuple[str, int, int, str]] = []
    latest_child = attempt_dirs[-1]
    for child in attempt_dirs:
        child_has_rows = False
        for path in sorted(child.rglob("case.json")):
            row = read_summary(path)
            if row.get("task") != "C4-R03":
                continue
            child_has_rows = True
            coordinate = (str(row.get("target", "")), int(row.get("user", -1)),
                          int(row.get("iteration", -1)), str(row.get("mode", "")))
            if coordinate in observed:
                previous = observed[coordinate]
                # A later, successful observation may replace exactly one durable device-loss
                # row.  The original row remains in its prior attempt and is still included by
                # the child aggregate's observations; only the terminal coordinate is updated.
                previous_environment = is_environment_interruption(previous)
                previous_timeout = is_performance_timeout_interruption(previous)
                current_environment = is_environment_interruption(row)
                current_timeout = is_performance_timeout_interruption(row)
                if previous_environment and not row.get("failureDetected"):
                    recovered_environment_coordinates.append(coordinate)
                elif previous_timeout and not row.get("failureDetected"):
                    recovered_performance_timeout_coordinates.append(coordinate)
                elif ((previous_environment or previous_timeout)
                      and (current_environment or current_timeout)):
                    # A later bounded environment/timeout observation may still be a failure;
                    # retain it as the latest terminal row so the child wrapper can enforce its
                    # own explicit recovery/retry budget.  This is not a PASS substitution.
                    pass
                else:
                    duplicates.append(coordinate)
            observed[coordinate] = row
            sources[coordinate] = child
        if child_has_rows:
            latest_child = child
    if duplicates:
        raise PhaseFailure("launch-continuation", "duplicate completed coordinates",
                           {"duplicates": duplicates,
                            "attempts": [str(path.resolve()) for path in attempt_dirs]})

    target_names = [value.strip() for value in targets.split(",") if value.strip()]
    user_values = [int(value.strip()) for value in users.split(",") if value.strip()]
    expected = [(target, user, iteration, mode)
                for target in target_names
                for user in user_values
                for iteration in range(1, loops + 1)
                for mode in ("cold", "hot")]
    for coordinate in expected:
        row = observed.get(coordinate)
        if row is None:
            target, user, iteration, mode = coordinate
            return {
                "previousLane": str(latest_child.resolve()),
                "seedLane": str(lane.resolve()),
                "target": target,
                "user": user,
                "iteration": iteration,
                "mode": mode,
                "completedRows": len(observed),
                "expectedRows": len(expected),
                "attempts": [str(path.resolve()) for path in attempt_dirs],
            }
        if row.get("failureDetected"):
            if is_environment_interruption(row):
                target, user, iteration, mode = coordinate
                low_memory = is_host_low_memory_interruption(row)
                return {
                    "previousLane": str(latest_child.resolve()),
                    "seedLane": str(lane.resolve()),
                    "target": target,
                    "user": user,
                    "iteration": iteration,
                    "mode": mode,
                    "completedRows": len(observed) - 1,
                    "expectedRows": len(expected),
                    "attempts": [str(path.resolve()) for path in attempt_dirs],
                    "environmentInterruption": {
                        "coordinate": list(coordinate),
                        "source": str(sources[coordinate].resolve()),
                        "classification": ("HOST_LOW_MEMORY_INTERRUPTION" if low_memory
                                            else "ENVIRONMENT_RESTART_INTERRUPTION"),
                        "originalFailurePreserved": True,
                        "evidence": host_low_memory_evidence(row) if low_memory else [],
                    },
                }
            if is_performance_timeout_interruption(row):
                timeout = classify_performance_timeout_row(row) or {}
                target, user, iteration, mode = coordinate
                return {
                    "previousLane": str(latest_child.resolve()),
                    "seedLane": str(lane.resolve()),
                    "target": target,
                    "user": user,
                    "iteration": iteration,
                    "mode": mode,
                    "completedRows": len(observed) - 1,
                    "expectedRows": len(expected),
                    "attempts": [str(path.resolve()) for path in attempt_dirs],
                    "performanceTimeoutInterruption": {
                        "coordinate": list(coordinate),
                        "source": str(sources[coordinate].resolve()),
                        "classification": "PERFORMANCE_TIMEOUT",
                        "originalFailurePreserved": True,
                        "retryBudget": PERFORMANCE_TIMEOUT_RETRY_BUDGET,
                        "policyDecision": "CONTINUE_UP_TO_5_EXPLICIT_RETRIES",
                        "evidence": timeout.get("evidence", []),
                    },
                }
            raise PhaseFailure("launch-continuation", "existing lane contains a non-terminal failure",
                               {"coordinate": coordinate, "row": row,
                                "source": str(sources[coordinate].resolve())})
    result = {
        "previousLane": str(latest_child.resolve()),
        "seedLane": str(lane.resolve()),
        "completedRows": len(observed),
        "expectedRows": len(expected),
        "attempts": [str(path.resolve()) for path in attempt_dirs],
        "complete": True,
    }
    if recovered_environment_coordinates:
        result["recoveredEnvironmentCoordinates"] = [list(item)
                                                     for item in recovered_environment_coordinates]
    if recovered_performance_timeout_coordinates:
        result["recoveredPerformanceTimeoutCoordinates"] = [
            list(item) for item in recovered_performance_timeout_coordinates
        ]
    return result


def run_command(label: str, command: list[str], output: Path, *,
                summary_path: Path | None = None,
                timeout_seconds: int = DEFAULT_PHASE_TIMEOUT_SECONDS) -> dict[str, Any]:
    """Run one phase once and preserve complete stdout/stderr before classification."""
    command_dir = output / "commands"
    command_dir.mkdir(parents=True, exist_ok=True)
    prefix = f"{len(list(command_dir.glob('*.json'))):03d}-{safe_name(label)}"
    started = now_iso()
    started_mono = time.monotonic()
    timed_out = False
    process_termination: dict[str, Any] | None = None
    popen_kwargs: dict[str, Any] = {
        "cwd": ROOT,
        "text": True,
        "encoding": "utf-8",
        "errors": "replace",
        "stdout": subprocess.PIPE,
        "stderr": subprocess.PIPE,
        "env": {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    }
    if os.name != "nt":
        popen_kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **popen_kwargs)
    try:
        stdout, stderr = process.communicate(timeout=timeout_seconds)
        returncode = process.returncode
    except subprocess.TimeoutExpired as error:
        timed_out = True
        returncode = 124
        process_termination = terminate_process_tree(process.pid)
        try:
            stdout, stderr = process.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, stderr = process.communicate()
            process_termination["fallbackKill"] = True
        stdout = _process_output(stdout) or _process_output(error.stdout)
        stderr = _process_output(stderr) or _process_output(error.stderr)
        stderr += f"\nTIMEOUT after {timeout_seconds}s\n"
    stdout = _process_output(stdout)
    stderr = _process_output(stderr)
    record: dict[str, Any] = {
        "label": label,
        "command": command,
        "startedAt": started,
        "completedAt": now_iso(),
        "elapsedMs": round((time.monotonic() - started_mono) * 1000),
        "returncode": returncode,
        "timedOut": timed_out,
        "timeoutSeconds": timeout_seconds,
        "processPid": process.pid,
        "stdoutPath": str((command_dir / f"{prefix}.stdout.txt").resolve()),
        "stderrPath": str((command_dir / f"{prefix}.stderr.txt").resolve()),
    }
    if process_termination is not None:
        record["processTermination"] = process_termination
    write_text(command_dir / f"{prefix}.stdout.txt", stdout)
    write_text(command_dir / f"{prefix}.stderr.txt", stderr)
    if summary_path is not None:
        record["summaryPath"] = str(summary_path.resolve())
        record["summary"] = read_summary(summary_path)
    write_json(command_dir / f"{prefix}.json", record)
    return record


def require_pass(record: dict[str, Any], phase: str) -> dict[str, Any]:
    summary = record.get("summary") or {}
    status = str(summary.get("status") or "")
    if int(record.get("returncode", 1)) != 0 or status not in {"", "PASS"}:
        detail = f"returncode={record.get('returncode')} summaryStatus={status or 'UNCLASSIFIED'}"
        raise PhaseFailure(phase, detail, record)
    if int(record.get("returncode", 1)) != 0:
        raise PhaseFailure(phase, f"returncode={record.get('returncode')}", record)
    return record


def commit_and_apk_snapshot() -> dict[str, Any]:
    missing = [name for name, path in APK_PATHS.items() if not path.is_file()]
    snapshot: dict[str, Any] = {
        "commit": git_value("rev-parse", "HEAD"),
        "branch": git_value("branch", "--show-current"),
        "tree": git_value("rev-parse", "HEAD^{tree}"),
        "missingApks": missing,
        "apks": {},
    }
    for name, path in APK_PATHS.items():
        if path.is_file():
            snapshot["apks"][name] = {
                "path": str(path.resolve()),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
    try:
        snapshot["apkMetadata"] = apk_metadata()
    except Exception as error:
        snapshot["apkMetadataError"] = str(error)
    return snapshot


def prepare_round(instance_name: str, round_name: str, output: Path) -> dict[str, Any]:
    environment = resolve_rd_environment(instance_name)
    serial = str(environment["adb_serial"])
    install = install_rd_apks(serial)
    clear: dict[str, Any] | None = None
    if round_name == "clean-install-cold":
        result = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        clear = {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        if result.returncode != 0 or result.stdout.strip().lower() != "success":
            raise PhaseFailure("prepare-clean-install", "host data clear failed", clear)
    payload = {
        "task": TASK_ID,
        "round": round_name,
        "environment": environment,
        "install": install,
        "clearHostData": clear,
        "attempt": 1,
        "retryBudget": 0,
        "automaticRetryPerformed": False,
    }
    write_json(output / "environment.json", environment)
    write_json(output / "prepare.json", payload)
    return payload


def run_r04_contracts(instance_name: str, round_output: Path, root_output: Path,
                      phase_timeout_seconds: int) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for mode in ("failure-injection", "recovery"):
        phase_output = round_output / f"r04-{mode}"
        command = [sys.executable, str(TOOLS / "run_c4_r04_rd.py"),
                   "--mode", mode, "--instance-name", instance_name,
                   "--output", str(phase_output)]
        record = run_command(f"{round_output.name}-r04-{mode}", command, root_output,
                             summary_path=phase_output / "runner-summary.json",
                             timeout_seconds=phase_timeout_seconds)
        records.append(require_pass(record, f"{round_output.name}-r04-{mode}"))
    return records


def run_add_gate(instance_name: str, round_output: Path, root_output: Path,
                 reduced_scope: bool, phase_timeout_seconds: int) -> dict[str, Any]:
    phase_output = round_output / "add-gate"
    command = [sys.executable, str(TOOLS / "run_c4_r02_rd.py"),
               "--instance-name", instance_name, "--output", str(phase_output)]
    if reduced_scope:
        command.append("--reduced-r05-scope")
    record = run_command(f"{round_output.name}-c4-r02-add-gate", command, root_output,
                         summary_path=phase_output / "summary.json",
                         timeout_seconds=phase_timeout_seconds)
    return require_pass(record, f"{round_output.name}-c4-r02-add-gate")


def run_launch_matrix(instance_name: str, loops: int, users: str, targets: str,
                      round_output: Path, root_output: Path,
                      continuation: dict[str, Any] | None = None,
                      phase_timeout_seconds: int = DEFAULT_PHASE_TIMEOUT_SECONDS) -> dict[str, Any]:
    phase_output = round_output / "launch-matrix"
    # The wrapper delegates every observation to run_c4_r03_rd.py and only handles the
    # user-approved MuMu LOW_MEMORY restart boundary around that fail-fast child.  A host
    # phase timeout is a separate, bounded session boundary: it may seed the complete durable
    # lane once, but it never changes the per-case readiness SLO or becomes a PASS by itself.
    base_command = [sys.executable, str(TOOLS / "run_c4_r03_low_memory_continuation.py"),
                    "--instance-name", instance_name, "--loops", str(loops),
                    "--users", users, "--targets", targets,
                    "--output", str(phase_output),
                    "--child-timeout-seconds", str(phase_timeout_seconds)]
    command = list(base_command)
    if continuation and not continuation.get("complete"):
        command.extend([
            "--seed-existing-lane", str(continuation.get("seedLane", phase_output)),
            "--" + "resume-target", str(continuation["target"]),
            "--" + "resume-user", str(continuation["user"]),
            "--" + "resume-iteration", str(continuation["iteration"]),
            "--" + "resume-mode", str(continuation["mode"]),
        ])
    if continuation and continuation.get("complete"):
        summary = read_summary(phase_output / "c4-r03-summary.json")
        if summary.get("status") != "PASS":
            raise PhaseFailure("launch-continuation", "existing launch lane is incomplete",
                               {"summary": summary})
        return {"label": f"{round_output.name}-c4-r03-launch-matrix",
                "returncode": 0, "summary": summary,
                "continuedExistingOutput": True}
    records: list[dict[str, Any]] = []
    host_phase_continuations: list[dict[str, Any]] = []
    phase_label = f"{round_output.name}-c4-r03-launch-matrix"
    while True:
        record = run_command(phase_label, command, root_output,
                             summary_path=phase_output / "c4-r03-summary.json",
                             timeout_seconds=phase_timeout_seconds)
        records.append(record)
        if not record.get("timedOut"):
            completed = require_pass(record, phase_label)
            break

        summary_status = str((record.get("summary") or {}).get("status") or "MISSING")
        if summary_status != "MISSING":
            raise PhaseFailure(
                phase_label,
                "host phase timed out with a non-missing child summary; fail-closed",
                record,
            )
        if host_phase_continuations:
            raise PhaseFailure(
                phase_label,
                "host phase continuation budget exhausted",
                {"record": record, "hostPhaseContinuations": host_phase_continuations},
            )

        progress = launch_continuation(phase_output, targets, users, loops)
        if progress.get("complete"):
            raise PhaseFailure(
                phase_label,
                "host phase timed out although the durable lane is complete but summary is missing",
                {"record": record, "progress": progress},
            )
        if int(progress.get("completedRows", 0)) <= 0:
            raise PhaseFailure(
                phase_label,
                "host phase timed out without durable case rows for continuation",
                {"record": record, "progress": progress},
            )
        continuation_event = {
            "classification": "HOST_PHASE_BOUNDARY_INTERRUPTION",
            "decision": "CONTINUE_ONCE_FROM_FULL_DURABLE_LANE",
            "retryable": True,
            "automaticRetryPerformed": False,
            "retryBudget": 0,
            "continuationBudget": 1,
            "sourceCommand": record,
            "progress": progress,
            "originalFailurePreserved": True,
        }
        host_phase_continuations.append(continuation_event)
        command = list(base_command)
        command.extend([
            "--seed-existing-lane", str(phase_output),
            "--" + "resume-target", str(progress["target"]),
            "--" + "resume-user", str(progress["user"]),
            "--" + "resume-iteration", str(progress["iteration"]),
            "--" + "resume-mode", str(progress["mode"]),
        ])
        phase_label = f"{round_output.name}-c4-r03-launch-matrix-host-continuation-001"

    result = {
        "label": completed.get("label", phase_label),
        "returncode": completed.get("returncode", 1),
        "summary": completed.get("summary") or {},
        "commands": records,
        "hostPhaseContinuations": host_phase_continuations,
    }
    if host_phase_continuations:
        result["summary"] = {
            **result["summary"],
            "hostPhaseContinuation": {
                "budget": 1,
                "used": len(host_phase_continuations),
                "events": host_phase_continuations,
                "originalFailurePreserved": True,
            },
        }
    return result


def run_regressions(instance_name: str, output: Path,
                    phase_timeout_seconds: int) -> list[dict[str, Any]]:
    """Run the required C1/C2/C4/SX gates only after both formal rounds pass."""
    commands = [
        ("c1-activity", [sys.executable, str(TOOLS / "run_c1_t01_rd.py"),
                          "--instance", instance_name, "--loops", "50",
                          "--receipt", str(output / "c1-t01-rd-summary.json")],
         output / "c1-t01-rd-summary.json"),
        ("c2-window-audio", [sys.executable, str(TOOLS / "run_c2_t05_rd.py"),
                              "--instance", instance_name, "--loops", "10",
                              "--verification-dir", str(output / "c2-window-audio")],
         output / "c2-window-audio" / "c2-t05-rd-summary.json"),
        ("c2-device-audio", [sys.executable, str(TOOLS / "run_c2_t06_rd.py"),
                               "--instance", instance_name, "--loops", "20",
                               "--clone-loops", "10", "--verification-dir",
                               str(output / "c2-device-audio")],
         output / "c2-device-audio" / "c2-t06-rd-summary.json"),
        ("c4-cas-only", [sys.executable, str(TOOLS / "run_c4_t04_rd.py"),
                          "--instance", instance_name, "--verification-dir",
                          str(output / "c4-cas-only")],
         output / "c4-cas-only" / "c4-t04-rd-summary.json"),
        ("sx-f1-f5-business", [sys.executable, str(TOOLS / "run_c4_t05_rd.py"),
                                "--instance", instance_name, "--verification-dir",
                                str(output / "sx-f1-f5-business")],
         output / "sx-f1-f5-business" / "c4-t05-rd-summary.json"),
    ]
    records: list[dict[str, Any]] = []
    for label, command, summary_path in commands:
        record = run_command(label, command, output, summary_path=summary_path,
                             timeout_seconds=phase_timeout_seconds)
        records.append(require_pass(record, label))
    return records


def capture_pressure_resources(serial: str, output: Path, tag: str) -> dict[str, Any]:
    resources: dict[str, Any] = {}
    probes = {
        "meminfo": ["shell", "dumpsys", "meminfo", HOST_PACKAGE],
        "processes": ["shell", "ps", "-A"],
        "windows": ["shell", "dumpsys", "window", "windows"],
        "transactions": ["shell", "run-as", HOST_PACKAGE, "cat",
                          "files/package-lifecycle-transactions.json"],
        "staging": ["shell", "run-as", HOST_PACKAGE, "find", "files", "-name", ".install-*"],
    }
    for name, args in probes.items():
        result = run_adb(serial, args, check=False)
        resources[name] = {"returncode": result.returncode, "stdout": result.stdout,
                           "stderr": result.stderr}
        write_text(output / f"{tag}-{name}.txt", result.stdout + ("\n" + result.stderr if result.stderr else ""))
    return resources


def classify_pressure_low_memory(snapshot_dir: Path) -> dict[str, Any] | None:
    evidence_path = snapshot_dir / "application-exit-info.txt"
    if not evidence_path.is_file():
        return None
    try:
        evidence = evidence_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    if not LOW_MEMORY_RE.search(evidence) or not HOST_PACKAGE_RE.search(evidence):
        return None
    return {
        "classification": "LOW_MEMORY",
        "reason": "explicit host-scoped ApplicationExitInfo LOW_MEMORY",
        "evidencePath": str(evidence_path.resolve()),
        "policy": "NON_BLOCKING_RESTART_AND_CONTINUE",
    }


def run_pressure_lane(instance_name: str, environment: dict[str, Any], user: int, minutes: int,
                      minimum_cycles: int, output: Path) -> dict[str, Any]:
    serial = str(environment["adb_serial"])
    lane = output / f"user-{user}"
    lane.mkdir(parents=True, exist_ok=True)
    write_json(lane / "environment.json", environment)
    initial_resources = capture_pressure_resources(serial, lane, "initial")
    initial_logcat_reset = run_adb(serial, ["shell", "logcat", "-c"], check=False)
    if initial_logcat_reset.returncode != 0:
        raise PhaseFailure(f"pressure-user-{user}", "initial logcat boundary failed", {
            "returncode": initial_logcat_reset.returncode,
            "stdout": initial_logcat_reset.stdout,
            "stderr": initial_logcat_reset.stderr,
        })
    started = time.monotonic()
    rows: list[dict[str, Any]] = []
    successful_cycles = 0
    low_memory_events: list[dict[str, Any]] = []
    while time.monotonic() - started < minutes * 60 or successful_cycles < minimum_cycles:
        request_id = f"c4-r05-pressure-u{user}-{uuid.uuid4().hex}"
        result = debug_command(
            serial,
            ["--es", "command", "launch", "--es", "package", GUEST_PACKAGE,
             "--ei", "user", str(user), "--es", "requestId", request_id,
             "--ez", "trustNativeGuest", "true"],
            deadline_sec=90,
            force_stop_host=False,
        )
        operation = result.get("result", {}).get("operation", {}) if isinstance(result, dict) else {}
        snapshot_dir = lane / f"cycle-{len(rows) + 1:03d}"
        snapshot = capture_snapshot(serial, snapshot_dir, GUEST_PACKAGE)
        row = {
            "user": user,
            "cycle": len(rows) + 1,
            "requestId": request_id,
            "attempt": 1,
            "retryBudget": 0,
            "automaticRetryPerformed": False,
            "result": result,
            "operation": operation,
            "snapshot": snapshot,
        }
        rows.append(row)
        logcat_reset = run_adb(serial, ["shell", "logcat", "-c"], check=False)
        row["postEvidenceLogcatReset"] = {
            "returncode": logcat_reset.returncode,
            "stdout": logcat_reset.stdout,
            "stderr": logcat_reset.stderr,
        }
        if logcat_reset.returncode != 0:
            row["classification"] = "LOGCAT_SCOPE_RESET_FAILED"
            write_json(lane / "cycles.json", rows)
            failure = {"row": row,
                       "resources": capture_pressure_resources(serial, lane, "first-failure")}
            write_json(lane / "first-failure.json", failure)
            raise PhaseFailure(f"pressure-user-{user}",
                               "post-evidence logcat boundary failed", failure)
        good = (
            str(result.get("status", "")).upper() == "PASS"
            and bool(operation.get("firstFrameDrawn"))
            and not snapshot.get("guestWindowState", {}).get("windows_empty", True)
            and not snapshot.get("guestWindowState", {}).get("reported_drawn_false", True)
            and not snapshot.get("guestWindowState", {}).get("has_visible_false", True)
            and bool(snapshot.get("surfaceNonEmpty"))
            and bool(snapshot.get("screenshot", {}).get("nonBlack"))
        )
        if good:
            row["classification"] = "NONE"
            successful_cycles += 1
            write_json(lane / "cycles.json", rows)
            continue

        low_memory = classify_pressure_low_memory(snapshot_dir)
        if low_memory is not None:
            event_number = len(low_memory_events) + 1
            recovery_dir = lane / "low-memory-recovery" / f"event-{event_number:03d}"
            low_memory.update({
                "user": user,
                "cycle": row["cycle"],
                "requestId": request_id,
                "resources": capture_pressure_resources(serial, recovery_dir, "before-restart"),
            })
            low_memory["restart"] = restart_mumu(instance_name, lane, recovery_dir)
            row.update({"classification": "LOW_MEMORY", "nonBlocking": True,
                        "lowMemoryRecovery": low_memory})
            low_memory_events.append(low_memory)
            write_json(lane / "cycles.json", rows)
            if low_memory["restart"].get("status") != "PASS":
                failure = {"row": row, "resources": low_memory.get("resources"),
                           "lowMemoryRecovery": low_memory}
                write_json(lane / "first-failure.json", failure)
                raise PhaseFailure(f"pressure-user-{user}",
                                   "LOW_MEMORY restart failed", failure)
            environment = low_memory["restart"].get("environmentAfter") or environment
            serial = str(environment["adb_serial"])
            write_json(lane / f"environment-after-low-memory-{event_number:03d}.json", environment)
            continue

        row["classification"] = "DYNAMIC_READINESS_FAILURE"
        write_json(lane / "cycles.json", rows)
        failure = {"row": row,
                   "resources": capture_pressure_resources(serial, lane, "first-failure")}
        if not good:
            write_json(lane / "first-failure.json", failure)
            raise PhaseFailure(f"pressure-user-{user}", "pressure cycle failed dynamic readiness", failure)
    resources = capture_pressure_resources(serial, lane, "final")
    result = {
        "user": user,
        "status": "PASS",
        "durationSeconds": round(time.monotonic() - started, 3),
        "cycles": successful_cycles,
        "attemptedCycles": len(rows),
        "minimumCycles": minimum_cycles,
        "lowMemoryRecoveries": len(low_memory_events),
        "nonBlockingLowMemory": low_memory_events,
        "initialResources": initial_resources,
        "resources": resources,
        "attemptPolicy": {"attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False},
    }
    write_json(lane / "summary.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--loops", type=int, default=25)
    parser.add_argument("--users", default="0,1")
    parser.add_argument("--targets", default="fixture,dingtalk,quark,hongguo,fanqie")
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--pressure-minutes", type=int, default=15)
    parser.add_argument("--pressure-minimum-cycles", type=int, default=50)
    parser.add_argument("--phase-timeout-seconds", type=int,
                        default=DEFAULT_PHASE_TIMEOUT_SECONDS,
                        help="maximum duration for each R05 phase and nested launch child")
    parser.add_argument("--acceptance-scope", choices=("c4-stage-reduced", "overall-50"),
                        default="c4-stage-reduced",
                        help="C4 stage gate uses two rounds of 25 loops; overall post-C7 acceptance uses two rounds of 50")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--continue-existing-output", action="store_true",
                        help="continue an interrupted clean-install-cold run from its durable evidence")
    args = parser.parse_args()
    expected_loops = 25 if args.acceptance_scope == "c4-stage-reduced" else 50
    expected_rounds = 2
    if args.rounds != expected_rounds or args.loops != expected_loops or args.pressure_minutes != 15 \
            or args.pressure_minimum_cycles < 50:
        raise SystemExit(f"{args.acceptance_scope} requires exactly {expected_rounds} round(s), {expected_loops} launch loops, "
                         "15-minute lanes, and >=50 cycles")
    if args.phase_timeout_seconds <= 0:
        raise SystemExit("--phase-timeout-seconds must be positive")
    users = [int(value.strip()) for value in args.users.split(",") if value.strip()]
    if users != [0, 1]:
        raise SystemExit("C4-R05 requires users=0,1")
    reduced_scope = args.acceptance_scope == "c4-stage-reduced"
    dirty_at_start = git_value("status", "--porcelain", "--untracked-files=all")
    if dirty_at_start:
        raise SystemExit("C4-R05 formal acceptance requires a clean worktree before evidence capture")
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    continuation: dict[str, Any] | None = None
    existing_launch_continuations: dict[int, dict[str, Any]] = {}
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "IN_PROGRESS",
        "startedAt": now_iso(),
        "instanceName": args.instance_name,
        "attemptPolicy": {"attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False},
        "orchestration": {
            "phaseTimeoutSeconds": args.phase_timeout_seconds,
            "phaseTimeoutHours": args.phase_timeout_seconds / 3600,
            "nestedLaunchChildTimeoutSeconds": args.phase_timeout_seconds,
        },
        "acceptance": {
            "rounds": ["clean-install-cold", "retained-hot-recovery"],
            "addGates": ({"fixture": 25, "dingtalk": 5, "quark": 5, "hongguo": 5, "fanqie": 5}
                         if reduced_scope
                         else {"fixture": 50, "dingtalk": 10, "quark": 10, "hongguo": 10, "fanqie": 10}),
            "launchLoops": args.loops,
            "scope": args.acceptance_scope,
            "users": users,
            "pressureMinutesPerUser": args.pressure_minutes,
            "pressureMinimumCyclesPerUser": args.pressure_minimum_cycles,
        },
        "rounds": [],
        "regressions": [],
        "pressure": [],
    }
    try:
        report["git"] = {"commit": git_value("rev-parse", "HEAD"),
                          "branch": git_value("branch", "--show-current"),
                          "remote": git_value("config", "--get", "branch." +
                                              git_value("branch", "--show-current") + ".remote"),
                          "worktreeStatusAtStart": dirty_at_start}
        environment = resolve_rd_environment(args.instance_name)
        report["environmentAtStart"] = environment
        write_json(output / "environment-at-start.json", environment)
        report["buildAndApk"] = commit_and_apk_snapshot()
        if report["buildAndApk"]["missingApks"]:
            raise PhaseFailure("build-metadata", "required APKs missing", report["buildAndApk"])
        if args.continue_existing_output:
            if not reduced_scope:
                raise PhaseFailure("continuation", "existing-output continuation is only defined for the reduced C4 scope")
            prior_build = find_command_record(output, "build-clean-commit")
            if int(prior_build.get("returncode", 1)) != 0:
                raise PhaseFailure("continuation", "the prior build evidence is missing or failed",
                                   {"record": prior_build})
            continuation = launch_continuation(
                output / "round-1-clean-install-cold" / "launch-matrix",
                args.targets, args.users, args.loops)
            existing_launch_continuations[1] = continuation
            round_two_lane = output / "round-2-retained-hot-recovery" / "launch-matrix"
            if round_two_lane.is_dir():
                existing_launch_continuations[2] = launch_continuation(
                    round_two_lane, args.targets, args.users, args.loops)
            report["continuedFromExistingOutput"] = str(output.resolve())
            report["continuation"] = existing_launch_continuations
            report["priorBuild"] = prior_build
        else:
            build = run_command(
                "build-clean-commit",
                [str(ROOT / "gradlew.bat"), ":app:assembleDebug", ":fixture-basic:assembleDebug",
                 ":sandbox-companion32:assembleDebug", ":fixture-compat32:assembleDebug", "--no-daemon"],
                output,
                timeout_seconds=3_600,
            )
            require_pass(build, "build-clean-commit")
        report["buildAndApk"] = commit_and_apk_snapshot()
        round_names = ("clean-install-cold", "retained-hot-recovery")
        for index, round_name in enumerate(round_names, start=1):
            round_output = output / f"round-{index}-{round_name}"
            round_report: dict[str, Any] = {"round": index, "name": round_name,
                                            "status": "IN_PROGRESS", "startedAt": now_iso()}
            report["rounds"].append(round_report)
            existing_round_continuation = existing_launch_continuations.get(index)
            if existing_round_continuation is not None:
                prepare_path = round_output / "prepare.json"
                if not prepare_path.is_file():
                    raise PhaseFailure("continuation", "existing prepare evidence is missing",
                                       {"path": str(prepare_path.resolve())})
                round_report["prepare"] = read_summary(prepare_path)
                r04_paths = (
                    round_output / "r04-failure-injection" / "runner-summary.json",
                    round_output / "r04-recovery" / "runner-summary.json",
                )
                round_report["r04"] = [read_summary(path) for path in r04_paths]
                if any(summary.get("status") != "PASS" for summary in round_report["r04"]):
                    raise PhaseFailure("continuation", "existing R04 evidence is missing or not PASS",
                                       {"r04": round_report["r04"]})
                add_gate = read_summary(round_output / "add-gate" / "summary.json")
                if add_gate.get("status") != "PASS":
                    raise PhaseFailure("continuation", "existing add-gate evidence is missing or not PASS",
                                       {"addGate": add_gate})
                round_report["addGate"] = add_gate
            else:
                round_report["prepare"] = prepare_round(args.instance_name, round_name, round_output)
                round_report["r04"] = [record["summary"] for record in
                                        run_r04_contracts(
                                            args.instance_name, round_output, output,
                                            args.phase_timeout_seconds)]
                round_report["addGate"] = run_add_gate(
                    args.instance_name, round_output, output,
                    reduced_scope=reduced_scope,
                    phase_timeout_seconds=args.phase_timeout_seconds)["summary"]
            launch_result = run_launch_matrix(
                args.instance_name, args.loops, args.users, args.targets, round_output, output,
                continuation=existing_round_continuation,
                phase_timeout_seconds=args.phase_timeout_seconds)
            round_report["launchMatrix"] = launch_result["summary"]
            if launch_result.get("hostPhaseContinuations"):
                round_report["launchMatrixHostPhaseContinuations"] = launch_result[
                    "hostPhaseContinuations"
                ]
            round_report.update({"status": "PASS", "completedAt": now_iso()})
        report["regressions"] = [record.get("summary") or {"label": record.get("label"),
                                                              "returncode": record.get("returncode")}
                                 for record in run_regressions(
                                     args.instance_name, output / "regressions",
                                     args.phase_timeout_seconds)]
        for user in users:
            pressure_environment = resolve_rd_environment(args.instance_name)
            report["pressure"].append(run_pressure_lane(
                args.instance_name, pressure_environment, user, args.pressure_minutes,
                args.pressure_minimum_cycles, output / "pressure"))
        report["status"] = "PASS"
        report["completedAt"] = now_iso()
        return_code = 0
    except PhaseFailure as error:
        report["status"] = "FAIL"
        report["outcome"] = "BLOCKED"
        report["firstFailure"] = {"phase": error.phase, "detail": error.detail,
                                   "evidence": error.evidence, "stoppedAt": now_iso(),
                                   "automaticRetryPerformed": False}
        report["completedAt"] = now_iso()
        write_json(output / "first-failure.json", report["firstFailure"])
        return_code = 1
    except Exception as error:
        report["status"] = "FAIL"
        report["outcome"] = "BLOCKED"
        report["firstFailure"] = {"phase": "orchestrator", "detail": str(error),
                                   "stoppedAt": now_iso(), "automaticRetryPerformed": False}
        report["completedAt"] = now_iso()
        write_json(output / "first-failure.json", report["firstFailure"])
        return_code = 1
    finally:
        report.setdefault("completedAt", now_iso())
        write_json(output / "summary.json", report)
        write_json(output / "c4-r05-summary.json", report)
        write_json(output / "artifact-index.json", {
            "schemaVersion": 1,
            "root": str(output.resolve()),
            "artifacts": artifact_index(output),
        })
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
