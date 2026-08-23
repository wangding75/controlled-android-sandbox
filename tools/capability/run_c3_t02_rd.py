#!/usr/bin/env python3
"""Run the C3-T02 file/proc/network/FD corpus on MuMu RD测试."""

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

from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_native_adversarial_rd import build_so_metadata, collect_device_files  # noqa: E402
from run_rd_campaign import (  # noqa: E402
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

TASK_ID = "C3-T02"
FIXTURE64 = "com.warden.controlledsandbox.fixture"
FIXTURE32 = "com.warden.controlledsandbox.fixture32"
ACTIVITY = "com.warden.controlledsandbox.fixture.C3T02FileProcNetworkFdActivity"
CASE_IDS = (
    "C3-T02-FS-001",
    "C3-T02-PROC-001",
    "C3-T02-NET-001",
    "C3-T02-FD-001",
    "C3-T02-FD-002",
    "C3-T02-FD-003",
    "C3-T02-RAW-001",
)
STATIC_GATES = (
    "scripts/check-c3-t02-file-proc-network-fd.py",
    "scripts/check-native-files-loader.py",
    "scripts/check-native-file-hooks.py",
    "scripts/check-native-network-audio.py",
    "scripts/check-m5-t19-1-b-native-network-correctness.py",
)
VERIFICATION = ROOT / "verification/catch-up/C3-T02"
GUEST_RESULT = f"files/instances/u0/{GUEST_PACKAGE}/data/files/c3t02-results.json"


def run_static_gates() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for relative in STATIC_GATES:
        completed = subprocess.run(
            [sys.executable, str(ROOT / relative)],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        rows.append(
            {
                "gate": relative,
                "returncode": completed.returncode,
                "stdout": completed.stdout,
                "stderr": completed.stderr,
            }
        )
    return rows


def parse_payload(
    label: str,
    payload: dict[str, Any] | None,
    expected_context: str,
    errors: list[str],
) -> dict[str, Any]:
    if not isinstance(payload, dict):
        errors.append(f"{label}: missing JSON payload")
        return {"label": label, "statuses": {}, "cases": []}
    records = payload.get("cases")
    if not isinstance(records, list):
        errors.append(f"{label}: cases is not a list")
        return {"label": label, "statuses": {}, "cases": []}
    by_id: dict[str, dict[str, Any]] = {}
    for record in records:
        if not isinstance(record, dict):
            errors.append(f"{label}: non-object case record")
            continue
        case_id = str(record.get("id") or "")
        if case_id not in CASE_IDS:
            errors.append(f"{label}: unexpected case {case_id!r}")
            continue
        by_id[case_id] = record
    missing = [case_id for case_id in CASE_IDS if case_id not in by_id]
    if missing:
        errors.append(f"{label}: missing cases {missing}")
    statuses = {case_id: str(by_id[case_id].get("status") or "") for case_id in by_id}
    for case_id, record in by_id.items():
        status = statuses[case_id]
        detail = str(record.get("detail") or "")
        if status == "ERROR":
            errors.append(f"{label}/{case_id}: ERROR {detail[:240]}")
        if str(record.get("context") or "") != expected_context:
            errors.append(f"{label}/{case_id}: context mismatch")
        if case_id == "C3-T02-RAW-001":
            if expected_context == "IN_SANDBOX":
                if status not in {"BYPASS_CONFIRMED", "BLOCKED_BY_POLICY"}:
                    errors.append(f"{label}/{case_id}: raw path silently became {status}")
                if "classification=" not in detail:
                    errors.append(f"{label}/{case_id}: raw classification missing")
            elif status != "PASS_COMPAT":
                errors.append(f"{label}/{case_id}: direct raw baseline is {status}")
    if expected_context == "IN_SANDBOX":
        for case_id in CASE_IDS[:-1]:
            allowed = {"PASS_COMPAT"}
            if case_id == "C3-T02-FD-003":
                allowed.add("BLOCKED_BY_POLICY")
            if statuses.get(case_id) not in allowed:
                errors.append(
                    f"{label}/{case_id}: expected {sorted(allowed)}, "
                    f"got {statuses.get(case_id)}"
                )
    else:
        for case_id in CASE_IDS[:-1]:
            if statuses.get(case_id) not in {"PASS_COMPAT", "BLOCKED_BY_POLICY"}:
                errors.append(
                    f"{label}/{case_id}: direct compatibility status "
                    f"{statuses.get(case_id)}"
                )
    proc = by_id.get("C3-T02-PROC-001")
    if proc is not None:
        detail = str(proc.get("detail") or "")
        for token in ("fd_snapshot=", "proc_net=", "unknown_fd_readlink="):
            if token not in detail:
                errors.append(f"{label}/C3-T02-PROC-001: missing {token}")
        if expected_context == "IN_SANDBOX" and "host_leak=0" not in detail:
            errors.append(f"{label}/C3-T02-PROC-001: host leak scan failed")
    for case_id in ("C3-T02-FD-001", "C3-T02-FD-002", "C3-T02-FD-003"):
        record = by_id.get(case_id)
        if record is not None and "close" not in str(record.get("detail") or ""):
            errors.append(f"{label}/{case_id}: close lifecycle marker missing")
    return {
        "label": label,
        "schema": str(payload.get("schema") or ""),
        "abi": str(payload.get("abi") or ""),
        "context": str(payload.get("context") or ""),
        "statuses": statuses,
        "cases": records,
    }


def pull_payload(
    serial: str,
    package: str,
    relative_file: str,
    output: Path,
    context: str,
    label: str,
    deadline_sec: int = 60,
) -> dict[str, Any] | None:
    deadline = time.time() + deadline_sec
    last_text = ""
    while time.time() < deadline:
        pulled = run_adb(
            serial,
            ["shell", "run-as", package, "cat", relative_file],
            check=False,
        )
        last_text = (pulled.stdout or "").strip()
        if last_text.startswith("{"):
            try:
                payload = json.loads(last_text)
            except json.JSONDecodeError:
                payload = None
            if (
                isinstance(payload, dict)
                and isinstance(payload.get("cases"), list)
                and payload.get("context") == context
            ):
                write_json(output / f"{label}.json", payload)
                return payload
        time.sleep(1)
    (output / f"{label}.txt").write_text(last_text, encoding="utf-8")
    return None


def start_direct(serial: str, package: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    run_adb(
        serial,
        ["shell", "run-as", package, "rm", "-f", "files/c3t02-results.json"],
        check=False,
    )
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{package}/{ACTIVITY}",
            "--es",
            "cas.native.context",
            "DIRECT_FIXTURE",
        ],
        check=False,
    )
    return {
        "package": package,
        "start_returncode": started.returncode,
        "start_stdout": started.stdout,
        "start_stderr": started.stderr,
    }


def start_sandbox(serial: str) -> dict[str, Any]:
    command = "c3-t02-file-proc-network-fd"
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
    run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"],
        check=False,
    )
    stop_started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-f",
            "0x10008000",
            "-n",
            DEBUG_ACTIVITY,
            "--es",
            "command",
            "stop",
            "--es",
            "package",
            GUEST_PACKAGE,
            "--ei",
            "user",
            "0",
            "--ez",
            "trustNativeGuest",
            "true",
        ],
        check=False,
    )
    try:
        stop_result = read_debug_command_result(
            serial, deadline_sec=60, expected_command="stop", expected_package=GUEST_PACKAGE
        )
    except CampaignBlocked as exc:
        stop_result = {"status": "ERROR", "detail": str(exc)}
    run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"],
        check=False,
    )
    run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "rm", "-f", GUEST_RESULT], check=False)
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-f",
            "0x10008000",
            "-n",
            DEBUG_ACTIVITY,
            "--es",
            "command",
            command,
            "--es",
            "package",
            GUEST_PACKAGE,
            "--ei",
            "user",
            "0",
            "--ez",
            "trustNativeGuest",
            "true",
        ],
        check=False,
    )
    try:
        result = read_debug_command_result(
            serial,
            deadline_sec=60,
            expected_command=command,
            expected_package=GUEST_PACKAGE,
        )
        status = str(result.get("status") or "").upper()
        return {
            "command": command,
            "status": status,
            "result": result,
            "returncode": 0 if status == "PASS" else 1,
            "stop_result": stop_result,
            "stop_start_returncode": stop_started.returncode,
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }
    except CampaignBlocked as exc:
        return {
            "command": command,
            "status": "ERROR",
            "detail": str(exc),
            "returncode": 1,
            "stop_result": stop_result,
            "stop_start_returncode": stop_started.returncode,
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }


