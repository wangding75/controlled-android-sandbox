#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

plan = (ROOT / "docs/plans/M4_T18_DEVELOPMENT_PLAN.md").read_text(encoding="utf-8")
if plan.count("**Execution status: PASS**") != 3:
    errors.append("M4-T18 plan must mark B1, B2 and B3 PASS")

required_docs = [
    "docs/M4_T18_B1_DEVELOPMENT_REPORT.md",
    "docs/M4_T18_B2_DEVELOPMENT_REPORT.md",
    "docs/M4_T18_B3_DEVELOPMENT_REPORT.md",
    "docs/M4_T18_STAGE_REPORT.md",
    "docs/M4_T18_DEVICE_PREFLIGHT_GAPS.md",
    "docs/comparisons/M4_T18_VA_NBB_COMPARISON.md",
]
for relative in required_docs:
    if not (ROOT / relative).is_file():
        errors.append(f"missing final M4-T18 document: {relative}")

preflight_path = ROOT / "verification/m4-t18-device-preflight.json"
try:
    preflight = json.loads(preflight_path.read_text(encoding="utf-8"))
except Exception as exc:
    errors.append(f"invalid device preflight manifest: {exc}")
    preflight = {}
if preflight.get("schemaVersion") != 1:
    errors.append("device preflight manifest must use schemaVersion 1")
if preflight.get("sourceReadyForDeviceTesting") is not True:
    errors.append("device preflight sourceReadyForDeviceTesting must be true")
device = preflight.get("deviceEvidence", {})
for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
    if device.get(field) != 0:
        errors.append(f"device preflight {field} must remain zero before device testing")
if device.get("status") != "not-tested":
    errors.append("device preflight status must remain not-tested")
if len(preflight.get("deviceTestTracks", [])) < 8:
    errors.append("device preflight must retain the complete device-test track list")

matrix_path = ROOT / "verification/m3-source-capability-matrix.json"
try:
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")
    matrix = {"capabilities": []}
capabilities = matrix.get("capabilities", [])
by_id = {item.get("id"): item for item in capabilities}
required_ids = {
    "quality.source-contract-closure",
    "quality.resource-lifecycle-closure",
    "release.device-test-preflight-baseline",
}
missing = sorted(required_ids - set(by_id))
if missing:
    errors.append("missing M4-T18 capability entries: " + ", ".join(missing))
verified = sorted(item.get("id", "") for item in capabilities if item.get("deviceStatus") == "verified")
if verified:
    errors.append("device capabilities cannot be marked verified before device testing: " + ", ".join(verified))
production_unresolved = {
    item.get("id") for item in capabilities
    if item.get("productionStatus") in {"partial", "blocked"}
}
declared_unresolved = set(preflight.get("matrixProductionUnresolved", []))
resolved_after_m4: set[str] = set()
for relative in ("verification/m5-t1-source-preflight.json", "verification/m5-t2-source-preflight.json", "verification/m5-t3-source-preflight.json", "verification/m5-t4-source-preflight.json"):
    candidate = ROOT / relative
    if not candidate.is_file():
        continue
    try:
        later = json.loads(candidate.read_text(encoding="utf-8"))
        resolved_after_m4.update(later.get("resolvedM4ProductionIds", []))
    except Exception as exc:
        errors.append(f"invalid post-M4 resolution manifest {relative}: {exc}")
expected_current = declared_unresolved - resolved_after_m4
if production_unresolved != expected_current:
    errors.append(
        "preflight unresolved production IDs differ from current matrix after explicit M5 resolutions: "
        f"missing={sorted(production_unresolved-expected_current)} "
        f"extra={sorted(expected_current-production_unresolved)} "
        f"resolvedAfterM4={sorted(resolved_after_m4)}"
    )

readme = (ROOT / "README.md").read_text(encoding="utf-8")
if "## M4-T18 source baseline" not in readme:
    errors.append("README must publish the M4-T18 source baseline")
if "Device evidence remains 0" not in readme:
    errors.append("README must explicitly retain zero device evidence")

if errors:
    print("FAIL M4-T18 final freeze checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(
    "PASS M4-T18 final freeze checks: "
    f"capabilities={len(capabilities)} unresolvedProduction={len(production_unresolved)} "
    f"deviceTracks={len(preflight.get('deviceTestTracks', []))}"
)
