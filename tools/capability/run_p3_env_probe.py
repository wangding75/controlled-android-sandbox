#!/usr/bin/env python3
"""P3 environment probe: resolve current device and run a short fixture smoke."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import GUEST_PACKAGE, resolve_rd_environment, run_adb

TRUST = ("--ez", "trustNativeGuest", "true")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--label", default="p3")
    args = parser.parse_args()
    output = artifacts_dir(args.label)
    output.mkdir(parents=True, exist_ok=True)
    try:
        environment = resolve_rd_environment(args.instance)
    except Exception as exc:
        write_json(output / "environment.json", {"status": "ENVIRONMENT_NOT_AVAILABLE", "error": str(exc)})
        print(json.dumps({"status": "ENVIRONMENT_NOT_AVAILABLE", "error": str(exc)}))
        return 0
    serial = environment["adb_serial"]
    page = run_adb(serial, ["shell", "getconf", "PAGE_SIZE"], check=False).stdout.strip()
    environment["page_size"] = page
    prepare = debug_command(
        serial, ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST], 90)
    launch = debug_command(
        serial, ["-e", "command", "launch", "-e", "package", GUEST_PACKAGE, *TRUST], 90)
    evidence = {
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "prepare": prepare,
        "launch": launch,
    }
    write_json(output / "evidence.json", evidence)
    print(json.dumps({
        "output": str(output),
        "api": environment.get("api_level"),
        "abi": environment.get("abi"),
        "page_size": page,
        "prepare": prepare.get("status"),
        "launch": launch.get("status"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