def clear_and_death(serial: str, output: Path) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
    host_after = run_adb(serial, ["shell", "pidof", HOST_PACKAGE], check=False)
    guest_after = run_adb(serial, ["shell", "pidof", GUEST_PACKAGE], check=False)
    command = "clear"
    run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"],
        check=False,
    )
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-f",
            "0x10008000",
            "-n",
            DEBUG_ACTIVITY,
            "--es",
            "command",
            command,
            "--es",
            "package",
            GUEST_PACKAGE,
            "--ei",
            "user",
            "0",
            "--ez",
            "trustNativeGuest",
            "true",
        ],
        check=False,
    )
    try:
        clear_result = read_debug_command_result(
            serial, deadline_sec=60, expected_command=command, expected_package=GUEST_PACKAGE
        )
    except CampaignBlocked as exc:
        clear_result = {"status": "ERROR", "detail": str(exc)}
    residual = run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "test", "-e", GUEST_RESULT],
        check=False,
    )
    evidence = {
        "force_stop": {
            "host_pid_after": (host_after.stdout or "").strip(),
            "guest_pid_after": (guest_after.stdout or "").strip(),
        },
        "clear_start_returncode": started.returncode,
        "clear_result": clear_result,
        "guest_result_after_clear_returncode": residual.returncode,
        "guest_result_removed_after_clear": residual.returncode != 0,
    }
    write_json(output / "clear-death.json", evidence)
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c3-t02")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    direct_results: dict[str, Any] = {}
    sandbox_command: dict[str, Any] = {}
    sandbox_payload: dict[str, Any] | None = None
    lifecycle: dict[str, Any] = {}
    build = apk_metadata()
    build["native_libraries"] = build_so_metadata()
    write_json(output / "build.json", build)
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance_name)
        serial = str(environment["adb_serial"])
        collect_device_files(serial, output, environment)
        write_json(output / "install.json", install_rd_apks(serial))
        for package in (FIXTURE64, FIXTURE32):
            direct_results[package] = start_direct(serial, package)
            payload = pull_payload(
                serial,
                package,
                "files/c3t02-results.json",
                output,
                "DIRECT_FIXTURE",
                "direct64" if package == FIXTURE64 else "direct32",
            )
            direct_results[package]["result"] = payload
            parse_payload(
                "direct64" if package == FIXTURE64 else "direct32",
                payload,
                "DIRECT_FIXTURE",
                errors,
            )
        sandbox_command = start_sandbox(serial)
        write_json(output / "sandbox-command.json", sandbox_command)
        if sandbox_command.get("status") != "PASS":
            errors.append(f"sandbox command status={sandbox_command.get('status')}")
        sandbox_payload = pull_payload(
            serial,
            HOST_PACKAGE,
            GUEST_RESULT,
            output,
            "IN_SANDBOX",
            "sandbox",
        )
        parse_payload("sandbox", sandbox_payload, "IN_SANDBOX", errors)
        lifecycle = clear_and_death(serial, output)
        if str(lifecycle.get("clear_result", {}).get("status") or "").upper() != "PASS":
            errors.append("clear lifecycle command did not PASS")
        if not lifecycle.get("guest_result_removed_after_clear"):
            errors.append("guest result remained after clear")
        logcat = run_adb(
            serial,
            ["logcat", "-d", "-s", "CS_C3_T02:I", "CS_NATIVE:I", "CS_FIXTURE:I"],
            check=False,
        )
        (output / "logcat.txt").write_text(logcat.stdout or "", encoding="utf-8")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    direct64_summary = parse_payload(
        "direct64-final",
        direct_results.get(FIXTURE64, {}).get("result"),
        "DIRECT_FIXTURE",
        [],
    )
    direct32_summary = parse_payload(
        "direct32-final",
        direct_results.get(FIXTURE32, {}).get("result"),
        "DIRECT_FIXTURE",
        [],
    )
    sandbox_summary = parse_payload(
        "sandbox-final", sandbox_payload, "IN_SANDBOX", []
    )
    build_ok = bool(build.get("apks")) and all(row.get("exists") for row in build["apks"])
    static_ok = bool(static_rows) and all(row["returncode"] == 0 for row in static_rows)
    targeted_ok = not errors and bool(direct64_summary["statuses"]) and bool(
        direct32_summary["statuses"]
    )
    rd_ok = targeted_ok and bool(sandbox_summary["statuses"])
    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "native_file_proc_network_fd_lifecycle",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
        "device_name": str(environment.get("device_name") or ""),
        "adb_serial": str(environment.get("adb_serial") or ""),
        "api_level": environment.get("api_level"),
        "abi": str(environment.get("abi") or ""),
        "boot_id": str(environment.get("boot_id") or ""),
        "android_id": str(environment.get("android_id") or ""),
        "instance_name": str(environment.get("instance_name") or args.instance_name),
        "build_result": "PASS" if build_ok else "FAIL",
        "static_result": "PASS" if static_ok else "FAIL",
        "targeted_result": "PASS" if targeted_ok else "FAIL",
        "rd_result": "PASS" if rd_ok else "FAIL",
        "regression_result": "PASS" if rd_ok else "UNVERIFIED",
        "failures": [
            {"id": "C3-T02-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": [
            "KI-R03-NATIVE-001 raw syscall/SVC remains outside PLT mediation",
            "KI-R03-NATIVE-003 unknown proc leaves remain fail-closed, not virtualized",
            "KI-R03-NATIVE-009 Provider/Binder FD producers and kernel inheritance are not complete proof",
            "KI-R03-NATIVE-ENF-001 network hostile isolation needs kernel/supervisor enforcement",
        ],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "raw direct syscall result is explicitly exposed and is not treated as PASS isolation; "
            "clear/death scan proves this fixture's residual data/process state, not kernel-wide FD enumeration; "
            "RD API32 is not API33+ / OEM commercial / VA Pro equivalence"
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C3-T02-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "build.json"),
        str(output / "static-gates.json"),
        str(output / "direct64.json"),
        str(output / "direct32.json"),
        str(output / "sandbox-command.json"),
        str(output / "sandbox.json"),
        str(output / "clear-death.json"),
        str(output / "logcat.txt"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c3-t02-rd-summary.json"),
        str(VERIFICATION / "c3-t02-local-verification.json"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c3-t02-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "PASS" if not errors else "FAIL",
        "source_commit_before_implementation_commit": identity.get("commit"),
        "source_worktree_status_before_implementation_commit": identity.get("status"),
        "started_at": evidence["timestamp"],
        "finished_at": now_iso(),
        "static_gates": static_rows,
        "build": build,
        "environment": environment,
        "direct64": direct64_summary,
        "direct32": direct32_summary,
        "sandbox": sandbox_summary,
        "sandbox_command": sandbox_command,
        "clear_death": lifecycle,
        "errors": errors,
        "non_claims": [
            "raw direct syscall result is explicitly exposed and is not treated as PASS isolation",
            "clear/death scan proves this fixture's residual data/process state, not kernel-wide FD enumeration",
            "RD API32 is not API33+ / OEM commercial / VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c3-t02-local-verification.json", local)
    report_lines = [
        "# C3-T02 文件、procfs、网络与 FD 生命周期 RD Evidence",
        "",
        f"- status: {evidence['rd_result']}",
        f"- instance: {evidence['instance_name']}",
        f"- resolved serial: {evidence['adb_serial']}",
        f"- device: {evidence['device_name']} API {evidence['api_level']}",
        f"- boot_id: {evidence['boot_id']}",
        f"- source commit before implementation commit: {evidence['commit']}",
        "- VA Pro equivalent: NOT_PROVEN",
        "",
        "## Case statuses",
        "",
    ]
    for label, summary in (
        ("direct64", direct64_summary),
        ("direct32", direct32_summary),
        ("sandbox", sandbox_summary),
    ):
        report_lines.append(f"### {label} ({summary.get('abi', '')})")
        for case_id in CASE_IDS:
            report_lines.append(
                f"- {case_id}: {summary.get('statuses', {}).get(case_id, 'MISSING')}"
            )
        report_lines.append("")
    report_lines.extend(
        [
            "## Boundary and lifecycle",
            "",
            "The authoritative FD ledger owns intercepted Guest/Host/Inherited state. "
            "Unknown proc-fd access is fail-closed; raw instruction results are explicitly "
            "classified as a direct-syscall boundary. clear/death evidence is fixture-scoped.",
            "",
        ]
    )
    report_path.write_text("\\n".join(report_lines), encoding="utf-8")
    print(
        json.dumps(
            {
                "status": evidence["rd_result"],
                "output": str(output),
                "verification": str(VERIFICATION / "c3-t02-rd-summary.json"),
                "serial": evidence["adb_serial"],
                "errors": errors,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
