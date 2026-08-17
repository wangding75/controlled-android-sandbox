#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def require(rel: str, *tokens: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    text = path.read_text(encoding="utf-8-sig")
    for token in tokens:
        if token not in text:
            errors.append(f"{rel} missing: {token}")
    return text

old_handler = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationHandler.java"
old_adapter = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationAdapter.java"
if old_handler.exists() or old_adapter.exists():
    errors.append("internal Bundle boundary remains in exported runtime.protocol package")

handler = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerOperationHandler.java",
    "interface RuntimeBrokerOperationHandler",
    "Bundle prepareGuest(Bundle request)",
)
adapter = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerOperationAdapter.java",
    "final class RuntimeBrokerOperationAdapter",
    "static RuntimeOperationResult execute(",
    "RuntimeOperationTransport.fromLegacy",
)
if "public interface RuntimeBrokerOperationHandler" in handler:
    errors.append("RuntimeBrokerOperationHandler remains public")
if "public final class RuntimeBrokerOperationAdapter" in adapter:
    errors.append("RuntimeBrokerOperationAdapter remains public")
if "public static RuntimeOperationResult execute" in adapter:
    errors.append("RuntimeBrokerOperationAdapter.execute remains public")

service = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "implements RuntimeBrokerOperationHandler",
    "RuntimeBrokerOperationAdapter.execute(RuntimeBrokerService.this, request)",
)
for forbidden in (
        "import com.warden.controlledsandbox.runtime.protocol.RuntimeBrokerOperationHandler",
        "import com.warden.controlledsandbox.runtime.protocol.RuntimeBrokerOperationAdapter"):
    if forbidden in service:
        errors.append(f"RuntimeBrokerService retains exported protocol import: {forbidden}")

for rel in (
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl",
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl"):
    aidl = require(rel, "RuntimeOperationResult executeV2")
    if "android.os.Bundle" in aidl or "Bundle " in aidl:
        errors.append(f"public AIDL contains Bundle payload: {rel}")

require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerOperationBoundarySelfTest.java",
    "new FakeHandler()",
    "RuntimeBrokerOperationAdapter.execute",
    "implements RuntimeBrokerOperationHandler",
)
runner = require(
    "tools/static_android_compile.py",
    "com.warden.controlledsandbox.runtime.broker.RuntimeBrokerOperationBoundarySelfTest",
)
if runner.count("'com.warden.controlledsandbox.runtime.broker.RuntimeBrokerOperationBoundarySelfTest'") != 1:
    errors.append("RuntimeBrokerOperationBoundarySelfTest must execute exactly once")

receipt = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
if not receipt.is_file():
    errors.append("static Android execution receipt missing")
else:
    executed = set(json.loads(receipt.read_text(encoding="utf-8")).get("executedTests", []))
    if "com.warden.controlledsandbox.runtime.broker.RuntimeBrokerOperationBoundarySelfTest" not in executed:
        errors.append("current execution receipt omits RuntimeBrokerOperationBoundarySelfTest")

classes = ROOT / "build/static-android-compile/classes"
if not classes.is_dir():
    errors.append("static Android class output missing")
else:
    with tempfile.TemporaryDirectory(prefix="bundle-boundary-") as tmp:
        source = Path(tmp) / "ExternalBundleBoundaryProbe.java"
        source.write_text(
            "package external.probe;\n"
            "import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerOperationHandler;\n"
            "public final class ExternalBundleBoundaryProbe {\n"
            "  RuntimeBrokerOperationHandler handler;\n"
            "}\n",
            encoding="utf-8")
        result = subprocess.run(
            ["javac", "--release", "17", "-cp", str(classes), str(source)],
            encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode == 0:
            errors.append("external package can compile against internal Bundle handler")

report = {
    "task": "M5-T19.1-P",
    "finding": "P2-09 internal Bundle Handler was public",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "handlerVisibility": "package-private",
    "adapterVisibility": "package-private",
    "externalCompilationDenied": not any("external package" in e for e in errors),
    "publicBundleAidlMethods": 0,
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-p-internal-bundle-boundary.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-P internal Bundle boundary", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-P Bundle handler and adapter are package-private runtime internals")
