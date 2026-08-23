#!/usr/bin/env python3
"""C4-T02 RD campaign: SX engine methods through CAS SDK only."""

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
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    CampaignBlocked,
    GUEST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
)

TASK_ID = "C4-T02"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C4-T02"
STATIC_GATES = (
    "scripts/check-c4-t02-sx-adapter.py",
    "scripts/check-c4-t01-sx-freeze.py",
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


def run_sdk_self_test() -> dict[str, Any]:
    gradlew = ROOT / "gradlew.bat"
    completed = subprocess.run(
        [str(gradlew), ":sandbox-sdk:selfTest", "--no-daemon"],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    return {
        "gate": ":sandbox-sdk:selfTest",
        "returncode": completed.returncode,
        "stdout": completed.stdout[-4000:],
        "stderr": completed.stderr[-2000:],
    }


def assemble_device_apks() -> dict[str, Any]:
    gradlew = ROOT / "gradlew.bat"
    completed = subprocess.run(
        [
            str(gradlew),
            ":app:assembleDebug",
            ":fixture-basic:assembleDebug",
            ":sandbox-companion32:assembleDebug",
            ":fixture-compat32:assembleDebug",
            "--no-daemon",
        ],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    return {
        "gate": "assembleDebug device APKs",
        "returncode": completed.returncode,
        "stdout": completed.stdout[-4000:],
        "stderr": completed.stderr[-2000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c4-t02")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    smoke: dict[str, Any] = {}
    mapping: dict[str, Any] = {}
    try:
        static_rows = run_static_gates()
        static_rows.append(run_sdk_self_test())
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        assembled = assemble_device_apks()
        static_rows.append(assembled)
        write_json(output / "static-gates.json", static_rows)
        if assembled["returncode"] != 0:
            raise RuntimeError("device APK assemble failed")
        environment = resolve_rd_environment(args.instance)
        write_json(output / "environment.json", environment)
        mapping = json.loads((VERIFICATION / "c4-t02-engine-mapping.json").read_text(encoding="utf-8"))
        installed = install_rd_apks(environment["adb_serial"])
        write_json(output / "apk-install.json", installed)
        smoke = debug_command(
            environment["adb_serial"],
            [
                "--es", "command", "c4-t02-engine",
                "--es", "package", GUEST_PACKAGE,
                "--ei", "user", "0",
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=240,
        )
        write_json(output / "c4-t02-engine.json", smoke)
        if str(smoke.get("status") or "").upper() != "PASS":
            raise RuntimeError(f"c4-t02-engine failed: {json.dumps(smoke, ensure_ascii=False)[:1500]}")
        campaign = (smoke.get("result") or {}).get("c4t02") or {}
        if not campaign.get("pass"):
            raise RuntimeError(f"engine campaign did not pass: {campaign}")
        encoded = json.dumps(campaign, ensure_ascii=False)
        for token in ("BlackBoxCore", "top.niunaijun.blackbox", "PineXposed"):
            if token in encoded:
                raise RuntimeError(f"engine trace contains {token}")
        if int(campaign.get("observerOperations") or 0) < 8:
            raise RuntimeError("observer did not record engine operations")
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(str(exc))

    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "package_lifecycle_clear_delete_reinstall",
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
        "build_result": "PASS" if static_rows and all(
            row["gate"] != "assembleDebug device APKs" or row["returncode"] == 0
            for row in static_rows
        ) else "FAIL",
        "static_result": "PASS" if static_rows and all(
            not str(row["gate"]).endswith(".py") or row["returncode"] == 0
            for row in static_rows
        ) else "FAIL",
        "targeted_result": "PASS" if not errors else "FAIL",
        "rd_result": "PASS" if not errors else "FAIL",
        "regression_result": "NOT_APPLICABLE",
        "failures": [
            {"id": "C4-T02-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": ["KI-R03-047 C4-T02 SX CAS SDK adapter"],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "C4-T02 routes SX install/start/stop/user/status through CasSandboxEngine "
            "and SandboxSdk. Adapter does not own catalog authority. "
            "setDisplayName persistence, sx_config, and live SX BlackBox deletion remain "
            f"C4-T03/T04. engine_methods={len(mapping.get('engine_methods') or [])}"
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C4-T02-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence["rd_result"] = "PASS" if not errors else "FAIL"
    evidence["targeted_result"] = evidence["rd_result"]
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "static-gates.json"),
        str(evidence_path),
        str(report_path),
        str(VERIFICATION / "c4-t02-engine-mapping.json"),
        str(VERIFICATION / "c4-t02-rd-summary.json"),
        str(VERIFICATION / "c4-t02-local-verification.json"),
        str(ROOT / "docs/review/C4_T02_SX_CAS_SDK_ADAPTER_DESIGN.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c4-t02-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "PASS" if not errors else "FAIL",
        "environment": environment,
        "static_gates": [
            {"gate": row["gate"], "returncode": row["returncode"],
             "stdout": (row.get("stdout") or "")[-500:],
             "stderr": (row.get("stderr") or "")[-300:]}
            for row in static_rows
        ],
        "smoke": {
            "status": smoke.get("status"),
            "pass": ((smoke.get("result") or {}).get("c4t02") or {}).get("pass"),
            "cloneUser": ((smoke.get("result") or {}).get("c4t02") or {}).get("cloneUser"),
            "observerOperations": ((smoke.get("result") or {}).get("c4t02") or {}).get("observerOperations"),
            "traceCount": ((smoke.get("result") or {}).get("c4t02") or {}).get("traceCount"),
        },
        "mapping_counts": {
            "engine_methods": len(mapping.get("engine_methods") or []),
            "unfinished_entries": len(mapping.get("unfinished_entries") or []),
        },
        "apk_metadata": apk_metadata(),
        "errors": errors,
        "non_claims": [
            "not SX live-tree BlackBox removed",
            "not DingTalk PASS",
            "not profile/config data migration",
            "not VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c4-t02-local-verification.json", local)
    report_path.write_text(
        "\n".join(
            [
                "# C4-T02 SX CAS SDK adapter",
                "",
                f"- status: {evidence['rd_result']}",
                f"- instance: {evidence['instance_name']}",
                f"- device: {evidence['device_name']} API {evidence['api_level']}",
                f"- engine methods: {local['mapping_counts']['engine_methods']}",
                "- VA Pro equivalent: NOT_PROVEN",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(f"{'PASS' if not errors else 'FAIL'} {TASK_ID} rd_result={evidence['rd_result']}")
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
