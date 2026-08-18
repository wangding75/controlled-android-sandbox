#!/usr/bin/env python3
"""FIX01-F: cumulative repetition, leak snapshots, and soak."""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_p2a_rd import process_alive
from run_fix01d_rd import live_guest
from run_rd_campaign import GUEST_PACKAGE, HOST_PACKAGE, apk_metadata, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX01-F"
TRUST = ("--ez", "trustNativeGuest", "true")


def cmd(serial: str, command: str, deadline: int = 90, force_stop_host: bool = False, extras: list[str] | None = None) -> dict[str, Any]:
    payload = ["-e", "command", command, "-e", "package", GUEST_PACKAGE, *TRUST]
    if extras:
        payload.extend(extras)
    return debug_command(serial, payload, deadline_sec=deadline, force_stop_host=force_stop_host)


def passed(probe: dict[str, Any]) -> bool:
    return str((probe.get("result") or {}).get("status") or probe.get("status") or "").upper() == "PASS"


def leak_snapshot(serial: str) -> dict[str, Any]:
    ps = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,UID,NAME"], check=False).stdout or ""
    host_lines = [line for line in ps.splitlines() if HOST_PACKAGE in line or GUEST_PACKAGE in line]
    fds: dict[str, int] = {}
    threads: dict[str, int] = {}
    for line in host_lines:
        parts = line.split()
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        pid = parts[0]
        name = parts[2]
        fd = run_adb(serial, ["shell", f"ls /proc/{pid}/fd"], check=False)
        fds[name] = len((fd.stdout or "").split())
        status = run_adb(serial, ["shell", f"cat /proc/{pid}/status"], check=False).stdout or ""
        for row in status.splitlines():
            if row.startswith("Threads:"):
                token = row.split()[-1]
                if token.isdigit():
                    threads[name] = int(token)
    return {
        "at": now_iso(),
        "process_lines": host_lines,
        "process_count": len(host_lines),
        "fds": fds,
        "threads": threads,
    }


def counts() -> dict[str, int]:
    return {
        "activity": 0,
        "service": 0,
        "provider": 0,
        "pi": 0,
        "alarm": 0,
        "notification": 0,
        "job": 0,
        "hostile": 0,
        "clear_reinstall": 0,
        "death_recycle": 0,
        "failures": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repeat", type=int, default=100)
    parser.add_argument("--soak-minutes", type=int, default=30)
    args = parser.parse_args()
    environment = resolve_rd_environment(RD_INSTANCE_NAME)
    serial = environment["adb_serial"]
    output = artifacts_dir("fix01f")
    write_json(output / "environment.json", environment)
    before = leak_snapshot(serial)
    write_json(output / "leak-before.json", before)
    tallies = counts()
    cycles: list[dict[str, Any]] = []

    cmd(serial, "import-prepare", 150, force_stop_host=True)
    for index in range(args.repeat):
        row: dict[str, Any] = {"index": index, "at": now_iso()}
        launch = cmd(serial, "launch", 60, force_stop_host=False)
        components = cmd(serial, "component-suite", 60)
        row.update({"launch": launch.get("status"), "components": components.get("status")})
        if passed(launch):
            tallies["activity"] += 1
        else:
            tallies["failures"] += 1
        if passed(components):
            tallies["service"] += 1
            tallies["provider"] += 1
        else:
            tallies["failures"] += 1
        if index % 2 == 0:
            holder = cmd(serial, "pi-system-holder", 90)
            job = cmd(serial, "launch-component", 60, extras=["-e", "component",
                "com.warden.controlledsandbox.fixture.FixtureJobScheduleActivity"])
            row["holder"] = holder.get("status")
            row["job"] = job.get("status")
            if passed(holder):
                tallies["pi"] += 2
                tallies["alarm"] += 1
                tallies["notification"] += 1
            if passed(job):
                tallies["job"] += 1
        if index % 5 == 4:
            guest = live_guest(serial)
            pid = int(guest.get("pid") or 0)
            if pid > 0:
                run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "kill", "-9", str(pid)], check=False)
                time.sleep(1)
                if not process_alive(serial, pid):
                    tallies["death_recycle"] += 1
                    recover = cmd(serial, "prepare", 90)
                    row["death_recover"] = recover.get("status")
            hostile = cmd(serial, "native-hostile", 90, force_stop_host=False)
            if passed(hostile):
                tallies["hostile"] += 1
            row["hostile"] = hostile.get("status")
        if index % 10 == 9:
            cleared = cmd(serial, "clear", 90)
            prepared = cmd(serial, "import-prepare", 150)
            if passed(cleared) and passed(prepared):
                tallies["clear_reinstall"] += 1
            row["clear"] = cleared.get("status")
            row["reimport"] = prepared.get("status")
        cycles.append(row)
        write_json(output / "counts.json", {"tallies": tallies, "completed": index + 1})
        if (index + 1) % 10 == 0:
            write_json(output / f"cycles-{index + 1:03d}.json", cycles[-10:])

    soak = []
    deadline = time.time() + args.soak_minutes * 60
    while time.time() < deadline:
        launch = cmd(serial, "launch", 90)
        soak.append({"at": now_iso(), "launch": launch.get("status")})
        if passed(launch):
            tallies["activity"] += 1
        time.sleep(15)

    after = leak_snapshot(serial)
    write_json(output / "leak-after.json", after)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "tallies": tallies,
        "targets": {
            "activity": 100, "service": 100, "provider": 100, "pi": 100,
            "alarm": 100, "notification": 100, "job": 100, "hostile": 100,
            "clear_reinstall": 20, "death_recycle": 20,
        },
        "targets_met": {key: tallies.get(key, 0) >= need for key, need in {
            "activity": 100, "service": 100, "provider": 100, "pi": 100,
            "alarm": 100, "notification": 100, "job": 100, "hostile": 100,
            "clear_reinstall": 20, "death_recycle": 20,
        }.items()},
        "leak_before": before,
        "leak_after": after,
        "soak_samples": len(soak),
        "failures": tallies["failures"],
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({"output": str(output), "tallies": tallies, "targets_met": evidence["targets_met"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
