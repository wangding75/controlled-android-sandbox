#!/usr/bin/env python3
"""Validate capability campaign YAML/JSON registries and evidence schema."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import (
    CAPABILITY_STATUS,
    CAS_STATUS,
    FORBIDDEN_STATUS_WORDS,
    IMPLEMENTATION_STATUS,
    ISSUE_CLASSIFICATION,
    ISSUE_SEVERITY,
    ISSUE_STATUS,
    REQUIRED_CAPABILITY_FIELDS,
    REQUIRED_CAPABILITY_IDS,
    REQUIRED_CORPUS_FIELDS,
    REQUIRED_ISSUE_FIELDS,
    VA_PRO_EQUIVALENT_STATUS,
)
from common import ROOT, load_schema, load_yaml, validate_evidence

REGISTRY = ROOT / "docs/capability/CAPABILITY_REGISTRY.yaml"
ISSUES = ROOT / "docs/review/KNOWN_ISSUES.yaml"
CORPUS = ROOT / "docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml"
GATES = ROOT / "tools/capability/campaign_gates.yaml"
SCHEMA = ROOT / "tools/capability/evidence_schema.json"


def _status_ok(value: object, allowed: set[str], field: str, errors: list[str]) -> None:
    if value not in allowed:
        errors.append(f"{field} has illegal status {value!r}")
    text = str(value).strip().lower()
    if text in FORBIDDEN_STATUS_WORDS:
        errors.append(f"{field} uses forbidden fuzzy status {value!r}")


def validate_registry() -> list[str]:
    errors: list[str] = []
    data = load_yaml(REGISTRY)
    capabilities = data.get("capabilities") or []
    ids = [item.get("id") for item in capabilities]
    if len(capabilities) != 14:
        errors.append(f"capability registry must contain 14 items, found {len(capabilities)}")
    if tuple(ids) != REQUIRED_CAPABILITY_IDS:
        errors.append(f"capability ids mismatch: {ids}")
    for item in capabilities:
        missing = [field for field in REQUIRED_CAPABILITY_FIELDS if field not in item]
        if missing:
            errors.append(f"{item.get('id')}: missing fields {missing}")
            continue
        _status_ok(item["implementation_status"], IMPLEMENTATION_STATUS, f"{item['id']}.implementation_status", errors)
        for field in (
            "static_status",
            "rd_api32_status",
            "api33_status",
            "api34_status",
            "api35_status",
            "api36_status",
            "oem_status",
            "commercial_app_status",
        ):
            _status_ok(item[field], CAPABILITY_STATUS, f"{item['id']}.{field}", errors)
        _status_ok(item["va_pro_equivalent"], VA_PRO_EQUIVALENT_STATUS, f"{item['id']}.va_pro_equivalent", errors)
        if item["va_pro_equivalent"] != "NOT_PROVEN":
            errors.append(f"{item['id']}: va_pro_equivalent must remain NOT_PROVEN")
        for api_field in ("api33_status", "api34_status", "api35_status", "api36_status"):
            if item[api_field] == "PASS":
                errors.append(f"{item['id']}.{api_field} cannot be PASS without a matrix run")
        if item["oem_status"] == "PASS":
            errors.append(f"{item['id']}.oem_status cannot be PASS without an OEM matrix")
        if item["id"] == "native_loader_jni_io" and item["implementation_status"] == "PASS":
            errors.append("native hostile/compatibility boundary cannot be implementation PASS")
        if not isinstance(item.get("evidence"), list) or not item["evidence"]:
            errors.append(f"{item['id']}: evidence must be a non-empty list")
        if not isinstance(item.get("known_gaps"), list) or not item["known_gaps"]:
            errors.append(f"{item['id']}: known_gaps must be a non-empty list")
    return errors


def validate_issues() -> list[str]:
    errors: list[str] = []
    data = load_yaml(ISSUES)
    issues = data.get("issues") or []
    ids: list[str] = []
    required_m10 = {
        "KI-M10-001",
        "KI-M10-002",
        "KI-M10-003",
        "KI-M10-004",
        "KI-M10-005",
        "KI-M10-006",
        "KI-M10-007",
    }
    for item in issues:
        missing = [field for field in REQUIRED_ISSUE_FIELDS if field not in item]
        if missing:
            errors.append(f"{item.get('issue_id')}: missing fields {missing}")
            continue
        issue_id = item["issue_id"]
        ids.append(issue_id)
        if item["classification"] not in ISSUE_CLASSIFICATION:
            errors.append(f"{issue_id}: illegal classification {item['classification']!r}")
        if item["severity"] not in ISSUE_SEVERITY:
            errors.append(f"{issue_id}: illegal severity {item['severity']!r}")
        if item["status"] not in ISSUE_STATUS:
            errors.append(f"{issue_id}: illegal status {item['status']!r}")
        if item["classification"] == "CURRENT_DEFECT" and str(item.get("discovered_by", "")).startswith("T57-M10"):
            errors.append(f"{issue_id}: M10 issues must not be auto-labeled CURRENT_DEFECT")
        if item["capability"] not in REQUIRED_CAPABILITY_IDS:
            errors.append(f"{issue_id}: unknown capability {item['capability']}")
    if len(ids) != len(set(ids)):
        errors.append("known issue ids are not unique")
    missing_m10 = required_m10 - set(ids)
    if missing_m10:
        errors.append(f"M10 issues missing from registry: {sorted(missing_m10)}")
    cxx = next((item for item in issues if item.get("issue_id") == "KI-M10-004"), None)
    if cxx is None or cxx.get("classification") != "EXPECTED_BEHAVIOR":
        errors.append("CXX5202 must remain EXPECTED_BEHAVIOR")
    return errors


def validate_corpus() -> list[str]:
    errors: list[str] = []
    data = load_yaml(CORPUS)
    entries = data.get("entries") or []
    ids = [item.get("id") for item in entries]
    if len(ids) != len(set(ids)):
        dupes = [key for key, count in Counter(ids).items() if count > 1]
        errors.append(f"VA Pro corpus ids are not unique: {dupes}")
    required_ids = {
        "VA-147", "VA-279", "VA-449", "VA-480", "VA-514", "VA-524", "VA-525",
        "VA-541", "VA-566", "VA-700", "VA-704", "VA-705",
        "VA-281", "VA-418", "VA-433", "VA-568", "VA-578",
        "VA-555", "VA-572", "VA-600", "VA-601", "VA-616", "VA-637", "VA-640",
        "VA-647", "VA-648", "VA-674", "VA-676", "VA-686", "VA-693", "VA-709",
        "VA-587", "VA-597", "VA-690", "VA-707",
        "VA-327", "VA-389", "VA-430", "VA-436", "VA-497", "VA-553", "VA-589",
        "VA-614", "VA-671", "VA-712", "VA-713",
        "VA-475", "VA-529", "VA-539", "VA-627",
        "VA-567", "VA-584", "VA-610", "VA-620", "VA-654", "VA-670",
        "VA-604", "VA-619", "VA-629", "VA-631", "VA-683",
        "VA-289", "VA-309", "VA-440", "VA-441", "VA-442", "VA-443", "VA-594",
        "VA-346", "VA-379", "VA-391", "VA-402", "VA-413", "VA-444", "VA-602",
    }
    missing = sorted(required_ids - set(ids))
    if missing:
        errors.append(f"required VA corpus ids missing: {missing}")
    categories = {item.get("category") for item in entries}
    required_categories = {
        "Activity", "Service", "PendingIntent", "PMS", "Manifest", "process",
        "Provider", "ClassLoader", "Native", "SystemService", "AndroidVersion",
        "OEM", "GMS", "WebView", "split APK", "process death", "package lifecycle",
    }
    missing_cats = sorted(required_categories - categories)
    if missing_cats:
        errors.append(f"required corpus categories missing: {missing_cats}")
    for item in entries:
        missing_fields = [field for field in REQUIRED_CORPUS_FIELDS if field not in item]
        if missing_fields:
            errors.append(f"{item.get('id')}: missing fields {missing_fields}")
            continue
        if item["cas_status"] not in CAS_STATUS:
            errors.append(f"{item['id']}: illegal cas_status {item['cas_status']!r}")
        if item["cas_mapping"] not in REQUIRED_CAPABILITY_IDS:
            errors.append(f"{item['id']}: unknown cas_mapping {item['cas_mapping']}")
    return errors


def validate_gates_and_schema() -> list[str]:
    errors: list[str] = []
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    if schema.get("$schema") is None or "required" not in schema:
        errors.append("evidence schema is incomplete")
    load_schema()
    sample = {
        "schema_version": 1,
        "campaign_id": "T57-R03-01",
        "capability": "activity_framework",
        "branch": "feature/t57-r03-va-pro-capability-campaign",
        "commit": "deadbeef",
        "tree": "cafebabe",
        "timestamp": "2026-08-17T00:00:00+00:00",
        "host_os": "Windows",
        "android_environment": "MuMu RD测试",
        "device_name": "sample",
        "adb_serial": "dynamic",
        "api_level": "32",
        "abi": "x86",
        "build_result": "UNVERIFIED",
        "static_result": "UNVERIFIED",
        "targeted_result": "PASS",
        "rd_result": "UNVERIFIED",
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": [],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
    }
    errors.extend(validate_evidence(sample))
    gates = load_yaml(GATES).get("gates") or []
    if not gates:
        errors.append("campaign gate catalog is empty")
    gate_ids = [item.get("id") for item in gates]
    if len(gate_ids) != len(set(gate_ids)):
        errors.append("campaign gate ids are not unique")
    for gate in gates:
        command = gate.get("command") or []
        if len(command) < 2:
            errors.append(f"{gate.get('id')}: invalid command")
            continue
        script = ROOT / command[1]
        if command[0] == "python" and not script.is_file():
            errors.append(f"{gate.get('id')}: missing script {command[1]}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    errors: list[str] = []
    for name, fn in (
        ("registry", validate_registry),
        ("issues", validate_issues),
        ("corpus", validate_corpus),
        ("schema", validate_gates_and_schema),
    ):
        try:
            found = fn()
        except Exception as exc:  # noqa: BLE001 - validator must surface parse errors
            found = [f"{name} parse failed: {exc}"]
        errors.extend(found)
    if errors:
        print("FAIL capability campaign infrastructure validation")
        for error in errors:
            print(" - " + error)
        return 1
    print("PASS capability campaign infrastructure validation")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
