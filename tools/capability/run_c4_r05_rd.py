#!/usr/bin/env python3
"""C4-R05 formal RD revalidation and closure orchestrator.

The orchestrator owns the C4-R05 evidence boundary.  It builds one commit, resolves the
MuMu instance by name, runs the configured stage or overall acceptance rounds, and stops at the
first non-PASS phase.  The launch-matrix child delegates its individual observations to
``run_c4_r03_rd.py`` and has one explicit environment exception: a host-scoped LOW_MEMORY
exit is recorded, the emulator is restarted, and the matrix continues from a separately
recorded coordinate.  Child campaigns keep their own request-scoped raw evidence; this runner
records the command, summary, commit and phase decision without turning a later observation into
an automatic retry.
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
from run_c4_r03_low_memory_continuation import restart_mumu  # noqa: E402
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
APK_PATHS = {
    "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
    "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
    "companion32": ROOT / "sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk",
    "fixture32": ROOT / "fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk",
}
LOW_MEMORY_RE = re.compile(r"\bLOW_MEMORY\b", re.IGNORECASE)
HOST_PACKAGE_RE = re.compile(re.escape(HOST_PACKAGE), re.IGNORECASE)


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
                "target": target,
                "user": user,
                "iteration": iteration,
                "mode": mode,
                "completedRows": len(observed),
                "expectedRows": len(expected),
                "attempts": [str(path.resolve()) for path in attempt_dirs],
            }
        if row.get("failureDetected"):
            raise PhaseFailure("launch-continuation", "existing lane contains a non-terminal failure",
                               {"coordinate": coordinate, "row": row,
                                "source": str(sources[coordinate].resolve())})
    return {
        "previousLane": str(latest_child.resolve()),
        "completedRows": len(observed),
        "expectedRows": len(expected),
        "attempts": [str(path.resolve()) for path in attempt_dirs],
        "complete": True,
    }


def run_command(label: str, command: list[str], output: Path, *,
                summary_path: Path | None = None, timeout_seconds: int = 14_400) -> dict[str, Any]:
    """Run one phase once and preserve complete stdout/stderr before classification."""
    command_dir = output / "commands"
    command_dir.mkdir(parents=True, exist_ok=True)
    prefix = f"{len(list(command_dir.glob('*.json'))):03d}-{safe_name(label)}"
    started = now_iso()
    started_mono = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        returncode = completed.returncode
        stdout = completed.stdout or ""
        stderr = completed.stderr or ""
    except subprocess.TimeoutExpired as error:
        returncode = 124
        stdout = str(error.stdout or "")
        stderr = str(error.stderr or "") + f"\nTIMEOUT after {timeout_seconds}s\n"
    record: dict[str, Any] = {
        "label": label,
        "command": command,
        "startedAt": started,
        "completedAt": now_iso(),
        "elapsedMs": round((time.monotonic() - started_mono) * 1000),
        "returncode": returncode,
        "stdoutPath": str((command_dir / f"{prefix}.stdout.txt").resolve()),
        "stderrPath": str((command_dir / f"{prefix}.stderr.txt").resolve()),
    }
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


def run_r04_contracts(instance_name: str, round_output: Path, root_output: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for mode in ("failure-injection", "recovery"):
        phase_output = round_output / f"r04-{mode}"
        command = [sys.executable, str(TOOLS / "run_c4_r04_rd.py"),
                   "--mode", mode, "--instance-name", instance_name,
                   "--output", str(phase_output)]
        record = run_command(f"{round_output.name}-r04-{mode}", command, root_output,
                             summary_path=phase_output / "runner-summary.json")
        records.append(require_pass(record, f"{round_output.name}-r04-{mode}"))
    return records


def run_add_gate(instance_name: str, round_output: Path, root_output: Path,
                 reduced_scope: bool) -> dict[str, Any]:
    phase_output = round_output / "add-gate"
    command = [sys.executable, str(TOOLS / "run_c4_r02_rd.py"),
               "--instance-name", instance_name, "--output", str(phase_output)]
    if reduced_scope:
        command.append("--reduced-r05-scope")
    record = run_command(f"{round_output.name}-c4-r02-add-gate", command, root_output,
                         summary_path=phase_output / "summary.json", timeout_seconds=14_400)
    return require_pass(record, f"{round_output.name}-c4-r02-add-gate")


def run_launch_matrix(instance_name: str, loops: int, users: str, targets: str,
                      round_output: Path, root_output: Path,
                      continuation: dict[str, Any] | None = None) -> dict[str, Any]:
    phase_output = round_output / "launch-matrix"
    # The wrapper delegates every observation to run_c4_r03_rd.py and only handles the
    # user-approved MuMu LOW_MEMORY restart boundary around that fail-fast child.
    command = [sys.executable, str(TOOLS / "run_c4_r03_low_memory_continuation.py"),
               "--instance-name", instance_name, "--loops", str(loops),
               "--users", users, "--targets", targets,
               "--output", str(phase_output)]
    if continuation and not continuation.get("complete"):
        command.extend([
            "--seed-existing-child", str(continuation["previousLane"]),
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
    record = run_command(f"{round_output.name}-c4-r03-launch-matrix", command, root_output,
                         summary_path=phase_output / "c4-r03-summary.json", timeout_seconds=14_400)
    return require_pass(record, f"{round_output.name}-c4-r03-launch-matrix")


def run_regressions(instance_name: str, output: Path) -> list[dict[str, Any]]:
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
                             timeout_seconds=14_400)
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
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "IN_PROGRESS",
        "startedAt": now_iso(),
        "instanceName": args.instance_name,
        "attemptPolicy": {"attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False},
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
            report["continuedFromExistingOutput"] = str(output.resolve())
            report["continuation"] = continuation
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
            if continuation is not None and index == 1:
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
                                        run_r04_contracts(args.instance_name, round_output, output)]
                round_report["addGate"] = run_add_gate(
                    args.instance_name, round_output, output, reduced_scope=reduced_scope)["summary"]
            round_report["launchMatrix"] = run_launch_matrix(
                args.instance_name, args.loops, args.users, args.targets, round_output, output,
                continuation=continuation if index == 1 else None)["summary"]
            round_report.update({"status": "PASS", "completedAt": now_iso()})
        report["regressions"] = [record.get("summary") or {"label": record.get("label"),
                                                              "returncode": record.get("returncode")}
                                 for record in run_regressions(args.instance_name, output / "regressions")]
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
