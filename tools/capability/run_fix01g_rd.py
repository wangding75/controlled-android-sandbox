#!/usr/bin/env python3
"""FIX01-G: commercial evidence deepening beyond launch smoke."""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_fix01d_rd import live_guest
from run_p2a_rd import process_alive
from run_rd_campaign import HOST_PACKAGE, apk_metadata, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX01-G"
TRUST = ("--ez", "trustNativeGuest", "true")
TARGETS = (
    ("com.quark.browser", "Quark"),
    ("com.alibaba.android.rimet", "DingTalk"),
    ("com.dragon.read", "DragonRead"),
)


def cmd(serial: str, command: str, package: str, deadline: int = 150, force_stop_host: bool = True) -> dict[str, Any]:
    return debug_command(
        serial,
        ["-e", "command", command, "-e", "package", package, *TRUST],
        deadline_sec=deadline,
        force_stop_host=force_stop_host,
    )


def observe(serial: str, package: str) -> dict[str, Any]:
    ps = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,UID,NAME"], check=False).stdout or ""
    activities = run_adb(serial, ["shell", "dumpsys", "activity", "activities"], check=False).stdout or ""
    return {
        "guest": live_guest(serial),
        "ps_hits": [line for line in ps.splitlines() if package in line or HOST_PACKAGE in line],
        "activity_hits": [line.strip() for line in activities.splitlines() if package in line][:20],
    }


def deepen(serial: str, package: str, label: str) -> dict[str, Any]:
    installed = package in (run_adb(serial, ["shell", "pm", "list", "packages"], check=False).stdout or "")
    if not installed:
        return {"package": package, "label": label, "classification": "ENVIRONMENT_NOT_AVAILABLE",
                "status": "LAUNCH_SMOKE_NOT_RUN"}
    cold = cmd(serial, "import-launch", package, 180, True)
    obs_fg = observe(serial, package)
    warm = cmd(serial, "launch", package, 150, False)
    stop = cmd(serial, "stop", package, 90, False)
    obs_bg = observe(serial, package)
    fg_again = cmd(serial, "launch", package, 150, False)
    guest = live_guest(serial)
    pid = int(guest.get("pid") or 0)
    kill = {"pid": pid, "killed": False}
    if pid > 0:
        run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "kill", "-9", str(pid)], check=False)
        time.sleep(1.2)
        kill["killed"] = not process_alive(serial, pid)
    recover = cmd(serial, "prepare", package, 120, False)
    relaunch = cmd(serial, "launch", package, 150, False)
    soak = []
    deadline = time.time() + 3 * 60
    while time.time() < deadline:
        soak.append({"at": now_iso(), "observe": observe(serial, package)})
        time.sleep(30)
    launch_ok = str((cold.get("result") or {}).get("status") or "").upper() == "PASS"
    return {
        "package": package,
        "label": label,
        "status": "LAUNCH_SMOKE_PASS" if launch_ok else "LAUNCH_SMOKE_FAIL",
        "classification": "GENERAL_SYSTEM_VIRTUALIZATION" if launch_ok else "GENERAL_RUNTIME_DEFECT",
        "cold": cold,
        "warm": warm,
        "stop": stop,
        "fg_again": fg_again,
        "observe_fg": obs_fg,
        "observe_bg": obs_bg,
        "kill": kill,
        "recover": recover,
        "relaunch": relaunch,
        "soak_samples": soak,
        "login_limited": True,
        "notes": "No personal accounts. Login-gated surfaces untested.",
    }


def main() -> int:
    environment = resolve_rd_environment(RD_INSTANCE_NAME)
    serial = environment["adb_serial"]
    output = artifacts_dir("fix01g")
    write_json(output / "environment.json", environment)
    rows = []
    for package, label in TARGETS:
        row = deepen(serial, package, label)
        write_json(output / f"{label}.json", row)
        rows.append(row)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "targets": rows,
        "xh": "ENVIRONMENT_NOT_AVAILABLE",
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "results": [{"package": row["package"], "status": row.get("status"),
                     "classification": row.get("classification")} for row in rows],
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
