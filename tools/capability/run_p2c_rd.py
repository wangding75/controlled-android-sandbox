#!/usr/bin/env python3
"""T57-R03-P2C commercial compatibility corpus against already-installed legal APKs."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import apk_metadata, install_rd_apks, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P2C"
TRUST = ("--ez", "trustNativeGuest", "true")
TARGETS = (
    ("com.quark.browser", "WebView/Chromium-heavy", "Quark"),
    ("com.alibaba.android.rimet", "Framework-heavy", "DingTalk"),
    ("com.dragon.read", "Native/content-heavy", "DragonRead"),
    ("com.warden.controlledsandbox.fixture", "Flash2 fixture self-comparison", "Flash2Fixture"),
)


def classify(package: str, present: bool, launch: dict, relaunch: dict, clear: dict) -> dict:
    if not present:
        return {"classification": "ENVIRONMENT_NOT_AVAILABLE", "class": "ENVIRONMENT"}
    launch_status = str((launch.get("result") or {}).get("status") or launch.get("status") or "")
    relaunch_status = str((relaunch.get("result") or {}).get("status") or relaunch.get("status") or "")
    error = str(((launch.get("result") or {}).get("errorMessage") or launch.get("detail") or ""))
    if launch_status.upper() == "PASS" and relaunch_status.upper() == "PASS":
        return {"classification": "PASS", "class": "LAUNCHED"}
    if "UNTRUSTED_NATIVE" in error:
        return {"classification": "GENERAL_RUNTIME_DEFECT", "class": "NATIVE_TRUST", "error": error}
    if "ClassNotFound" in error or "ClassLoader" in error:
        return {"classification": "GENERAL_IDENTITY", "class": "LOADER", "error": error}
    if launch_status.upper() != "PASS":
        return {"classification": "GENERAL_RUNTIME_DEFECT", "class": "LAUNCH_FAIL", "error": error}
    return {"classification": "PARTIAL", "class": "RELAUNCH", "error": error}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("p2c")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True} if args.skip_install else install_rd_apks(serial)
    write_json(output / "install.json", install)
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"], check=False).stdout
    rows = []
    for package, kind, label in TARGETS:
        present = package in packages
        row = {"package": package, "kind": kind, "label": label, "present": present}
        if not present:
            row.update(classify(package, False, {}, {}, {}))
            rows.append(row)
            continue
        launch = debug_command(
            serial,
            ["-e", "command", "import-launch", "-e", "package", package, *TRUST],
            deadline_sec=150,
        )
        relaunch = debug_command(
            serial,
            ["-e", "command", "launch", "-e", "package", package, *TRUST],
            deadline_sec=120,
        )
        clear = debug_command(
            serial,
            ["-e", "command", "clear", "-e", "package", package, *TRUST],
            deadline_sec=90,
        )
        row.update({"launch": launch, "relaunch": relaunch, "clear": clear})
        row.update(classify(package, True, launch, relaunch, clear))
        write_json(output / f"{label}.json", row)
        rows.append(row)
    missing = {
        "XH": "ENVIRONMENT_NOT_AVAILABLE",
        "old_SX": "ENVIRONMENT_NOT_AVAILABLE",
    }
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "device_packages": packages,
        "targets": rows,
        "absent_preferred": missing,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "results": [{"package": row["package"], "classification": row.get("classification"),
                     "present": row.get("present")} for row in rows],
        "absent": missing,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
