#!/usr/bin/env python3
"""C4-R02 explicit Host and PackageService death recovery gates."""

from __future__ import annotations

import json
import sys
import time
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "capability"))

from run_c4_r01_rd import capture_snapshot, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import GUEST_PACKAGE, HOST_PACKAGE, resolve_rd_environment, run_adb

OUT = ROOT / "verification/catch-up/C4-R02/rd-acceptance/death-recovery"


def pids(serial: str) -> dict[str, Any]:
    rows = run_adb(serial, ["shell", "ps", "-A", "-o", "PID,NAME"], check=False).stdout
    parsed = []
    for line in rows.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2 and parts[0].isdigit() and parts[1].startswith(HOST_PACKAGE):
            parsed.append({"pid": int(parts[0]), "name": parts[1]})
    return {"capturedAt": now_iso(), "processes": parsed}


def await_pid_removed(serial: str, old_pid: int, deadline_sec: float = 10.0) -> dict[str, Any]:
    started = time.monotonic()
    polls = 0
    while time.monotonic() - started < deadline_sec:
        polls += 1
        current = pids(serial)
        if all(row["pid"] != old_pid for row in current["processes"]):
            return {"removed": True, "polls": polls,
                    "elapsedMs": round((time.monotonic() - started) * 1000), "state": current}
        time.sleep(0.1)
    return {"removed": False, "polls": polls,
            "elapsedMs": round((time.monotonic() - started) * 1000), "state": pids(serial)}


def catalog(serial: str) -> dict[str, Any]:
    raw = run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "cat",
                          "files/sandbox-catalog.json"], check=False).stdout
    return json.loads(raw)


def identity(value: dict[str, Any], package_name: str) -> dict[str, Any]:
    row = next(item for item in value.get("packages", [])
               if item.get("packageName") == package_name)
    return {"packageName": package_name, "sha256": row.get("sha256"),
            "versionCode": row.get("versionCode"), "apkPath": row.get("apkPath"),
            "instances": [item for item in value.get("instances", [])
                          if item.get("packageName") == package_name]}


def import_once(serial: str, request_id: str, force_stop_host: bool) -> dict[str, Any]:
    payload = debug_command(serial, ["--es", "command", "import-only",
                                     "--es", "package", GUEST_PACKAGE,
                                     "--ei", "user", "0",
                                     "--es", "requestId", request_id,
                                     "--ez", "trustNativeGuest", "true"],
                            deadline_sec=240, force_stop_host=force_stop_host)
    result = payload.get("result") or {}
    trace = result.get("packageOperationTrace") or {}
    if payload.get("status") != "PASS" or trace.get("requestId") != request_id:
        raise RuntimeError(f"RECOVERY_IMPORT_FAILED:{payload}")
    if trace.get("attempt") != 1 or trace.get("retryBudget") != 0:
        raise RuntimeError(f"RECOVERY_HIDDEN_RETRY:{trace}")
    return payload


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment("RD测试")
    serial = str(environment["adb_serial"])
    report: dict[str, Any] = {"task": "C4-R02", "environment": environment,
                              "startedAt": now_iso(), "status": "IN_PROGRESS"}
    write_json(OUT / "report.json", report)
    try:
        before_catalog = catalog(serial)
        before_identity = identity(before_catalog, GUEST_PACKAGE)
        host_before = pids(serial)
        run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
        host_pids = [row["pid"] for row in host_before["processes"]]
        host_removed = [await_pid_removed(serial, pid) for pid in host_pids]
        if any(not row["removed"] for row in host_removed):
            raise RuntimeError("HOST_DEATH_NOT_OBSERVED")
        host_request = f"c4-r02-host-death-{uuid.uuid4()}"
        host_recovery = import_once(serial, host_request, False)
        host_after = pids(serial)
        after_host_identity = identity(catalog(serial), GUEST_PACKAGE)
        if before_identity["sha256"] != after_host_identity["sha256"]:
            raise RuntimeError("HOST_DEATH_REVISION_NOT_RECOVERED")

        package_before = pids(serial)
        package_row = next((row for row in package_before["processes"]
                            if row["name"].endswith(":sandbox_package")), None)
        if package_row is None:
            raise RuntimeError("PACKAGE_SERVICE_PID_NOT_FOUND")
        killed = run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "kill", "-9",
                                  str(package_row["pid"])], check=False)
        package_removed = await_pid_removed(serial, int(package_row["pid"]))
        if killed.returncode != 0 or not package_removed["removed"]:
            raise RuntimeError(f"PACKAGE_SERVICE_DEATH_NOT_OBSERVED:{killed.stderr}")
        package_request = f"c4-r02-package-death-{uuid.uuid4()}"
        package_recovery = import_once(serial, package_request, False)
        package_after = pids(serial)
        after_package_identity = identity(catalog(serial), GUEST_PACKAGE)
        if before_identity["sha256"] != after_package_identity["sha256"]:
            raise RuntimeError("PACKAGE_SERVICE_DEATH_REVISION_NOT_RECOVERED")
        if not any(row["name"].endswith(":sandbox_package")
                   and row["pid"] != package_row["pid"]
                   for row in package_after["processes"]):
            raise RuntimeError("PACKAGE_SERVICE_NEW_GENERATION_NOT_OBSERVED")

        report.update({"status": "PASS", "completedAt": now_iso(),
                       "beforeIdentity": before_identity,
                       "hostDeath": {"before": host_before, "removed": host_removed,
                                     "recovery": host_recovery, "after": host_after,
                                     "identity": after_host_identity},
                       "packageServiceDeath": {"before": package_before,
                                               "killedPid": package_row,
                                               "killResult": {"returncode": killed.returncode,
                                                              "stdout": killed.stdout,
                                                              "stderr": killed.stderr},
                                               "removed": package_removed,
                                               "recovery": package_recovery,
                                               "after": package_after,
                                               "identity": after_package_identity}})
        write_json(OUT / "report.json", report)
        return 0
    except Exception as error:
        snapshot = capture_snapshot(serial, OUT / "first-failure", GUEST_PACKAGE)
        report.update({"status": "FAIL", "completedAt": now_iso(), "error": str(error),
                       "retryable": False, "attempt": 1, "retryBudget": 0,
                       "snapshot": snapshot})
        write_json(OUT / "report.json", report)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
