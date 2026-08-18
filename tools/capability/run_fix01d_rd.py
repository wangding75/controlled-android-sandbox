#!/usr/bin/env python3
"""FIX01-D: system-held PendingIntent after actual guest stub death."""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_p2a_rd import guest_process_name, pidof, probe_kill
from run_rd_campaign import GUEST_PACKAGE, HOST_PACKAGE, apk_metadata, resolve_rd_environment, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX01-D"
TRUST = ("--ez", "trustNativeGuest", "true")


def main() -> int:
    environment = resolve_rd_environment(RD_INSTANCE_NAME)
    serial = environment["adb_serial"]
    output = artifacts_dir("fix01d")
    write_json(output / "environment.json", environment)

    armed = debug_command(
        serial,
        ["-e", "command", "pi-system-holder", "-e", "package", GUEST_PACKAGE, *TRUST],
        deadline_sec=150,
    )
    write_json(output / "armed.json", armed)
    notifications = run_adb(serial, ["shell", "dumpsys", "notification", "--noredact"], check=False)
    alarms = run_adb(serial, ["shell", "dumpsys", "alarm"], check=False)
    held_notification = "cas.system.holder" in (notifications.stdout or "") or "CAS system-holder" in (
        notifications.stdout or ""
    )
    held_alarm = "SYSTEM_HOLDER_ALARM" in (alarms.stdout or "") or "cas.system.holder" in (alarms.stdout or "")

    kill = probe_kill(serial)
    write_json(output / "kill.json", kill)
    time.sleep(8)
    recover = debug_command(
        serial,
        ["-e", "command", "prepare", "-e", "package", GUEST_PACKAGE, *TRUST],
        deadline_sec=90,
    )
    write_json(output / "recover.json", recover)
    delivered = run_adb(
        serial,
        ["shell", "run-as", GUEST_PACKAGE, "cat", "files/system-holder-delivered.json"],
        check=False,
    )
    after = run_adb(serial, ["shell", "dumpsys", "notification", "--noredact"], check=False)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "armed": armed,
        "notification_held_before_kill": held_notification,
        "alarm_held_before_kill": held_alarm,
        "kill": kill,
        "broker_survived": bool((kill.get("brokerPidsAfterKill") or [])),
        "recover": recover,
        "delivered_after_kill": {
            "returncode": delivered.returncode,
            "text": (delivered.stdout or "")[-2000:],
        },
        "notification_dump_after": (after.stdout or "")[-2000:],
        "guest_process_name": guest_process_name(54),
        "host_package": HOST_PACKAGE,
    }
    write_json(output / "evidence.json", evidence)
    try:
        validate_evidence(evidence)
    except Exception as exc:
        (output / "evidence_validation.txt").write_text(str(exc), encoding="utf-8")
    print(json.dumps({
        "output": str(output),
        "held_notification": held_notification,
        "held_alarm": held_alarm,
        "kill": kill.get("classification"),
        "broker_survived": evidence["broker_survived"],
        "recover": ((recover.get("result") or {}).get("status")),
        "delivered_rc": delivered.returncode,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
