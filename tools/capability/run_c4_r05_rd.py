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
                      round_output: Path, root_output: Path) -> dict[str, Any]:
    phase_output = round_output / "launch-matrix"
    # The wrapper delegates every observation to run_c4_r03_rd.py and only handles the
    # user-approved MuMu LOW_MEMORY restart boundary around that fail-fast child.
    command = [sys.executable, str(TOOLS / "run_c4_r03_low_memory_continuation.py"),
               "--instance-name", instance_name, "--loops", str(loops),
               "--users", users, "--targets", targets,
               "--output", str(phase_output)]
    record = run_command(f"{round_output.name}-c4-r03-launch-matrix", command, root_output,
                         summary_path=phase_output / "c4-r03-summary.json", timeout_seconds=14_400)
    return require_pass(record, f"{round_output.name}-c4-r03-launch-matrix")


def run_regressions(instance_name: str, output: Path) -> list[dict[str, Any]]:
    """Run the required C1/C2/C4/SX gates only after both formal rounds pass."""
    commands = [
        ("c1-activity", [sys.executable, str(TOOLS / "run_c1_t01_rd.py"),
                         "--instance", instance_name, "--loops", "50",
                         "--receipt", str(output / "c1-t01-rd-summary.json")]),
        ("c2-window-audio", [sys.executable, str(TOOLS / "run_c2_t05_rd.py"),
                             "--instance", instance_name, "--loops", "10"]),
        ("c2-device-audio", [sys.executable, str(TOOLS / "run_c2_t06_rd.py"),
                              "--instance", instance_name, "--loops", "20",
                              "--clone-loops", "10"]),
        ("c4-cas-only", [sys.executable, str(TOOLS / "run_c4_t04_rd.py"),
                         "--instance", instance_name]),
        ("sx-f1-f5-business", [sys.executable, str(TOOLS / "run_c4_t05_rd.py"),
                               "--instance", instance_name]),
    ]
    records: list[dict[str, Any]] = []
    for label, command in commands:
        record = run_command(label, command, output, timeout_seconds=14_400)
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


def run_pressure_lane(environment: dict[str, Any], user: int, minutes: int,
                      minimum_cycles: int, output: Path) -> dict[str, Any]:
    serial = str(environment["adb_serial"])
    lane = output / f"user-{user}"
    lane.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    rows: list[dict[str, Any]] = []
    while time.monotonic() - started < minutes * 60 or len(rows) < minimum_cycles:
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
        write_json(lane / "cycles.json", rows)
        good = (
            str(result.get("status", "")).upper() == "PASS"
            and bool(operation.get("firstFrameDrawn"))
            and not snapshot.get("guestWindowState", {}).get("windows_empty", True)
            and not snapshot.get("guestWindowState", {}).get("reported_drawn_false", True)
            and not snapshot.get("guestWindowState", {}).get("has_visible_false", True)
            and bool(snapshot.get("surfaceNonEmpty"))
            and bool(snapshot.get("screenshot", {}).get("nonBlack"))
        )
        if not good:
            failure = {"row": row, "resources": capture_pressure_resources(serial, lane, "first-failure")}
            write_json(lane / "first-failure.json", failure)
            raise PhaseFailure(f"pressure-user-{user}", "pressure cycle failed dynamic readiness", failure)
    resources = capture_pressure_resources(serial, lane, "final")
    result = {
        "user": user,
        "status": "PASS",
        "durationSeconds": round(time.monotonic() - started, 3),
        "cycles": len(rows),
        "minimumCycles": minimum_cycles,
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
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--pressure-minutes", type=int, default=15)
    parser.add_argument("--pressure-minimum-cycles", type=int, default=50)
    parser.add_argument("--acceptance-scope", choices=("c4-stage-reduced", "overall-50"),
                        default="c4-stage-reduced",
                        help="C4 stage gate uses 25 loops; overall post-C7 acceptance uses 50")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    expected_loops = 25 if args.acceptance_scope == "c4-stage-reduced" else 50
    expected_rounds = 1 if args.acceptance_scope == "c4-stage-reduced" else 2
    if args.rounds != expected_rounds or args.loops != expected_loops or args.pressure_minutes != 15 \
            or args.pressure_minimum_cycles < 50:
        raise SystemExit(f"{args.acceptance_scope} requires exactly {expected_rounds} round(s), {expected_loops} launch loops, "
                         "15-minute lanes, and >=50 cycles")
    users = [int(value.strip()) for value in args.users.split(",") if value.strip()]
    if users != [0, 1]:
        raise SystemExit("C4-R05 requires users=0,1")
    reduced_scope = args.acceptance_scope == "c4-stage-reduced"
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "IN_PROGRESS",
        "startedAt": now_iso(),
        "instanceName": args.instance_name,
        "attemptPolicy": {"attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False},
        "acceptance": {
            "rounds": (["clean-install-cold"] if reduced_scope
                       else ["clean-install-cold", "retained-hot-recovery"]),
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
                                             git_value("branch", "--show-current") + ".remote")}
        environment = resolve_rd_environment(args.instance_name)
        report["environmentAtStart"] = environment
        write_json(output / "environment-at-start.json", environment)
        report["buildAndApk"] = commit_and_apk_snapshot()
        if report["buildAndApk"]["missingApks"]:
            raise PhaseFailure("build-metadata", "required APKs missing", report["buildAndApk"])
        build = run_command(
            "build-clean-commit",
            [str(ROOT / "gradlew.bat"), ":app:assembleDebug", ":fixture-basic:assembleDebug",
             ":sandbox-companion32:assembleDebug", ":fixture-compat32:assembleDebug", "--no-daemon"],
            output,
            timeout_seconds=3_600,
        )
        require_pass(build, "build-clean-commit")
        report["buildAndApk"] = commit_and_apk_snapshot()
        round_names = ("clean-install-cold",) if reduced_scope \
            else ("clean-install-cold", "retained-hot-recovery")
        for index, round_name in enumerate(round_names, start=1):
            round_output = output / f"round-{index}-{round_name}"
            round_report: dict[str, Any] = {"round": index, "name": round_name,
                                            "status": "IN_PROGRESS", "startedAt": now_iso()}
            report["rounds"].append(round_report)
            round_report["prepare"] = prepare_round(args.instance_name, round_name, round_output)
            round_report["r04"] = [record["summary"] for record in
                                    run_r04_contracts(args.instance_name, round_output, output)]
            round_report["addGate"] = run_add_gate(
                args.instance_name, round_output, output, reduced_scope=reduced_scope)["summary"]
            round_report["launchMatrix"] = run_launch_matrix(
                args.instance_name, args.loops, args.users, args.targets, round_output, output)["summary"]
            round_report.update({"status": "PASS", "completedAt": now_iso()})
        report["regressions"] = [record.get("summary") or {"label": record.get("label"),
                                                              "returncode": record.get("returncode")}
                                 for record in run_regressions(args.instance_name, output / "regressions")]
        pressure_environment = resolve_rd_environment(args.instance_name)
        for user in users:
            report["pressure"].append(run_pressure_lane(
                pressure_environment, user, args.pressure_minutes,
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
