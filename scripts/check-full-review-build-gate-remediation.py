#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")

framework_gradle = text("sandbox-framework/build.gradle")
if "api project(':sandbox-contract')" not in framework_gradle:
    errors.append("sandbox-framework must declare its public sandbox-contract dependency")

runner = text("tools/static_android_compile.py")
for token in (
    "compile_module('sandbox-framework'",
    "compile_module('sandbox-runtime'",
    "compile_module('app'",
    "STATIC_ANDROID_TEST_TIMEOUT_SECONDS",
    "STATIC_ANDROID_STAGE_TIMEOUT_SECONDS",
    "start_new_session",
    "terminate_process",
    "'status': 'TIMEOUT'",
    "write_execution_receipt(False)",
):
    if token not in runner:
        errors.append(f"static Android gate missing {token}")
if "sources=[]" in runner or "PASS static Android-source compilation with local API stubs" in runner:
    errors.append("legacy all-modules-in-one-javac implementation remains")

module_receipt_path = ROOT / "build/static-android-compile/verification/static-android-module-compilation.json"
test_receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
try:
    module_receipt = json.loads(module_receipt_path.read_text(encoding="utf-8"))
    modules = [item.get("module") for item in module_receipt.get("modules", [])]
    expected = ["foundation", "sandbox-framework", "sandbox-native", "sandbox-runtime",
                "app", "sandbox-companion32", "fixture-basic", "test-harness"]
    if module_receipt.get("completed") is not True or modules != expected:
        errors.append(f"module-faithful receipt is incomplete: {modules}")
except Exception as exc:
    errors.append(f"invalid module compile receipt: {exc}")
try:
    test_receipt = json.loads(test_receipt_path.read_text(encoding="utf-8"))
    if test_receipt.get("completed") is not True or test_receipt.get("executedTestCount", 0) < 111:
        errors.append("static Android test receipt is incomplete")
    statuses = [item.get("status") for item in test_receipt.get("events", [])]
    if statuses.count("START") != test_receipt.get("executedTestCount"):
        errors.append("each test must have a START event")
    if statuses.count("PASS") != test_receipt.get("executedTestCount"):
        errors.append("each completed test must have a PASS event")
except Exception as exc:
    errors.append(f"invalid static Android test receipt: {exc}")

verify = text("scripts/verify-all.sh")
if "python3 scripts/check-full-review-build-gate-remediation.py" not in verify:
    errors.append("verify-all.sh does not enforce the full-review build gate")

if errors:
    print("FAIL full-review module and timeout gate", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS full-review module-faithful compile and bounded self-test gate")
