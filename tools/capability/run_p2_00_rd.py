#!/usr/bin/env python3
"""T57-R03-P2-00 RD campaign: isolated peer identity + system-holder PI.

Reuses the P1-00 probe surface against the P2-00 runtime peer contract.
Resolves MuMu instance RD测试 dynamically. Never hard-codes historical serials.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import RD_INSTANCE_NAME
from common import artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import (
    probe_classloader,
    probe_isolated_slots,
    probe_ordinary_slots,
    probe_system_holder,
)
from run_rd_campaign import (
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
)

CAMPAIGN_ID = "T57-R03-P2-00"


def classify_isolated(isolated: dict) -> dict:
    rows = []
    for row in isolated.get("targets") or []:
        detail = str(row.get("detail") or "")
        status = str(row.get("status") or "")
        if status == "PASS":
            classification = "PASS"
        elif "UNTRUSTED_RUNTIME_PEER_UID" in detail:
            classification = "UNTRUSTED_RUNTIME_PEER_UID"
        else:
            classification = "FAIL"
        rows.append({**row, "classification": classification})
    seventeenth = isolated.get("seventeenth") or {}
    message = str(
        ((seventeenth.get("result") or {}).get("errorMessage"))
        or seventeenth.get("detail")
        or ""
    )
    seventeenth_class = (
        "NO_PROCESS_SLOT"
        if "NO_PROCESS_SLOT" in message
        else ("PASS" if seventeenth.get("status") == "PASS" else "FAIL")
    )
    return {"targets": rows, "seventeenth": {**seventeenth, "classification": seventeenth_class}}


def classify_holder(holder: dict) -> dict:
    armed = holder.get("armed") or {}
    message = str(((armed.get("result") or {}).get("errorMessage")) or "")
    if armed.get("status") == "PASS" and holder.get("notification_held_before_kill"):
        classification = "ARMED"
    elif "GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE" in message:
        classification = "ALIAS_OR_PACKAGE_STATE"
    elif armed.get("status") == "PASS":
        classification = "LAUNCHED_NOT_HELD"
    else:
        classification = "FAIL"
    delivered = holder.get("delivered_after_kill") or {}
    delivered_text = str(delivered.get("text") or "")
    return {
        **holder,
        "classification": classification,
        "delivered_after_kill_ok": "ARMED" in delivered_text or "delivered" in delivered_text.lower()
        or delivered.get("returncode") == 0 and delivered_text.strip().startswith("{"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default=RD_INSTANCE_NAME)
    parser.add_argument("--skip-install", action="store_true")
    args = parser.parse_args()
    output = artifacts_dir("p2-00")
    output.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance)
    serial = environment["adb_serial"]
    write_json(output / "environment.json", environment)
    install = {"skipped": True}
    if not args.skip_install:
        install = install_rd_apks(serial)
    write_json(output / "install.json", install)
    classloader = probe_classloader(serial)
    write_json(output / "classloader.json", classloader)
    ordinary = probe_ordinary_slots(serial)
    write_json(output / "ordinary_slots.json", ordinary)
    isolated = classify_isolated(probe_isolated_slots(serial))
    write_json(output / "isolated_slots.json", isolated)
    holder = classify_holder(probe_system_holder(serial))
    write_json(output / "system_holder.json", holder)
    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "environment": environment,
        "apk": apk_metadata(),
        "classloader": classloader,
        "ordinary_slots": ordinary,
        "isolated_slots": isolated,
        "system_holder": holder,
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
        "isolated": [
            {"target": row.get("target"), "status": row.get("status"),
             "classification": row.get("classification"), "observed": row.get("observed")}
            for row in isolated.get("targets") or []
        ],
        "seventeenth": (isolated.get("seventeenth") or {}).get("classification"),
        "system_holder": holder.get("classification"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
