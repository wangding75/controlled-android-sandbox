#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "verification" / "m3-source-capability-matrix.json"
SOURCE_VALID = {"complete", "partial", "missing"}
PRODUCTION_VALID = {"wired", "partial", "blocked", "not-applicable"}
DEVICE_VALID = {"verified", "partial", "not-tested", "blocked", "not-applicable"}

payload = json.loads(MATRIX.read_text(encoding="utf-8"))
capabilities = payload.get("capabilities", [])
if payload.get("schemaVersion") != 2:
    raise SystemExit("FAIL M3 capability matrix must use schemaVersion 2")
if not capabilities:
    raise SystemExit("FAIL M3 capability matrix has no capabilities")

ids: set[str] = set()
source_counts = {status: 0 for status in SOURCE_VALID}
production_counts = {status: 0 for status in PRODUCTION_VALID}
device_counts = {status: 0 for status in DEVICE_VALID}
errors: list[str] = []
for item in capabilities:
    capability_id = item.get("id", "")
    source_status = item.get("sourceStatus", "")
    production_status = item.get("productionStatus", "")
    device_status = item.get("deviceStatus", "")
    evidence = item.get("evidence", [])
    if not capability_id or capability_id in ids:
        errors.append(f"invalid or duplicate capability id: {capability_id!r}")
    ids.add(capability_id)
    if source_status not in SOURCE_VALID:
        errors.append(f"{capability_id}: invalid sourceStatus {source_status!r}")
    else:
        source_counts[source_status] += 1
    if production_status not in PRODUCTION_VALID:
        errors.append(f"{capability_id}: invalid productionStatus {production_status!r}")
    else:
        production_counts[production_status] += 1
    if device_status not in DEVICE_VALID:
        errors.append(f"{capability_id}: invalid deviceStatus {device_status!r}")
    else:
        device_counts[device_status] += 1
    if source_status == "complete" and not evidence:
        errors.append(f"{capability_id}: source-complete capability has no evidence")
    if production_status == "wired" and source_status == "missing":
        errors.append(f"{capability_id}: production cannot be wired when source is missing")
    if device_status == "verified" and production_status not in {"wired", "not-applicable"}:
        errors.append(f"{capability_id}: device verification requires wired production status")
    for relative in evidence:
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"{capability_id}: missing evidence file {relative}")

if errors:
    print("FAIL M3 capability evidence matrix", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)

source_weighted = source_counts["complete"] + source_counts["partial"] * 0.5
source_percentage = round(source_weighted * 100 / len(capabilities), 1)
production_denominator = len(capabilities) - production_counts["not-applicable"]
production_percentage = round(
    (production_counts["wired"] + production_counts["partial"] * 0.5) * 100
    / max(1, production_denominator), 1)
device_denominator = len(capabilities) - device_counts["not-applicable"]
device_percentage = round(
    (device_counts["verified"] + device_counts["partial"] * 0.5) * 100
    / max(1, device_denominator), 1)
print(
    "PASS M3 capability evidence matrix: "
    f"source complete={source_counts['complete']} partial={source_counts['partial']} "
    f"missing={source_counts['missing']} weighted={source_percentage}%; "
    f"production wired={production_counts['wired']} partial={production_counts['partial']} "
    f"blocked={production_counts['blocked']} n/a={production_counts['not-applicable']} "
    f"weighted={production_percentage}%; "
    f"device verified={device_counts['verified']} partial={device_counts['partial']} "
    f"not-tested={device_counts['not-tested']} blocked={device_counts['blocked']} "
    f"n/a={device_counts['not-applicable']} weighted={device_percentage}%"
)
if "--require-source-complete" in sys.argv and source_counts["partial"] + source_counts["missing"] > 0:
    print("M3_SOURCE_NOT_COMPLETE", file=sys.stderr)
    raise SystemExit(20)
if "--require-device-verified" in sys.argv:
    unresolved = device_counts["partial"] + device_counts["not-tested"] + device_counts["blocked"]
    if unresolved > 0:
        print("M3_DEVICE_NOT_VERIFIED", file=sys.stderr)
        raise SystemExit(21)
