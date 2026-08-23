#!/usr/bin/env python3
"""Probe RD kernel seccomp/user-notify availability for the C3-T05 decision."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
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
from run_rd_campaign import CampaignBlocked, resolve_rd_environment, run_adb  # noqa: E402

TASK_ID = "C3-T05"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C3-T05"
STATIC_GATES = (
    "scripts/check-c3-t05-seccomp-decision.py",
    "scripts/check-native-hostile-profile.py",
    "scripts/check-c3-t04-hostile-isolation.py",
)


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


def shell(serial: str, command: str) -> dict[str, Any]:
    result = run_adb(serial, ["shell", command], check=False)
    return {
        "command": command,
        "returncode": result.returncode,
        "stdout": (result.stdout or "").strip(),
        "stderr": (result.stderr or "").strip(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c3-t05")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    probes: dict[str, Any] = {}
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        serial = str(environment["adb_serial"])
        write_json(output / "environment.json", environment)
        probes = {
            "uname": shell(serial, "uname -a"),
            "kernel_release": shell(serial, "uname -r"),
            "proc_version": shell(serial, "cat /proc/version"),
            "sdk": shell(serial, "getprop ro.build.version.sdk"),
            "release": shell(serial, "getprop ro.build.version.release"),
            "getenforce": shell(serial, "getenforce"),
            "seccomp_status": shell(serial, "grep -E 'Seccomp|NoNewPrivs' /proc/self/status"),
            "config": shell(
                serial,
                "sh -c 'if [ -f /proc/config.gz ]; then zcat /proc/config.gz | grep SECCOMP; else echo NO_CONFIG_GZ; fi'",
            ),
        }
        write_json(output / "kernel-probes.json", probes)
        config = probes["config"]["stdout"]
        if "CONFIG_SECCOMP=y" not in config:
            errors.append("CONFIG_SECCOMP=y missing")
        if "CONFIG_SECCOMP_FILTER=y" not in config:
            errors.append("CONFIG_SECCOMP_FILTER=y missing")
        if "CONFIG_SECCOMP_USER_NOTIF=y" in config:
            errors.append(
                "USER_NOTIF unexpectedly enabled; ADR must not claim kernel absence"
            )
        kernel = probes["kernel_release"]["stdout"]
        if not kernel.startswith("5."):
            errors.append(f"unexpected kernel release {kernel!r}")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    decision = "NOT_APPLICABLE"
    user_notif = "CONFIG_SECCOMP_USER_NOTIF=y" in str(
        (probes.get("config") or {}).get("stdout") or ""
    )
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
        "build_result": "NOT_APPLICABLE",
        "static_result": "PASS" if static_rows and all(row["returncode"] == 0 for row in static_rows) else "FAIL",
        "targeted_result": "PASS" if not errors else "FAIL",
        "rd_result": "PASS" if not errors else "FAIL",
        "regression_result": "NOT_APPLICABLE",
        "failures": [
            {"id": "C3-T05-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": [
            "KI-R03-NATIVE-008 user-notify remains unimplemented / privileged",
            "KI-R03-NATIVE-ENF-001 isolated UID without hostile filter is not a network boundary",
        ],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            f"decision={decision}; user_notif_config={'present' if user_notif else 'absent'}; "
            "ordinary APK product uses C3-T04 isolated + deny-only BPF; "
            "Option D/E remain REQUIRES_PRIVILEGE"
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C3-T05-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence["rd_result"] = "PASS" if not errors else "FAIL"
    evidence["targeted_result"] = evidence["rd_result"]
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "static-gates.json"),
        str(output / "kernel-probes.json"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c3-t05-rd-summary.json"),
        str(VERIFICATION / "c3-t05-local-verification.json"),
        str(ROOT / "docs/review/C3_T05_SECCOMP_USER_NOTIFY_ADR.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c3-t05-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "NOT_APPLICABLE" if not errors else "FAIL",
        "decision": decision,
        "user_notify_config": "present" if user_notif else "absent",
        "kernel_release": (probes.get("kernel_release") or {}).get("stdout"),
        "seccomp_config": (probes.get("config") or {}).get("stdout"),
        "getenforce": (probes.get("getenforce") or {}).get("stdout"),
        "environment": environment,
        "static_gates": static_rows,
        "errors": errors,
        "privileged_alternatives": [
            "deny-only BPF in isolated hostile worker (already C3-T04)",
            "privileged companion owning a user-notify listener (Option E)",
            "OEM image with CONFIG_SECCOMP_USER_NOTIF and SELinux policy",
        ],
    }
    write_json(VERIFICATION / "c3-t05-local-verification.json", local)
    report_path.write_text(
        "\n".join(
            [
                "# C3-T05 seccomp/user-notify decision",
                "",
                f"- decision: {decision}",
                f"- kernel: {local.get('kernel_release')}",
                f"- user-notify config: {local.get('user_notify_config')}",
                f"- getenforce: {local.get('getenforce')}",
                "- VA Pro equivalent: NOT_PROVEN",
                "",
                "## SECCOMP config",
                "",
                "```",
                str(local.get("seccomp_config") or ""),
                "```",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(
        f"{'PASS' if not errors else 'FAIL'} {TASK_ID} decision={decision} "
        f"rd_result={evidence['rd_result']}"
    )
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
