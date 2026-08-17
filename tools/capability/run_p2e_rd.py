#!/usr/bin/env python3
"""T57-R03-P2E repeated lifecycle + optional long-run soak."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import GUEST_PACKAGE, apk_metadata, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P2E"
TRUST = ("--ez", "trustNativeGuest", "true")


def cmd(serial: str, command: str, deadline: int = 90) -> dict:
    return debug_command(
        serial,
        ["-e", "command", command, "-e", "package", GUEST_PACKAGE, *TRUST],
        deadline_sec=deadline,
    )


def snapshot(serial: str) -> dict:
    ps = run_adb(serial, ["shell", "ps", "-A"], check=False).stdout
    return {
        "guest_lines": [line for line in ps.splitlines() if "controlledsandbox.fixture" in line],
        "host_lines": [line for line in ps.splitlines() if "controlledsandbox.debug" in line],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--repeat", type=int, default=20)
    parser.add_argument("--soak-minutes", type=int, default=30)
    args = parser.parse_args()
    output = artifacts_dir("p2e")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    before = snapshot(serial)
    write_json(output / "before.json", before)

    repeats = []
    for index in range(args.repeat):
        row = {
            "index": index,
            "prepare": cmd(serial, "prepare"),
            "components": cmd(serial, "component-suite"),
            "stop": cmd(serial, "stop"),
        }
        repeats.append(row)
        write_json(output / f"repeat-{index:02d}.json", row)
    clears = []
    for index in range(min(5, args.repeat)):
        row = {"index": index, "clear": cmd(serial, "clear"), "prepare": cmd(serial, "prepare")}
        clears.append(row)

    soak = []
    deadline = time.time() + args.soak_minutes * 60
    while time.time() < deadline:
        soak.append({
            "at": now_iso(),
            "launch": cmd(serial, "launch", 120),
            "stop": cmd(serial, "stop"),
        })
        time.sleep(20)

    after = snapshot(serial)
    write_json(output / "after.json", after)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "repeat": args.repeat,
        "soak_minutes": args.soak_minutes,
        "repeats": repeats,
        "clears": clears,
        "soak_count": len(soak),
        "soak": soak,
        "before": before,
        "after": after,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    failed = [row for row in repeats if row["prepare"].get("status") != "PASS"
              or row["components"].get("status") != "PASS"]
    soak_fail = [row for row in soak if row["launch"].get("status") != "PASS"]
    print(json.dumps({
        "output": str(output),
        "repeat_fail": len(failed),
        "soak_cycles": len(soak),
        "soak_fail": len(soak_fail),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
