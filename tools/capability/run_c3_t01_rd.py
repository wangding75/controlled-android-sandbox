#!/usr/bin/env python3
"""Run the C3-T01 native compatibility/bypass corpus on MuMu RD测试.

The runner resolves the MuMu endpoint by instance name, executes the static
gates, builds/installs the existing campaign APKs, and validates direct 64-bit,
direct 32-bit, and in-sandbox results. A raw-path discovery is evidence, not a
failure and never becomes a VA Pro or hostile-isolation claim.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME  # noqa: E402
from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_native_adversarial_rd import (  # noqa: E402
    ADV_ACTIVITY,
    CASE_IDS,
    FIXTURE32,
    FIXTURE64,
    build_so_metadata,
    collect_device_files,
    invoke_in_sandbox,
    start_direct_fixture,
)
from run_rd_campaign import (  # noqa: E402
    CampaignBlocked,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    read_debug_command_result,
    resolve_rd_environment,
    run_adb,
    sha256_file,
)

TASK_ID = "C3-T01"
STATIC_GATES = (
    "scripts/check-c3-t01-native-corpus.py",
    "scripts/check-native-boundary-matrix.py",
    "scripts/check-native-files-loader.py",
    "scripts/check-native-file-hooks.py",
    "scripts/check-native-abi-companion.py",
    "scripts/check-native-hostile-profile.py",
    "scripts/check-native-enforcement-poc.py",
)
VERIFICATION = ROOT / "verification/catch-up/C3-T01"
KNOWN_ISSUES = [
    "KI-T57-009",
    "KI-R03-023",
    "KI-R03-NATIVE-001",
    "KI-R03-NATIVE-002",
    "KI-R03-NATIVE-003",
    "KI-R03-NATIVE-004",
    "KI-R03-NATIVE-005",
    "KI-R03-NATIVE-006",
    "KI-R03-NATIVE-007",
    "KI-R03-NATIVE-008",
    "KI-R03-NATIVE-009",
    "KI-R03-NATIVE-ENF-001",
]
ALLOWED_SANDBOX_STATUSES = {"PASS_COMPAT", "BYPASS_CONFIRMED", "BLOCKED_BY_POLICY"}


def run_static_gates() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for relative in STATIC_GATES:
        command = [sys.executable, str(ROOT / relative)]
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            env={**dict(__import__("os").environ), "PYTHONDONTWRITEBYTECODE": "1"},
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


def parse_case_payload(
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
        if case_id in by_id:
            errors.append(f"{label}: duplicate case {case_id}")
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
        if expected_context == "DIRECT_FIXTURE" and status != "PASS_COMPAT":
            errors.append(f"{label}/{case_id}: expected PASS_COMPAT, got {status}")
        if expected_context == "IN_SANDBOX" and status not in ALLOWED_SANDBOX_STATUSES:
            errors.append(f"{label}/{case_id}: illegal sandbox status {status}")
        if str(record.get("context") or "") != expected_context:
            errors.append(f"{label}/{case_id}: context mismatch")
    case011 = by_id.get("NATIVE-ADV-011")
    if case011:
        detail = str(case011.get("detail") or "")
        required = ("negative_host_path=DENIED",)
        for token in required:
            if token not in detail:
                errors.append(f"{label}/NATIVE-ADV-011: missing {token}")
        if "classification=" not in detail:
            errors.append(f"{label}/NATIVE-ADV-011: missing classification marker")
        if expected_context == "IN_SANDBOX":
            if case011.get("status") != "BYPASS_CONFIRMED":
                errors.append(
                    f"{label}/NATIVE-ADV-011: expected BYPASS_CONFIRMED discovery, "
                    f"got {case011.get('status')}"
                )
            if "classification=UNCONTROLLED_ENTRYPOINTS_RECORDED" not in detail:
                errors.append(f"{label}/NATIVE-ADV-011: uncontrolled classification missing")
        elif case011.get("status") != "PASS_COMPAT":
            errors.append(f"{label}/NATIVE-ADV-011: direct compatibility status missing")
    return {
        "label": label,
        "abi": str(payload.get("abi") or ""),
        "context": str(payload.get("context") or ""),
        "schema": str(payload.get("schema") or ""),
        "statuses": statuses,
        "cases": records,
    }


def pull_sandbox_payload(serial: str, output: Path) -> dict[str, Any] | None:
    last_text = ""
    candidate_files = (
        (
            HOST_PACKAGE,
            f"files/instances/u0/{GUEST_PACKAGE}/data/files/native-adv-results.json",
        ),
        (GUEST_PACKAGE, "files/native-adv-results.json"),
    )
    for _ in range(45):
        for package, relative_file in candidate_files:
            pulled = run_adb(
                serial,
                ["shell", "run-as", package, "cat", relative_file],
                check=False,
            )
            last_text = (pulled.stdout or "").strip()
            if not last_text.startswith("{"):
                continue
            try:
                payload = json.loads(last_text)
            except json.JSONDecodeError:
                payload = None
            if (
                isinstance(payload, dict)
                and isinstance(payload.get("cases"), list)
                and payload.get("context") == "IN_SANDBOX"
            ):
                write_json(output / "sandbox-native-result.json", payload)
                return payload
        time.sleep(1)
    (output / "sandbox-native-result.txt").write_text(last_text, encoding="utf-8")
    return None


def required_native_abis(libraries: list[dict[str, Any]], soname: str) -> set[str]:
    return {
        Path(str(item.get("path") or "")).name and str(item.get("abi") or "")
        for item in libraries
        if Path(str(item.get("path") or "")).name == soname
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default=RD_INSTANCE_NAME)
    args = parser.parse_args()

    output = artifacts_dir("catch-up-c3-t01")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    environment: dict[str, Any] = {}
    direct64: dict[str, Any] = {}
    direct32: dict[str, Any] = {}
    sandbox: dict[str, Any] = {}
    sandbox_payload: dict[str, Any] | None = None
    static_rows: list[dict[str, Any]] = []
    build = apk_metadata()
    libraries = build_so_metadata()
    build["native_libraries"] = libraries
    write_json(output / "build.json", build)

    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed_gates = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed_gates:
            raise RuntimeError(f"static gates failed: {failed_gates}")

        missing_apks = [row["name"] for row in build.get("apks", []) if not row.get("exists")]
        required_abis = {"arm64-v8a", "x86_64", "armeabi-v7a", "x86"}
        fixture_abis = required_native_abis(libraries, "libcontrolled_sandbox_fixture.so")
        payload_abis = required_native_abis(libraries, "libfixture_adv_payload.so")
        if missing_apks:
            raise RuntimeError(f"missing APKs: {missing_apks}")
        if fixture_abis != required_abis or payload_abis != required_abis:
            raise RuntimeError(
                f"four-ABI native library set incomplete: fixture={sorted(fixture_abis)} "
                f"payload={sorted(payload_abis)}"
            )

        environment = resolve_rd_environment(args.instance_name)
        serial = str(environment["adb_serial"])
        collect_device_files(serial, output, environment)
        write_json(output / "install.json", install_rd_apks(serial))
        for package in (FIXTURE64, FIXTURE32):
            run_adb(serial, ["shell", "run-as", package, "rm", "-f", "files/native-adv-results.json"], check=False)
        run_adb(serial, ["logcat", "-c"], check=False)
        direct64 = start_direct_fixture(serial, FIXTURE64)
        direct32 = start_direct_fixture(serial, FIXTURE32)
        write_json(output / "direct64.json", direct64)
        write_json(output / "direct32.json", direct32)
        parse_case_payload(
            "direct64", direct64.get("result"), "DIRECT_FIXTURE", errors
        )
        parse_case_payload(
            "direct32", direct32.get("result"), "DIRECT_FIXTURE", errors
        )

        sandbox_file = f"files/instances/u0/{GUEST_PACKAGE}/data/files/native-adv-results.json"
        run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "rm", "-f", sandbox_file], check=False)
        sandbox = invoke_in_sandbox(serial)
        write_json(output / "sandbox-command.json", sandbox)
        sandbox_payload = pull_sandbox_payload(serial, output)
        parse_case_payload("sandbox", sandbox_payload, "IN_SANDBOX", errors)
        logcat = run_adb(
            serial,
            ["logcat", "-d", "-s", "CS_NATIVE_ADV:I", "CS_FIXTURE:I"],
            check=False,
        )
        (output / "logcat.txt").write_text(logcat.stdout or "", encoding="utf-8")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:  # repaired/re-run by the task loop when deterministic
        errors.append(str(exc))

    direct64_summary = parse_case_payload(
        "direct64-final", direct64.get("result") if direct64 else None,
        "DIRECT_FIXTURE", [],
    )
    direct32_summary = parse_case_payload(
        "direct32-final", direct32.get("result") if direct32 else None,
        "DIRECT_FIXTURE", [],
    )
    sandbox_summary = parse_case_payload(
        "sandbox-final", sandbox_payload, "IN_SANDBOX", [],
    )
    serial = str(environment.get("adb_serial") or "")
    build_ok = bool(build.get("apks")) and all(row.get("exists") for row in build["apks"])
    static_ok = bool(static_rows) and all(row["returncode"] == 0 for row in static_rows)
    targeted_ok = not errors and bool(direct64_summary["statuses"])
    rd_ok = targeted_ok and bool(sandbox_summary["statuses"])
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
        "adb_serial": serial,
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
            {
                "id": "C3-T01-ACCEPTANCE",
                "classification": "FAIL",
                "summary": error,
            }
            for error in errors
        ],
        "known_issues": KNOWN_ISSUES,
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "notes": (
            "C3-T01 native corpus. NATIVE-ADV-011 records uncontrolled native "
            "entrypoints; BYPASS_CONFIRMED is a valid discovery. PLT/GOT evidence "
            "does not prove ISOLATED_HOSTILE enforcement or VA Pro equivalence."
        ),
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
    }
    schema_errors = validate_evidence(evidence)
    if schema_errors:
        errors.extend(schema_errors)
        evidence["failures"].extend(
            {
                "id": "C3-T01-EVIDENCE-SCHEMA",
                "classification": "FAIL",
                "summary": error,
            }
            for error in schema_errors
        )
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "build.json"),
        str(output / "static-gates.json"),
        str(output / "direct64.json"),
        str(output / "direct32.json"),
        str(output / "sandbox-command.json"),
        str(output / "logcat.txt"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c3-t01-rd-summary.json"),
        str(VERIFICATION / "c3-t01-local-verification.json"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c3-t01-rd-summary.json", evidence)
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
        "sandbox_command_status": sandbox.get("status") if sandbox else "",
        "errors": errors,
        "non_claims": [
            "raw syscall/SVC is not mediated by PLT/GOT",
            "four ABI build presence is not translated-ABI runtime proof",
            "RD API32 is not API33+ / OEM / commercial / VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c3-t01-local-verification.json", local)
    report = [
        "# C3-T01 Native Corpus RD Evidence",
        "",
        f"- status: `{evidence['rd_result']}`",
        f"- instance: `{evidence['instance_name']}`",
        f"- resolved serial: `{evidence['adb_serial']}`",
        f"- device: `{evidence['device_name']}` API `{evidence['api_level']}`",
        f"- boot_id: `{evidence['boot_id']}`",
        f"- source commit before implementation commit: `{evidence['commit']}`",
        "- VA Pro equivalent: `NOT_PROVEN`",
        "",
        "## Case statuses",
        "",
    ]
    for label, summary in (
        ("direct64", direct64_summary),
        ("direct32", direct32_summary),
        ("sandbox", sandbox_summary),
    ):
        report.append(f"### {label} ({summary.get('abi', '')})")
        for case_id in CASE_IDS:
            report.append(f"- `{case_id}`: `{summary.get('statuses', {}).get(case_id, 'MISSING')}`")
        report.append("")
    report.extend(
        [
            "## Boundary statement",
            "",
            "`NATIVE-ADV-011` classifies uncontrolled entrypoints and preserves the raw-path "
            "discovery. A successful PLT/libc path is compatibility evidence only; it is "
            "not hostile isolation enforcement.",
            "",
        ]
    )
    report_path.write_text("\n".join(report), encoding="utf-8")
    print(json.dumps({"status": evidence["rd_result"], "output": str(output),
                      "verification": str(VERIFICATION / "c3-t01-rd-summary.json"),
                      "serial": serial, "errors": errors}, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
