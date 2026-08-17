#!/usr/bin/env python3
"""RD Native enforcement POC campaign runner.

Resolves MuMu instance RD测试 dynamically. Never hard-codes historical serials.
One install, then FS/Net/Seccomp matrix. Isolated UID must be distinct.
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
from run_native_adversarial_rd import build_so_metadata
from run_rd_campaign import (
    CampaignBlocked,
    DEBUG_ACTIVITY,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    read_debug_command_result,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "T57-R03-P0A-02"
FIXTURE64 = "com.warden.controlledsandbox.fixture"
FIXTURE32 = "com.warden.controlledsandbox.fixture32"
SECCOMP_ACTIVITY = "com.warden.controlledsandbox.fixture.NativeEnforcementSeccompActivity"


def pull_run_as_json(serial: str, package: str, relative: str) -> dict[str, Any]:
    pulled = run_adb(serial, ["shell", "run-as", package, "cat", relative], check=False)
    text = (pulled.stdout or "").strip()
    parsed: dict[str, Any] | None = None
    if text.startswith("{"):
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = None
    return {
        "package": package,
        "path": relative,
        "returncode": pulled.returncode,
        "text": text,
        "result": parsed,
    }


def invoke_host_campaign(serial: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/debug-command-result.json"],
        check=False,
    )
    run_adb(
        serial,
        ["shell", "run-as", HOST_PACKAGE, "rm", "-f", "files/native-enf-results.json"],
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
            "native-hostile",
        ],
        check=False,
    )
    try:
        command = read_debug_command_result(serial, deadline_sec=90)
    except CampaignBlocked as exc:
        command = {"status": "ERROR", "errorMessage": str(exc)}
    results = pull_run_as_json(serial, HOST_PACKAGE, "files/native-enf-results.json")
    uid_map = pull_run_as_json(serial, HOST_PACKAGE, "files/native-enf-uid-map.json")
    fs = pull_run_as_json(serial, HOST_PACKAGE, "files/native-enf-fs.json")
    net = pull_run_as_json(serial, HOST_PACKAGE, "files/native-enf-net.json")
    seccomp = pull_run_as_json(serial, HOST_PACKAGE, "files/native-enf-seccomp.json")
    return {
        "start_returncode": started.returncode,
        "start_stdout": started.stdout,
        "start_stderr": started.stderr,
        "debug_command": command,
        "results": results,
        "uid_map": uid_map,
        "fs": fs,
        "net": net,
        "seccomp": seccomp,
    }


def start_fixture_seccomp(serial: str, package: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    run_adb(serial, ["logcat", "-c"], check=False)
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "startservice",
            "-n",
            f"{package}/com.warden.controlledsandbox.fixture.NativeEnforcementSeccompService",
        ],
        check=False,
    )
    activity = run_adb(
        serial,
        ["shell", "am", "start", "-W", "-n", f"{package}/{SECCOMP_ACTIVITY}"],
        check=False,
    )
    deadline = time.time() + 25
    pulled = {
        "package": package,
        "path": "files/native-enf-seccomp.json",
        "returncode": 1,
        "text": "",
        "result": None,
    }
    while time.time() < deadline:
        pulled = pull_run_as_json(serial, package, "files/native-enf-seccomp.json")
        parsed = pulled.get("result") or {}
        if parsed.get("classification") or parsed.get("status") in {"PASS", "ERROR"}:
            break
        time.sleep(1)
    parsed = pulled.get("result") or {}
    if not (parsed.get("classification") or parsed.get("status") in {"PASS", "ERROR"}):
        dumped = run_adb(
            serial,
            ["logcat", "-d", "-t", "80", "-s", "CS_NATIVE_ENF:I"],
            check=False,
        )
        for line in (dumped.stdout or "").splitlines():
            marker = "SECCOMP_JSON "
            if marker not in line:
                continue
            raw = line.split(marker, 1)[1].strip()
            if not raw.startswith("{"):
                continue
            try:
                pulled["result"] = json.loads(raw)
                pulled["source"] = "logcat"
            except json.JSONDecodeError:
                continue
    pulled["start_returncode"] = started.returncode
    pulled["start_stdout"] = started.stdout
    pulled["start_stderr"] = started.stderr
    pulled["activity_stdout"] = activity.stdout
    return pulled


def dumps_ps(serial: str) -> str:
    ps = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,UID,NAME"], check=False)
    return ps.stdout or ""


def render_report(payload: dict[str, Any], host: dict[str, Any], sec64: dict[str, Any],
                  sec32: dict[str, Any]) -> str:
    result = ((host.get("results") or {}).get("result")) or {}
    isolated = result.get("isolated") or {}
    host_id = result.get("host") or {}
    lines = [
        "# T57-R03-P0A-02 Native enforcement RD evidence",
        "",
        f"- campaign_id: `{payload['campaign_id']}`",
        f"- instance: `{RD_INSTANCE_NAME}`",
        f"- serial: `{payload['adb_serial']}`",
        f"- api_level: `{payload['api_level']}`",
        f"- abi: `{payload['abi']}`",
        f"- boot_id: `{payload.get('boot_id')}`",
        f"- android_id: `{payload.get('android_id')}`",
        f"- rd_result: `{payload['rd_result']}`",
        f"- maturity_level: `{payload['maturity_level']}`",
        f"- va_pro_equivalent: `{payload['va_pro_equivalent']}`",
        "",
        "Maturity label: `RD_BASELINE_NATIVE_ENFORCEMENT_POC`.",
        "This is not VA Pro Equivalent, ANDROID_MATRIX, or OEM.",
        "",
        "## Process isolation",
        "",
        f"- host uid/pid/name: `{host_id}`",
        f"- isolated: `{isolated}`",
        f"- distinct: `{result.get('isolatedUidDistinct')}`",
        "",
        "## Conclusions",
        "",
        f"- FS: `{result.get('fs_conclusion')}` / `{result.get('BROKER_FS_CAPABILITY')}`",
        f"- NET: `{result.get('net_conclusion')}` / `{result.get('BROKER_NET_CAPABILITY')}`",
        f"- SECCOMP-64: `{result.get('seccomp_conclusion')}` / `{result.get('SECCOMP_FILTER')}`",
        f"- SECCOMP fixture64: `{(sec64.get('result') or {}).get('classification')}`",
        f"- SECCOMP fixture32: `{(sec32.get('result') or {}).get('classification')}`",
        "",
        "## Cases",
        "",
    ]
    for item in result.get("cases") or []:
        lines.append(f"- `{item.get('id')}`: `{item.get('status') or item.get('classification')}`")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default=RD_INSTANCE_NAME)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()

    identity = git_identity()
    out_dir = artifacts_dir("native-enforcement")
    build = apk_metadata()
    build["native_libraries"] = build_so_metadata()
    write_json(out_dir / "build.json", build)

    environment: dict[str, Any] | None = None
    host: dict[str, Any] = {}
    sec64: dict[str, Any] = {}
    sec32: dict[str, Any] = {}
    blocked: str | None = None
    logcat_text = ""
    try:
        if args.dry_run:
            write_json(
                out_dir / "plan.json",
                {
                    "instance_name": args.instance_name,
                    "resolver": "scripts/mumu_instance.py",
                    "host_command": "native-enforcement",
                    "seccomp_activity": SECCOMP_ACTIVITY,
                    "serial_policy": "dynamic resolve_rd_environment; no hardcoded historical serial",
                },
            )
        else:
            environment = resolve_rd_environment(args.instance_name)
            write_json(out_dir / "environment.json", environment)
            devices = run_adb(None, ["devices", "-l"], check=False)
            (out_dir / "adb_devices.txt").write_text(devices.stdout or "", encoding="utf-8")
            serial = environment["adb_serial"]
            if not args.skip_install:
                write_json(out_dir / "install.json", install_rd_apks(serial))
            run_adb(serial, ["logcat", "-c"], check=False)
            host = invoke_host_campaign(serial)
            write_json(out_dir / "host.json", host)
            uid = (host.get("uid_map") or {}).get("result")
            write_json(out_dir / "uid_map.json", uid or host.get("uid_map") or {})
            write_json(out_dir / "fs_results.json", (host.get("fs") or {}).get("result") or host.get("fs") or {})
            write_json(out_dir / "network_results.json", (host.get("net") or {}).get("result") or host.get("net") or {})
            write_json(
                out_dir / "seccomp_results.json",
                {
                    "host": (host.get("seccomp") or {}).get("result") or host.get("seccomp"),
                    "fixture64": None,
                    "fixture32": None,
                },
            )
            sec64 = start_fixture_seccomp(serial, FIXTURE64)
            sec32 = start_fixture_seccomp(serial, FIXTURE32)
            write_json(
                out_dir / "seccomp_results.json",
                {
                    "host": (host.get("seccomp") or {}).get("result") or host.get("seccomp"),
                    "fixture64": sec64.get("result") or sec64,
                    "fixture32": sec32.get("result") or sec32,
                },
            )
            (out_dir / "ps.txt").write_text(dumps_ps(serial), encoding="utf-8")
            logcat = run_adb(
                serial,
                ["logcat", "-d", "-s", "CS_NATIVE_ENF:I", "CS_COMMAND:I"],
                check=False,
            )
            logcat_text = logcat.stdout or ""
            (out_dir / "logcat.txt").write_text(logcat_text, encoding="utf-8")
    except CampaignBlocked as exc:
        blocked = str(exc)
        write_json(out_dir / "blocked.json", {"code": exc.code, "detail": exc.detail})

    host_result = ((host.get("results") or {}).get("result")) or {}
    poc_valid = bool(host_result.get("isolatedUidDistinct"))
    meaningful = bool(host_result.get("cases"))
    rd_result = "DRY_RUN" if args.dry_run else "BLOCKED_ENV" if blocked else "UNVERIFIED"
    if poc_valid and meaningful:
        rd_result = "PASS"
    elif blocked:
        rd_result = "BLOCKED_ENV"
    elif host and not poc_valid:
        rd_result = "FAIL"

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
        "known_issues": ["KI-R03-NATIVE-010"],
        "evidence_files": [str(out_dir / "evidence.json"), str(out_dir / "report.md")],
        "maturity_level": "RD_BASELINE",
        "notes": (
            "RD_BASELINE_NATIVE_ENFORCEMENT_POC. Isolated host debug process only. "
            "Not CAS guest production wiring. Not VA Pro Equivalent."
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
    if host and not poc_valid and not args.dry_run:
        evidence["failures"].append(
            {
                "id": "ISOLATED_UID_POC_INVALID",
                "classification": "FAIL",
                "summary": str(host_result.get("error") or "isolated UID was not distinct"),
            }
        )
    errors = validate_evidence(evidence)
    if errors:
        raise SystemExit("evidence schema validation failed: " + "; ".join(errors))
    write_json(out_dir / "evidence.json", evidence)
    if not (out_dir / "environment.json").is_file():
        write_json(out_dir / "environment.json", environment or {"dry_run": True})
    if not (out_dir / "uid_map.json").is_file():
        write_json(out_dir / "uid_map.json", {})
    if not (out_dir / "fs_results.json").is_file():
        write_json(out_dir / "fs_results.json", {})
    if not (out_dir / "network_results.json").is_file():
        write_json(out_dir / "network_results.json", {})
    if not (out_dir / "seccomp_results.json").is_file():
        write_json(out_dir / "seccomp_results.json", {})
    if not (out_dir / "logcat.txt").is_file():
        (out_dir / "logcat.txt").write_text(logcat_text, encoding="utf-8")
    report = render_report(evidence, host, sec64, sec32)
    (out_dir / "report.md").write_text(report, encoding="utf-8")
    print(report)
    print(f"EVIDENCE: {out_dir}")
    if args.dry_run:
        return 0
    if rd_result in {"BLOCKED_ENV", "FAIL"}:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
