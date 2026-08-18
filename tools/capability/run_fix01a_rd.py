#!/usr/bin/env python3
"""FIX01-A RD smoke: bounded stub architecture on the current RD device."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import (
    GUEST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

CAMPAIGN_ID = "T57-R03-P4-FIX01-A"
TRUST_EXTRAS = ("--ez", "trustNativeGuest", "true")
SCALE_PACKAGE = "com.warden.controlledsandbox.fixture.scale"
SCALE_ACTIVITIES = (
    "com.warden.controlledsandbox.fixture.scale.ScaleActivity000",
    "com.warden.controlledsandbox.fixture.scale.ScaleActivity063",
    "com.warden.controlledsandbox.fixture.scale.ScaleActivity064",
    "com.warden.controlledsandbox.fixture.scale.ScaleActivity095",
    "com.warden.controlledsandbox.fixture.scale.ScaleActivity127",
)


def launch(serial: str, package: str, component: str) -> dict[str, Any]:
    return debug_command(
        serial,
        [
            "-e", "command", "launch-component",
            "-e", "package", package,
            "-e", "component", component,
            *TRUST_EXTRAS,
        ],
        deadline_sec=60,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("fix01a")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True}
    if not args.skip_install:
        install = install_rd_apks(serial)
        scale_apk = Path(__file__).resolve().parents[2] / (
            "fixture-activity-scale/build/outputs/apk/debug/fixture-activity-scale-debug.apk"
        )
        if scale_apk.is_file():
            installed = run_adb(serial, ["install", "-r", str(scale_apk)], check=False)
            install["scale"] = {
                "path": str(scale_apk),
                "returncode": installed.returncode,
                "stdout": installed.stdout,
                "stderr": installed.stderr,
            }
    write_json(output / "install.json", install)

    prepare = debug_command(
        serial,
        ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST_EXTRAS],
        deadline_sec=90,
    )
    write_json(output / "prepare-basic.json", prepare)

    slot63 = debug_command(
        serial,
        [
            "-e", "command", "slot-campaign",
            "-e", "package", GUEST_PACKAGE,
            "--ei", "slotTarget", "63",
            "--ez", "startService", "false",
            *TRUST_EXTRAS,
        ],
        deadline_sec=120,
    )
    write_json(output / "prepare-slot63.json", slot63)

    import_scale = debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", SCALE_PACKAGE, *TRUST_EXTRAS],
        deadline_sec=120,
    )
    write_json(output / "import-scale.json", import_scale)

    launches = []
    for component in SCALE_ACTIVITIES:
        row = {"package": SCALE_PACKAGE, "component": component, "probe": launch(serial, SCALE_PACKAGE, component)}
        launches.append(row)
        write_json(output / f"launch-{component.rsplit('.', 1)[-1]}.json", row)

    basic = launch(serial, GUEST_PACKAGE, "com.warden.controlledsandbox.fixture.MainActivity")
    write_json(output / "launch-basic-main.json", basic)

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "prepare_basic": prepare,
        "prepare_slot63": slot63,
        "import_scale": import_scale,
        "scale_launches": launches,
        "basic_launch": basic,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "serial": serial,
        "api": environment.get("api_level"),
        "prepare": (prepare.get("result") or {}).get("status"),
        "slot63": ((slot63.get("result") or {}).get("operation") or {}).get("processSlot"),
        "launches": [
            {
                "component": row["component"],
                "status": row["probe"].get("status"),
            }
            for row in launches
        ],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
