#!/usr/bin/env python3
"""C4-T03 RD campaign: SX config/profile/media migration."""

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

TASK_ID = "C4-T03"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
VERIFICATION = ROOT / "verification/catch-up/C4-T03"
STATIC_GATES = ("scripts/check-c4-t03-sx-migration.py",)


def run_cmd(args: list[str], gate: str) -> dict[str, Any]:
    completed = subprocess.run(
        args, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
        capture_output=True, check=False,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
    )
    return {
        "gate": gate,
        "returncode": completed.returncode,
        "stdout": (completed.stdout or "")[-4000:],
        "stderr": (completed.stderr or "")[-2000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c4-t03")
    VERIFICATION.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    smoke: dict[str, Any] = {}
    try:
        static_rows.append(run_cmd([sys.executable, str(ROOT / STATIC_GATES[0])], STATIC_GATES[0]))
        static_rows.append(run_cmd(
            [str(ROOT / "gradlew.bat"), ":sandbox-domain:selfTest", "--no-daemon"],
            ":sandbox-domain:selfTest",
        ))
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        assembled = run_cmd(
            [
                str(ROOT / "gradlew.bat"),
                ":app:assembleDebug",
                ":fixture-basic:assembleDebug",
                ":sandbox-companion32:assembleDebug",
                ":fixture-compat32:assembleDebug",
                "--no-daemon",
            ],
            "assembleDebug device APKs",
        )
        static_rows.append(assembled)
        write_json(output / "static-gates.json", static_rows)
        if assembled["returncode"] != 0:
            raise RuntimeError("device APK assemble failed")
        environment = resolve_rd_environment(args.instance)
        write_json(output / "environment.json", environment)
        install_rd_apks(environment["adb_serial"])
        smoke = debug_command(
            environment["adb_serial"],
            [
                "--es", "command", "c4-t03-migrate",
                "--es", "package", GUEST_PACKAGE,
                "--ei", "user", "0",
                "--ez", "trustNativeGuest", "true",
            ],
            deadline_sec=240,
        )
        write_json(output / "c4-t03-migrate.json", smoke)
        if str(smoke.get("status") or "").upper() != "PASS":
            raise RuntimeError(f"c4-t03-migrate failed: {json.dumps(smoke, ensure_ascii=False)[:2000]}")
        campaign = (smoke.get("result") or {}).get("c4t03") or {}
        if not campaign.get("pass"):
            raise RuntimeError(f"migration campaign did not pass: {campaign}")
        if campaign.get("replay") != "IDEMPOTENT":
            raise RuntimeError(f"replay was not idempotent: {campaign.get('replay')}")
        if campaign.get("user0Lat") == campaign.get("user1Lat"):
            raise RuntimeError("user latitudes collided")
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
            {"id": "C4-T03-ACCEPTANCE", "classification": "FAIL", "summary": error}
            for error in errors
        ],
        "known_issues": ["KI-R03-048 C4-T03 SX data migration"],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "C4-T03 migrates desensitized sx_config documents onto instance-scoped CAS "
            "profiles with backup, idempotent replay, interrupt, and rollback."
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C4-T03-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
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
        str(VERIFICATION / "c4-t03-rd-summary.json"),
        str(VERIFICATION / "c4-t03-local-verification.json"),
        str(ROOT / "docs/review/C4_T03_SX_DATA_MIGRATION_DESIGN.md"),
    ]
    write_json(evidence_path, evidence)
    write_json(VERIFICATION / "c4-t03-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "PASS" if not errors else "FAIL",
        "environment": environment,
        "static_gates": [
            {"gate": row["gate"], "returncode": row["returncode"]}
            for row in static_rows
        ],
        "smoke": {
            "status": smoke.get("status"),
            "campaign": (smoke.get("result") or {}).get("c4t03"),
        },
        "apk_metadata": apk_metadata(),
        "errors": errors,
        "non_claims": [
            "not DingTalk PASS",
            "not BlackBox removed from production APK",
            "not VA Pro equivalence",
        ],
    }
    write_json(VERIFICATION / "c4-t03-local-verification.json", local)
    report_path.write_text(
        f"# C4-T03 SX data migration\n\n- status: {evidence['rd_result']}\n",
        encoding="utf-8",
    )
    print(f"{'PASS' if not errors else 'FAIL'} {TASK_ID} rd_result={evidence['rd_result']}")
    for error in errors:
        print(f" - {error}")
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
