#!/usr/bin/env python3
"""T57-R03-P2B RD: package lifecycle clone / identity reset / replace / rollback."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import GUEST_PACKAGE, apk_metadata, install_rd_apks, resolve_rd_environment

CAMPAIGN_ID = "T57-R03-P2B"
TRUST = ("--ez", "trustNativeGuest", "true")


def cmd(serial: str, command: str, extra: list[str] | None = None) -> dict:
    extras = ["-e", "command", command, "-e", "package", GUEST_PACKAGE, *TRUST]
    if extra:
        extras.extend(extra)
    return debug_command(serial, extras, deadline_sec=90)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("p2b")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True} if args.skip_install else install_rd_apks(serial)
    write_json(output / "install.json", install)

    import_prepare = cmd(serial, "import-prepare")
    write_json(output / "import_prepare.json", import_prepare)
    clone = cmd(serial, "lifecycle-clone")
    write_json(output / "clone.json", clone)
    status_after_clone = cmd(serial, "lifecycle-status")
    write_json(output / "status_after_clone.json", status_after_clone)
    reset = cmd(serial, "lifecycle-reset-identity")
    write_json(output / "reset_identity.json", reset)
    replace = cmd(serial, "import-prepare")
    write_json(output / "replace.json", replace)
    status_after_replace = cmd(serial, "lifecycle-status")
    write_json(output / "status_after_replace.json", status_after_replace)
    rollback = cmd(serial, "lifecycle-rollback")
    write_json(output / "rollback.json", rollback)
    recover = cmd(serial, "prepare")
    write_json(output / "recover.json", recover)

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "import_prepare": import_prepare,
        "clone": clone,
        "reset_identity": reset,
        "replace": replace,
        "rollback": rollback,
        "recover": recover,
        "status_after_clone": status_after_clone,
        "status_after_replace": status_after_replace,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "serial": serial,
        "clone": clone.get("status"),
        "reset": reset.get("status"),
        "replace": replace.get("status"),
        "rollback": rollback.get("status"),
        "recover": recover.get("status"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
