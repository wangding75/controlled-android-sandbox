#!/usr/bin/env python3
"""Verify that architecture-critical production owners have direct Host regression owners.

This is a source ownership gate, not a line-coverage claim. JaCoCo and Android
instrumentation remain device/build-stage evidence.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

OWNERS = {
    "PackageManagementService": [
        "app/src/testHarness/java/com/warden/controlledsandbox/PackageManagementAuthorizationSelfTest.java",
        "app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java",
    ],
    "PackageManagementSession": [
        "app/src/testHarness/java/com/warden/controlledsandbox/PackageManagementAuthorizationSelfTest.java",
    ],
    "VirtualSystemServiceStore": [
        "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    ],
    "ActivityTaskLedger": [
        "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java",
        "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerConcurrencySelfTest.java",
    ],
    "ActivityTaskCheckpointCoordinator": [
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStoreSelfTest.java",
        "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java",
    ],
    "RuntimeBrokerService": [
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/BrokerStateStoreSelfTest.java",
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/BrokerArchitecturePortsSelfTest.java",
    ],
    "RuntimeGuestConnectionPool": [
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/BrokerStateStoreSelfTest.java",
    ],
    "RuntimeOperationTransport": [
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransportSelfTest.java",
    ],
    "InvocationMethodMatcher": [
        "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcherSelfTest.java",
    ],
    "ApplicationEnvironmentInvocationInterceptor": [
        "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentVirtualizationSelfTest.java",
    ],
    "VirtualSecretCipher": [
        "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
    ],
    "RebindableServiceConnector": [
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
    ],
}

missing: list[str] = []
records: list[dict[str, object]] = []
for owner, tests in OWNERS.items():
    present = []
    for rel in tests:
        path = ROOT / rel
        if path.is_file():
            present.append(rel)
        else:
            missing.append(f"{owner}: missing test owner {rel}")
    records.append({"owner": owner, "tests": present, "expectedTests": tests})

runner = (ROOT / "tools/static_android_compile.py").read_text(encoding="utf-8")
for record in records:
    for rel in record["tests"]:
        class_name = Path(rel).stem
        if class_name not in runner:
            missing.append(f"{record['owner']}: {class_name} is not executed by static_android_compile.py")

report = {
    "gate": "critical-source-test-ownership",
    "metricType": "explicit critical-path regression ownership",
    "lineCoverageClaimed": False,
    "branchCoverageClaimed": False,
    "owners": records,
    "ownerCount": len(records),
    "mappedOwnerCount": sum(1 for record in records if record["tests"]),
}
output = ROOT / "verification/m5-t19-critical-test-ownership.json"
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

if missing:
    print("FAIL critical source test ownership", file=sys.stderr)
    for item in missing:
        print(" - " + item, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS critical source test ownership ({len(records)}/{len(records)} owners mapped; no coverage percentage claimed)")
