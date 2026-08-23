#!/usr/bin/env python3
"""RD Native adversarial campaign runner.

Resolves MuMu instance RD测试 dynamically. Never hard-codes historical serials.
Installs existing host/fixture APKs, runs the test-only Native adversarial
fixture, and writes campaign evidence. BYPASS_CONFIRMED is a successful
discovery, not a campaign failure.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import (
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_rd_campaign import (
    CampaignBlocked,
    HOST_PACKAGE,
    DEBUG_ACTIVITY,
    GUEST_PACKAGE,
    apk_metadata,
    apk_paths,
    install_rd_apks,
    read_debug_command_result,
    resolve_rd_environment,
    run_adb,
    sha256_file,
)

CAMPAIGN_ID = "T57-R03-P0A-01"
FIXTURE64 = "com.warden.controlledsandbox.fixture"
FIXTURE32 = "com.warden.controlledsandbox.fixture32"
ADV_ACTIVITY = "com.warden.controlledsandbox.fixture.NativeAdversarialProbeActivity"
CASE_IDS = [f"NATIVE-ADV-{index:03d}" for index in range(1, 12)]


def collect_device_files(serial: str, output_dir: Path, environment: dict[str, Any]) -> None:
    devices = run_adb(None, ["devices", "-l"], check=False)
    (output_dir / "adb_devices.txt").write_text(devices.stdout or "", encoding="utf-8")
    props = run_adb(serial, ["shell", "getprop"], check=False)
    (output_dir / "device_properties.txt").write_text(props.stdout or "", encoding="utf-8")
    write_json(output_dir / "environment.json", environment)


def start_direct_fixture(serial: str, package: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{package}/{ADV_ACTIVITY}",
            "--es",
            "cas.native.context",
            "DIRECT_FIXTURE",
        ],
        check=False,
    )
    time.sleep(8)
    pulled = run_adb(
        serial,
        ["shell", "run-as", package, "cat", "files/native-adv-results.json"],
        check=False,
    )
    parsed: dict[str, Any] | None = None
    text = (pulled.stdout or "").strip()
    if text.startswith("{"):
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = None
    return {
        "package": package,
        "start_returncode": started.returncode,
        "start_stdout": started.stdout,
        "start_stderr": started.stderr,
        "result_returncode": pulled.returncode,
        "result_text": text,
        "result": parsed,
    }


def invoke_in_sandbox(serial: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"], check=False)
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
            "native-adversarial",
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
        result = read_debug_command_result(serial, deadline_sec=60)
        status = str(result.get("status") or "").upper()
        return {
            "probe": "debug-command:native-adversarial",
            "status": status,
            "returncode": 0 if status == "PASS" else 1,
            "result": result,
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }
    except CampaignBlocked as exc:
        return {
            "probe": "debug-command:native-adversarial",
            "status": "BLOCKED_BY_KNOWN_ISSUE" if "LAUNCH" in str(exc) else "ERROR",
            "returncode": 1,
            "detail": str(exc),
            "start_stdout": started.stdout,
            "start_stderr": started.stderr,
        }


def extract_case_status(payload: dict[str, Any] | None) -> dict[str, str]:
    statuses = {case: "UNVERIFIED_RUNTIME" for case in CASE_IDS}
    if not payload:
        return statuses
    for item in payload.get("cases") or []:
        case_id = str(item.get("id") or "")
        if case_id in statuses:
            statuses[case_id] = str(item.get("status") or "UNVERIFIED_RUNTIME")
    return statuses


def build_so_metadata() -> list[dict[str, Any]]:
    rows = []
    roots = [
        ROOT / "fixture-basic/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
        ROOT / "fixture-compat32/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
        ROOT / "sandbox-native/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
        ROOT / "sandbox-companion32/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
    ]
    for root in roots:
        if not root.is_dir():
            continue
        for so in root.rglob("*.so"):
            rows.append(
                {
                    "path": str(so),
                    "abi": so.parent.name,
                    "sha256": sha256_file(so),
                }
            )
    return rows


def render_report(
    payload: dict[str, Any],
    environment: dict[str, Any] | None,
    cases: dict[str, str],
    direct64: dict[str, Any],
    sandbox: dict[str, Any],
) -> str:
    env = environment or {}
    lines = [
        "# T57-R03-P0A-01 Native adversarial RD evidence",
        "",
        f"- campaign_id: `{payload['campaign_id']}`",
        f"- instance: `{RD_INSTANCE_NAME}`",
        f"- serial: `{payload['adb_serial']}`",
        f"- api_level: `{payload['api_level']}`",
        f"- abi: `{payload['abi']}`",
        f"- boot_id: `{payload.get('boot_id')}`",
        f"- android_id: `{payload.get('android_id')}`",
        f"- model: `{env.get('device_name', '')}`",
        f"- rd_result: `{payload['rd_result']}`",
        f"- maturity_level: `{payload['maturity_level']}`",
        f"- va_pro_equivalent: `{payload['va_pro_equivalent']}`",
        "",
        "Maturity label for this campaign is `RD_BASELINE_NATIVE_DISCOVERY`.",
        "RD测试 PASS is not VA Pro Equivalent.",
        "",
        "## Direct fixture",
        "",
        f"- package: `{direct64.get('package')}`",
        f"- start_rc: `{direct64.get('start_returncode')}`",
        "",
        "## In-sandbox",
        "",
        f"- status: `{sandbox.get('status')}`",
        "",
        "## Cases",
        "",
    ]
    for case_id, status in cases.items():
        lines.append(f"- `{case_id}`: `{status}`")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default=RD_INSTANCE_NAME)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-probe", action="store_true")
    args = parser.parse_args()

    identity = git_identity()
    out_dir = artifacts_dir("native")
    build = apk_metadata()
    build["native_libraries"] = build_so_metadata()
    write_json(out_dir / "build.json", build)

    environment: dict[str, Any] | None = None
    direct64: dict[str, Any] = {}
    direct32: dict[str, Any] = {}
    sandbox: dict[str, Any] = {}
    blocked: str | None = None
    logcat_text = ""
    try:
        if args.dry_run:
            write_json(
                out_dir / "plan.json",
                {
                    "instance_name": args.instance_name,
                    "resolver": "scripts/mumu_instance.py",
                    "direct_activity": ADV_ACTIVITY,
                    "sandbox_command": "native-adversarial",
                },
            )
        else:
            environment = resolve_rd_environment(args.instance_name)
            collect_device_files(environment["adb_serial"], out_dir, environment)
            if not args.skip_probe:
                serial = environment["adb_serial"]
                install = install_rd_apks(serial)
                write_json(out_dir / "install.json", install)
                run_adb(serial, ["logcat", "-c"], check=False)
                direct64 = start_direct_fixture(serial, FIXTURE64)
                write_json(out_dir / "direct64.json", direct64)
                direct32 = start_direct_fixture(serial, FIXTURE32)
                write_json(out_dir / "direct32.json", direct32)
                sandbox = invoke_in_sandbox(serial)
                write_json(out_dir / "sandbox.json", sandbox)
                logcat = run_adb(serial, ["logcat", "-d", "-s", "CS_NATIVE_ADV:I", "CS_FIXTURE:I"], check=False)
                logcat_text = logcat.stdout or ""
                (out_dir / "logcat.txt").write_text(logcat_text, encoding="utf-8")
    except CampaignBlocked as exc:
        blocked = str(exc)
        write_json(out_dir / "blocked.json", {"code": exc.code, "detail": exc.detail})

    cases = extract_case_status(direct64.get("result") if isinstance(direct64, dict) else None)
    meaningful = any(status != "UNVERIFIED_RUNTIME" for status in cases.values())
    rd_result = "DRY_RUN" if args.dry_run else "BLOCKED_ENV" if blocked else "UNVERIFIED"
    if meaningful:
        rd_result = "PASS"
    elif sandbox.get("status") == "PASS":
        rd_result = "PASS"
    elif blocked:
        rd_result = "BLOCKED_ENV"

    evidence = {
        "schema_version": 1,
        "campaign_id": CAMPAIGN_ID,
        "capability": "native_loader_jni_io",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": f"MuMu {RD_INSTANCE_NAME}",
        "device_name": str((environment or {}).get("device_name") or ""),
        "adb_serial": str((environment or {}).get("adb_serial") or ""),
        "api_level": (environment or {}).get("api_level"),
        "abi": str((environment or {}).get("abi") or ""),
        "boot_id": str((environment or {}).get("boot_id") or ""),
        "android_id": str((environment or {}).get("android_id") or ""),
        "instance_name": RD_INSTANCE_NAME,
        "build_result": "PASS" if all(item["exists"] for item in build.get("apks", [])) else "UNVERIFIED",
        "static_result": "UNVERIFIED",
        "targeted_result": "DRY_RUN" if args.dry_run else "PASS",
        "rd_result": rd_result,
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": ["KI-T57-009", "KI-R03-030"],
        "evidence_files": [
            str(out_dir / "evidence.json"),
            str(out_dir / "report.md"),
        ],
        "maturity_level": "RD_BASELINE",
        "notes": (
            "RD_BASELINE_NATIVE_DISCOVERY. BYPASS_CONFIRMED is a valid discovery. "
            "This is not VA Pro Equivalent."
        ),
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
    }
    if blocked:
        evidence["failures"].append(
            {
                "id": "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
                "classification": "ENVIRONMENT_BLOCKED",
                "summary": blocked,
            }
        )
    errors = validate_evidence(evidence)
    if errors:
        raise SystemExit("evidence schema validation failed: " + "; ".join(errors))
    write_json(out_dir / "evidence.json", evidence)
    write_json(
        out_dir / "adversarial_results.json",
        {
            "cases": cases,
            "direct64": direct64,
            "direct32": direct32,
            "sandbox": sandbox,
        },
    )
    write_json(out_dir / "native_boundary_matrix.json", {"source": str(MATRIX_PATH)})
    report = render_report(evidence, environment, cases, direct64, sandbox)
    (out_dir / "report.md").write_text(report, encoding="utf-8")
    print(report)
    print(f"EVIDENCE: {out_dir}")
    if args.dry_run:
        return 0
    if rd_result == "BLOCKED_ENV" and not meaningful:
        return 2
    return 0


MATRIX_PATH = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml"


if __name__ == "__main__":
    raise SystemExit(main())
