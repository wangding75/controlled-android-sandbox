#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")


script = read("scripts/check-critical-test-ownership.py")
for token in (
    "strip_comments_and_literals",
    "reachable_reference",
    "runtimeExecutionReceipt",
    "inputDigestSha256",
    "--self-test",
    "P1_REGRESSIONS",
):
    if token not in script:
        errors.append(f"critical ownership gate missing {token}")

try:
    report = json.loads(read("build/verification/m5-t19-critical-test-ownership.json"))
    if report.get("generationMode") != "live source reachability and current execution receipt":
        errors.append("ownership report is not generated from the live source tree")
    owner_count = report.get("ownerCount", 0)
    if owner_count < 12:
        errors.append("ownership report must retain at least the original twelve critical owners")
    for field in ("mappedOwnerCount", "directOwnerCount", "executedOwnerCount"):
        if report.get(field) != owner_count:
            errors.append(f"ownership report {field} must equal ownerCount")
    owners = report.get("owners", [])
    if len(owners) != owner_count or any(item.get("status") != "PASS" for item in owners):
        errors.append("every critical owner must have a direct executable test")
    required = {
        "PackageManagementSession",
        "PackageRuntimePermissionSession",
        "PackageVirtualSystemServiceSession",
        "RuntimeGuestConnectionPool",
        "RebindableServiceConnector",
    }
    actual = {item.get("owner") for item in owners}
    if not required.issubset(actual):
        errors.append("package sessions, Guest pool and connector direct ownership are incomplete")
    regressions = report.get("p1RegressionEvidence", [])
    if [item.get("issue") for item in regressions] != [f"P1-0{i}" for i in range(1, 7)]:
        errors.append("P1 regression evidence must cover P1-01 through P1-06 in order")
    if any(item.get("status") != "PASS" for item in regressions):
        errors.append("P1 regression evidence is incomplete")
    digest = report.get("inputDigestSha256", "")
    if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        errors.append("ownership input digest is invalid")
except Exception as exc:
    errors.append(f"invalid ownership report: {exc}")

runner = read("tools/static_android_compile.py")
for name in ("PackageSessionDirectOwnershipSelfTest", "RuntimeGuestConnectionPoolSelfTest"):
    if runner.count(name) != 1:
        errors.append(f"static Android runner must execute {name} exactly once")

verify = read("scripts/verify-all.sh")
for command in (
    "python3 scripts/check-critical-test-ownership.py --self-test",
    "python3 scripts/check-critical-test-ownership.py",
    "python3 scripts/check-m5-t19-1-f-critical-test-ownership.py",
):
    if command not in verify:
        errors.append(f"verify-all.sh missing: {command}")

if errors:
    print("FAIL M5-T19.1-F critical test ownership", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-F direct executable critical-test ownership")
