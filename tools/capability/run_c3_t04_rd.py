#!/usr/bin/env python3
"""Run the C3-T04 hostile native isolation campaign on MuMu RD测试.

Device selection goes through resolve_rd_environment -> mumu_instance.
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

from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_native_adversarial_rd import build_so_metadata  # noqa: E402
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    CampaignBlocked,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C3-T04"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
STATIC_GATES = (
    "scripts/check-c3-t04-hostile-isolation.py",
    "scripts/check-native-hostile-profile.py",
    "scripts/check-native-enforcement-poc.py",
    "scripts/check-native-boundary-matrix.py",
)
VERIFICATION = ROOT / "verification/catch-up/C3-T04"
HOST_RESULT = "files/c3-t04-hostile-results.json"
REQUIRED_CASES = {
    "C3-T04-ISO-001": {"PASS_ISOLATED"},
    "C3-T04-FS-CORE-001": {"DENIED_BY_KERNEL_POLICY"},
    "C3-T04-FS-GUEST-001": {"DENIED_BY_KERNEL_POLICY"},
    "C3-T04-FS-UNGRANTED-001": {"DENIED"},
    "C3-T04-FD-INHERIT-001": {"PASS_NO_LEAK"},
    "C3-T04-PTRACE-001": {"DENIED_BY_SECCOMP", "DENIED_BY_KERNEL_POLICY"},
    "C3-T04-EXEC-001": {"DENIED_BY_SECCOMP", "DENIED_BY_KERNEL_POLICY"},
    "C3-T04-CAP-GRANT-001": {"PASS_CAPABILITY"},
    "C3-T04-CAP-SCOPE-001": {"DENIED_BY_BROKER"},
    "C3-T04-CAP-REV-001": {"DENIED_BY_BROKER"},
    "C3-T04-CAP-EXPIRY-001": {"DENIED_BY_BROKER"},
    "C3-T04-CAP-REPLAY-001": {"DENIED_BY_BROKER"},
    "C3-T04-DEATH-001": {"PASS_REVOKED"},
    "C3-T04-NET-BROKER-001": {"PASS_CAPABILITY"},
}
OBSERVED_CASES = {
    "C3-T04-BINDER-001": {"DENIED_BY_KERNEL_POLICY", "KERNEL_LIMIT_EXPOSED"},
    "C3-T04-CLONE-001": {"DENIED_BY_KERNEL_POLICY", "KERNEL_LIMIT_EXPOSED_SAME_UID"},
    "C3-T04-SOCKET-001": {"DENIED_BY_SECCOMP", "KERNEL_LIMIT_EXPOSED", "DIRECT_DENIED"},
}


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


def pull_json(serial: str, relative: str, output: Path, label: str) -> dict[str, Any] | None:
    deadline = time.time() + 20
    last = ""
    while time.time() < deadline:
        pulled = run_adb(
            serial, ["shell", "run-as", HOST_PACKAGE, "cat", relative], check=False
        )
        last = (pulled.stdout or "").strip()
        if last.startswith("{"):
            try:
                payload = json.loads(last)
            except json.JSONDecodeError:
                payload = None
            if isinstance(payload, dict):
                write_json(output / f"{label}.json", payload)
                return payload
        time.sleep(0.4)
    (output / f"{label}-last.txt").write_text(last, encoding="utf-8")
    return None


def parse_cases(payload: dict[str, Any] | None, errors: list[str]) -> dict[str, str]:
    if not isinstance(payload, dict):
        errors.append("missing C3-T04 JSON payload")
        return {}
    cases = payload.get("cases")
    if not isinstance(cases, list):
        errors.append("cases is not a list")
        return {}
    statuses: dict[str, str] = {}
    for record in cases:
        if not isinstance(record, dict):
            continue
        case_id = str(record.get("id") or "")
        if case_id.startswith("C3-T04-"):
            statuses[case_id] = str(record.get("status") or "")
    for case_id, allowed in REQUIRED_CASES.items():
        status = statuses.get(case_id, "")
        if case_id not in statuses:
            errors.append(f"missing required case {case_id}")
        elif status not in allowed:
            errors.append(f"{case_id} status={status!r} allowed={sorted(allowed)}")
    for case_id, allowed in OBSERVED_CASES.items():
        status = statuses.get(case_id, "")
        if case_id not in statuses:
            errors.append(f"missing observed case {case_id}")
        elif status not in allowed:
            errors.append(f"{case_id} status={status!r} observed={sorted(allowed)}")
    if not payload.get("isolatedUidDistinct"):
        errors.append("isolated UID was not distinct from Host")
    if not payload.get("c3t04Pass"):
        errors.append("campaign c3t04Pass was false")
    death = payload.get("death") or {}
    if not death.get("processGone"):
        errors.append("isolated process still present after unbind")
    if not death.get("replayDenied"):
        errors.append("capability replay was not denied after death")
    return statuses


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c3-t04")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    command: dict[str, Any] = {}
    payload: dict[str, Any] | None = None
    statuses: dict[str, str] = {}
    build = apk_metadata()
    build["native_libraries"] = build_so_metadata()
    write_json(output / "build.json", build)
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        serial = str(environment["adb_serial"])
        if serial in FORBIDDEN_SERIALS and serial != str(
            (environment.get("mumu") or {}).get("configuredSerial") or serial
        ):
            raise CampaignBlocked(
                "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
                "resolved serial matched a historical constant without a live MuMu mapping",
            )
        write_json(output / "environment.json", environment)
        write_json(output / "install.json", install_rd_apks(serial))
        run_adb(serial, ["logcat", "-c"], check=False)
        command = debug_command(
            serial,
            ["--es", "command", "c3-t04-hostile"],
            deadline_sec=120,
        )
        write_json(output / "debug-command.json", command)
        if command.get("status") != "PASS":
            errors.append(f"debug command status={command.get('status')}")
        payload = pull_json(serial, HOST_RESULT, output, "c3-t04")
        statuses = parse_cases(payload, errors)
        logcat = run_adb(
            serial,
            ["logcat", "-d", "-s", "CS_NATIVE_HOSTILE:I", "CS_NATIVE_ENF:I", "CS_COMMAND:I"],
            check=False,
        )
        (output / "logcat.txt").write_text(logcat.stdout or "", encoding="utf-8")
        ps = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,UID,NAME"], check=False)
        (output / "ps.txt").write_text(ps.stdout or "", encoding="utf-8")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    build_ok = bool(build.get("apks")) and all(row.get("exists") for row in build["apks"])
    static_ok = bool(static_rows) and all(row["returncode"] == 0 for row in static_rows)
    rd_ok = not errors and bool(statuses)
    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "native_loader_jni_io",
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
        "instance_name": str(environment.get("instance_name") or args.instance),
        "build_result": "PASS" if build_ok else "FAIL",
        "static_result": "PASS" if static_ok else "FAIL",
        "targeted_result": "PASS" if rd_ok else "FAIL",
        "rd_result": "PASS" if rd_ok else "FAIL",
        "regression_result": "PASS" if rd_ok else "UNVERIFIED",
        "failures": [
            {"id": "C3-T04-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": [
            "KI-R03-044 C3-T04 hostile attack matrix evidence",
            "KI-R03-NATIVE-001 raw SVC remains outside PLT; isolated UID/seccomp is the hostile path",
            "KI-R03-NATIVE-006 /dev/binder observation is a residual kernel limit",
            "KI-R03-NATIVE-008 user-notify is not implemented; classic BPF deny-only only",
            "KI-R03-NATIVE-ENF-001 isolated UID without the hostile filter is not a network boundary",
        ],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "ISOLATED_HOSTILE evidence is isolated UID + Broker ledger + deny-only seccomp. "
            "Binder/clone/socket residuals are classified, not isolation PASS. "
            "RD API32 is not API33+ / ARM / 16KB / OEM / VA Pro equivalence. "
            "case_statuses=" + json.dumps(statuses, sort_keys=True) + " residual="
            + json.dumps((payload or {}).get("residual") if isinstance(payload, dict) else {},
                         sort_keys=True)
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C3-T04-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence["rd_result"] = "PASS" if not errors else "FAIL"
    evidence["targeted_result"] = evidence["rd_result"]
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "build.json"),
        str(output / "static-gates.json"),
        str(output / "debug-command.json"),
        str(output / "c3-t04.json"),
        str(output / "logcat.txt"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c3-t04-rd-summary.json"),
        str(VERIFICATION / "c3-t04-local-verification.json"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c3-t04-rd-summary.json", evidence)
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
        "debug_command": command,
        "case_statuses": statuses,
        "residual": (payload or {}).get("residual") if isinstance(payload, dict) else {},
        "errors": errors,
        "non_claims": [
            "PLT/GOT is not a hostile native security boundary",
            "classic BPF deny-only is not seccomp user-notify",
            "binder/clone residuals are kernel limits, not isolation PASS",
            "RD API32 is not API33+ / ARM / 16KB / OEM / VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c3-t04-local-verification.json", local)
    lines = [
        "# C3-T04 Hostile Native Isolation RD Evidence",
        "",
        f"- status: {evidence['rd_result']}",
        f"- instance: {evidence['instance_name']}",
        f"- resolved serial: {evidence['adb_serial']}",
        f"- device: {evidence['device_name']} API {evidence['api_level']}",
        f"- boot_id: {evidence['boot_id']}",
        "- VA Pro equivalent: NOT_PROVEN",
        "",
        "## Case statuses",
        "",
    ]
    for case_id in list(REQUIRED_CASES) + list(OBSERVED_CASES):
        lines.append(f"- `{case_id}`: `{statuses.get(case_id, 'MISSING')}`")
    lines.extend(["", "## Residuals", "", json.dumps(local.get("residual") or {}, indent=2)])
    if errors:
        lines.extend(["", "## Errors", ""])
        lines.extend(f"- {error}" for error in errors)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{'PASS' if not errors else 'FAIL'} {TASK_ID} rd_result={evidence['rd_result']}")
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
