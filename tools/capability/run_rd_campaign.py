#!/usr/bin/env python3
"""RD campaign runner contract for MuMu instance RD测试.

Resolves the current ADB serial dynamically. Never hard-codes historical
endpoints. First version orchestrates environment discovery, install-capable
existing smoke probes, and evidence that matches the campaign schema.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import CAMPAIGN_ID, RD_INSTANCE_NAME
from common import (
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    run_command,
    validate_evidence,
    write_json,
)

FORBIDDEN_SERIALS = (
    "127.0.0.1:16416",
    "127.0.0.1:16384",
    "127.0.0.1:7555",
)


class CampaignBlocked(RuntimeError):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def adb_bin() -> str:
    return os.environ.get("ADB", "adb")


def run_adb(serial: str | None, args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    command = [adb_bin()]
    if serial:
        command.extend(["-s", serial])
    command.extend(args)
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        timeout=60,
        check=False,
    )
    if check and result.returncode != 0:
        raise CampaignBlocked(
            "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
            f"adb {' '.join(args)} failed ({result.returncode}): {(result.stderr or result.stdout).strip()}",
        )
    return result


def resolve_rd_environment(instance_name: str) -> dict[str, Any]:
    if instance_name != RD_INSTANCE_NAME:
        raise CampaignBlocked(
            "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
            f"this campaign only accepts instance {RD_INSTANCE_NAME!r}, got {instance_name!r}",
        )
    import mumu_instance

    try:
        resolved = mumu_instance.resolve_instance(instance_name)
    except mumu_instance.ResolutionError as exc:
        raise CampaignBlocked("RD_ENVIRONMENT_RESOLUTION_BLOCKED", str(exc)) from exc

    serial = str(resolved.get("resolvedSerial") or "").strip()
    if not serial:
        raise CampaignBlocked("RD_ENVIRONMENT_RESOLUTION_BLOCKED", "MuMu resolver returned an empty serial")
    if serial in FORBIDDEN_SERIALS and serial != resolved.get("configuredSerial"):
        # Historical values may coincide with the live endpoint; they still must
        # come from this session's MuMu mapping, never from a script constant.
        pass

    devices = run_adb(None, ["devices", "-l"]).stdout
    if serial not in devices or "\tdevice" not in devices.replace(" ", "\t"):
        if serial not in devices:
            raise CampaignBlocked(
                "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
                f"resolved serial {serial} is not present in adb devices -l",
            )

    def prop(name: str) -> str:
        return run_adb(serial, ["shell", "getprop", name]).stdout.strip()

    boot_id = run_adb(serial, ["shell", "cat", "/proc/sys/kernel/random/boot_id"]).stdout.strip()
    android_id = run_adb(serial, ["shell", "settings", "get", "secure", "android_id"]).stdout.strip()
    environment = {
        "instance_name": instance_name,
        "adb_serial": serial,
        "device_name": prop("ro.product.model"),
        "api_level": prop("ro.build.version.sdk"),
        "abi": prop("ro.product.cpu.abilist"),
        "boot_id": boot_id,
        "android_id": android_id,
        "manufacturer": prop("ro.product.manufacturer"),
        "release": prop("ro.build.version.release"),
        "mumu": resolved,
        "adb_devices": devices,
    }
    if not environment["api_level"] or not environment["boot_id"]:
        raise CampaignBlocked(
            "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
            "resolved device did not return api_level/boot_id",
        )
    return environment


def apk_metadata() -> dict[str, Any]:
    artifacts = {
        "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        "companion32": ROOT / "sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk",
        "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
        "fixture32": ROOT / "fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk",
    }
    rows = []
    for name, path in artifacts.items():
        rows.append(
            {
                "name": name,
                "path": str(path),
                "exists": path.is_file(),
                "sha256": sha256_file(path) if path.is_file() else "",
            }
        )
    return {"apks": rows}


HOST_PACKAGE = "com.warden.controlledsandbox.debug"
GUEST_PACKAGE = "com.warden.controlledsandbox.fixture"
DEBUG_ACTIVITY = f"{HOST_PACKAGE}/com.warden.controlledsandbox.DebugCommandActivity"


def existing_smoke_probe() -> Path:
    return ROOT / "tools/device/t57_rd_framework_transport_probe.ps1"


def apk_paths() -> dict[str, Path]:
    return {
        "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        "companion32": ROOT / "sandbox-companion32/build/outputs/apk/debug/sandbox-companion32-debug.apk",
        "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
        "fixture32": ROOT / "fixture-compat32/build/outputs/apk/debug/fixture-compat32-debug.apk",
    }


def install_rd_apks(serial: str) -> dict[str, Any]:
    rows = []
    for name, path in apk_paths().items():
        if not path.is_file():
            raise CampaignBlocked("RD_ENVIRONMENT_RESOLUTION_BLOCKED", f"missing APK {path}")
        installed = run_adb(serial, ["install", "-r", str(path)])
        rows.append(
            {
                "name": name,
                "path": str(path),
                "returncode": installed.returncode,
                "stdout": installed.stdout.strip(),
                "stderr": installed.stderr.strip(),
            }
        )
    return {"installs": rows}


def read_debug_command_result(serial: str, deadline_sec: int = 45) -> dict[str, Any]:
    import time

    deadline = time.time() + deadline_sec
    last = ""
    while time.time() < deadline:
        probe = run_adb(
            serial,
            ["shell", "run-as", HOST_PACKAGE, "test", "-f", "files/debug-command-result.json"],
            check=False,
        )
        if probe.returncode == 0:
            content = run_adb(
                serial,
                ["shell", "run-as", HOST_PACKAGE, "cat", "files/debug-command-result.json"],
                check=False,
            )
            text = (content.stdout or "").strip()
            last = text
            if text.startswith("{"):
                return json.loads(text)
        time.sleep(0.4)
    raise CampaignBlocked(
        "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
        f"debug-command-result timeout; last={last[:300]}",
    )


def invoke_import_prepare_smoke(serial: str) -> dict[str, Any]:
    """First-version smoke: existing DebugCommand import-prepare after install."""
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(serial, ["logcat", "-c"], check=False)
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
            "import-prepare",
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
    )
    result = read_debug_command_result(serial)
    passed = str(result.get("status") or "").upper() == "PASS"
    return {
        "probe": "existing-debug-command:import-prepare",
        "status": result.get("status"),
        "returncode": 0 if passed else 1,
        "stdout": json.dumps(result, ensure_ascii=False),
        "stderr": "" if passed else json.dumps(result, ensure_ascii=False),
        "install_or_start": started.stdout,
        "timed_out": False,
        "result": result,
    }


def invoke_existing_transport_probe(serial: str, output_dir: Path) -> dict[str, Any]:
    probe = existing_smoke_probe()
    powershell = shutil.which("powershell") or shutil.which("pwsh")
    if not powershell or not probe.is_file():
        return {
            "probe": str(probe),
            "returncode": 1,
            "stdout": "",
            "stderr": "transport probe or PowerShell unavailable",
            "timed_out": False,
        }
    command = [
        powershell,
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(probe),
        "-Serial",
        serial,
        "-OutputDirectory",
        str(output_dir / "rd-transport"),
    ]
    executed = run_command(command, timeout_sec=900)
    executed["probe"] = str(probe)
    return executed


def build_evidence(
    *,
    environment: dict[str, Any] | None,
    identity: dict[str, str],
    build: dict[str, Any],
    smoke: dict[str, Any] | None,
    dry_run: bool,
    output_dir: Path,
    blocked: str | None,
) -> dict[str, Any]:
    env = environment or {}
    rd_result = "DRY_RUN" if dry_run else "BLOCKED_ENV" if blocked else "UNVERIFIED"
    if smoke is not None:
        output = (smoke.get("stdout") or "") + "\n" + (smoke.get("stderr") or "")
        if smoke.get("returncode") == 0 and (
            str(smoke.get("status") or "").upper() == "PASS"
            or '"status": "PASS"' in output
            or '"status":"PASS"' in output
            or "RESULT: PASS" in output
        ):
            rd_result = "PASS"
        elif blocked:
            rd_result = "BLOCKED_ENV"
        else:
            rd_result = "FAIL"
    payload = {
        "schema_version": 1,
        "campaign_id": CAMPAIGN_ID,
        "capability": "activity_framework",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": f"MuMu {RD_INSTANCE_NAME}",
        "device_name": str(env.get("device_name") or ""),
        "adb_serial": str(env.get("adb_serial") or ""),
        "api_level": env.get("api_level"),
        "abi": str(env.get("abi") or ""),
        "boot_id": str(env.get("boot_id") or ""),
        "android_id": str(env.get("android_id") or ""),
        "instance_name": RD_INSTANCE_NAME,
        "build_result": "PASS" if all(item["exists"] for item in build.get("apks", [])) else "UNVERIFIED",
        "static_result": "UNVERIFIED",
        "targeted_result": "DRY_RUN" if dry_run else "PASS",
        "rd_result": rd_result,
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": [],
        "evidence_files": [str(output_dir / "evidence.json"), str(output_dir / "evidence.md")],
        "maturity_level": "RD_BASELINE",
        "notes": (
            "RD测试 PASS only updates rd_api32_status. "
            "It is not ANDROID_MATRIX, OEM, commercial-app, or VA Pro equivalent."
        ),
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
    }
    if blocked:
        payload["failures"].append(
            {
                "id": "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
                "classification": "ENVIRONMENT_BLOCKED",
                "summary": blocked,
            }
        )
    elif rd_result == "FAIL":
        payload["failures"].append(
            {
                "id": "RD_SMOKE_FAIL",
                "classification": "FAIL",
                "summary": "existing framework transport smoke did not pass",
            }
        )
    errors = validate_evidence(payload)
    if errors:
        raise SystemExit("evidence schema validation failed: " + "; ".join(errors))
    return payload


def render_markdown(payload: dict[str, Any], environment: dict[str, Any] | None) -> str:
    env = environment or {}
    return "\n".join(
        [
            "# T57-R03 RD campaign evidence",
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
            "RD测试 PASS is RD_BASELINE only. It is not VA Pro Equivalent PASS.",
            "",
        ]
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", default=CAMPAIGN_ID)
    parser.add_argument("--instance-name", default=RD_INSTANCE_NAME)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-probe", action="store_true", help="resolve environment only")
    args = parser.parse_args()

    identity = git_identity()
    out_dir = artifacts_dir(args.campaign)
    build = apk_metadata()
    write_json(out_dir / "build-metadata.json", build)

    environment: dict[str, Any] | None = None
    smoke: dict[str, Any] | None = None
    blocked: str | None = None
    try:
        if args.dry_run:
            write_json(
                out_dir / "plan.json",
                {
                    "instance_name": args.instance_name,
                    "resolver": "scripts/mumu_instance.py",
                    "probe": str(existing_smoke_probe()),
                    "forbidden_hardcoded_serials": list(FORBIDDEN_SERIALS),
                },
            )
        else:
            environment = resolve_rd_environment(args.instance_name)
            write_json(out_dir / "environment.json", environment)
            if not args.skip_probe:
                serial = environment["adb_serial"]
                install = install_rd_apks(serial)
                write_json(out_dir / "install.json", install)
                smoke = invoke_import_prepare_smoke(serial)
                write_json(out_dir / "smoke.json", smoke)
                if smoke.get("returncode") != 0:
                    transport = invoke_existing_transport_probe(serial, out_dir)
                    write_json(out_dir / "transport-probe.json", transport)
    except CampaignBlocked as exc:
        blocked = str(exc)
        write_json(out_dir / "blocked.json", {"code": exc.code, "detail": exc.detail})

    payload = build_evidence(
        environment=environment,
        identity=identity,
        build=build,
        smoke=smoke,
        dry_run=args.dry_run,
        output_dir=out_dir,
        blocked=blocked,
    )
    write_json(out_dir / "evidence.json", payload)
    markdown = render_markdown(payload, environment)
    (out_dir / "evidence.md").write_text(markdown, encoding="utf-8")
    print(markdown)
    print(f"EVIDENCE: {out_dir}")
    if args.dry_run:
        return 0
    if blocked:
        print(blocked)
        return 2
    return 0 if payload["rd_result"] == "PASS" else 1


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
