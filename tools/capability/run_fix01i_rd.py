#!/usr/bin/env python3
"""FIX01-I: API35/API36 launch smoke."""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, apk_metadata, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX01-I"
TRUST = ("--ez", "trustNativeGuest", "true")
FIXTURES = (
    "com.warden.controlledsandbox.fixture",
    "com.warden.controlledsandbox.fixture.scale",
)


def probe(serial: str, package: str) -> dict:
    prepare = debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", package, *TRUST],
        deadline_sec=150,
    )
    launch = debug_command(
        serial,
        ["-e", "command", "import-launch", "-e", "package", package, *TRUST],
        deadline_sec=150,
    )
    return {
        "package": package,
        "prepare": prepare,
        "launch": launch,
        "prepare_status": (prepare.get("result") or {}).get("status") or prepare.get("status"),
        "launch_status": (launch.get("result") or {}).get("status") or launch.get("status"),
    }


def main() -> int:
    devices = run_adb(None, ["devices", "-l"], check=False).stdout or ""
    rows = []
    for line in devices.splitlines():
        if "\tdevice" not in line and " device " not in line:
            continue
        serial = line.split()[0]
        if serial == "List":
            continue
        api = run_adb(serial, ["shell", "getprop", "ro.build.version.sdk"], check=False).stdout.strip()
        model = run_adb(serial, ["shell", "getprop", "ro.product.model"], check=False).stdout.strip()
        if api not in {"35", "36"}:
            rows.append({"serial": serial, "api": api, "model": model, "skipped": True})
            continue
        installed = run_adb(serial, ["shell", "pm", "path", HOST_PACKAGE], check=False)
        fixture_rows = [probe(serial, package) for package in FIXTURES]
        rows.append({
            "serial": serial,
            "api": api,
            "model": model,
            "host_installed": installed.returncode == 0,
            "fixtures": fixture_rows,
        })
    output = artifacts_dir("fix01i")
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "apk": apk_metadata(),
        "devices": rows,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({"output": str(output), "devices": [
        {"serial": row.get("serial"), "api": row.get("api"), "skipped": row.get("skipped"),
         "fixtures": [
             {"package": item.get("package"), "prepare": item.get("prepare_status"),
              "launch": item.get("launch_status")}
             for item in row.get("fixtures") or []
         ]} for row in rows
    ]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
