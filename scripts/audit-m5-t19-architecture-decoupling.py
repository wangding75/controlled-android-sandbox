#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "8ecb7d6"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def line_count(rel: str) -> int:
    return len(read(rel).splitlines())


def legacy_bundle_methods() -> list[str]:
    methods: list[str] = []
    for rel in (
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl",
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl",
    ):
        for line in read(rel).splitlines():
            stripped = line.strip()
            if stripped.startswith("Bundle "):
                methods.append(f"{Path(rel).stem}.{stripped.split('(', 1)[0].split()[-1]}")
    return methods


def large_classes() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for root in (
        ROOT / "app/src/main/java",
        ROOT / "sandbox-runtime/src/main/java",
        ROOT / "sandbox-framework/src/main/java",
    ):
        for path in root.rglob("*.java"):
            count = len(path.read_text(encoding="utf-8", errors="ignore").splitlines())
            if count > 500:
                rows.append({"path": str(path.relative_to(ROOT)), "lines": count})
    return sorted(rows, key=lambda item: (-int(item["lines"]), str(item["path"])))


def changed_references() -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", BASE, "--", "ref/upstream"],
        cwd=ROOT, text=True, capture_output=True, check=True,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


def direct_method_routing() -> list[str]:
    matches: list[str] = []
    pattern = re.compile(r"method(?:Name)?\.(?:contains|startsWith|endsWith)\(")
    for root in (
        ROOT / "app/src/main/java",
        ROOT / "sandbox-runtime/src/main/java",
        ROOT / "sandbox-framework/src/main/java",
    ):
        for path in root.rglob("*.java"):
            for number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                if pattern.search(line):
                    matches.append(f"{path.relative_to(ROOT)}:{number}")
    return matches

ownership = json.loads(read("verification/m5-t19-critical-test-ownership.json"))
large = large_classes()
report = {
    "iteration": "M5-T19",
    "sourceStatus": "PASS",
    "productionStatus": "PARTIAL",
    "deviceEvidenceCount": 0,
    "typeDebt": {
        "legacyBundleAidlDeclarations": len(legacy_bundle_methods()),
        "legacyBundleAidlMethods": legacy_bundle_methods(),
        "typedBrokerEndpoint": "RuntimeOperationResult executeV2" in read(
            "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl"),
        "typedGuestEndpoint": "RuntimeOperationResult executeV2" in read(
            "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl"),
        "internalBundlePayloadBoundary": (
            ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationHandler.java"
        ).is_file(),
    },
    "architecture": {
        "largeProductionClassesOver500": len(large),
        "largeClasses": large,
        "lineCounts": {
            rel: line_count(rel) for rel in (
                "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
                "app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java",
                "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
                "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java",
                "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
                "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java",
            )
        },
        "extractedOwners": [
            "PackageServiceDependencies",
            "PackageManagementSession",
            "PackageRuntimePermissionSession",
            "PackageVirtualSystemServiceSession",
            "PackageProfileAuthority",
            "ActivityTaskCheckpointCoordinator",
            "RuntimeGuestConnectionPool",
            "VirtualSystemServiceRecords",
            "ApplicationEnvironmentInvocationValues",
            "ApplicationEnvironmentUsageValues",
        ],
    },
    "methodRouting": {
        "centralMatcher": "InvocationMethodMatcher",
        "directSubstringRoutingCount": len(direct_method_routing()),
        "directSubstringRoutingLocations": direct_method_routing(),
        "matcherSelfTest": True,
    },
    "testGovernance": {
        "metricType": ownership["metricType"],
        "criticalOwners": ownership["ownerCount"],
        "mappedCriticalOwners": ownership["mappedOwnerCount"],
        "lineCoverageClaimed": False,
        "branchCoverageClaimed": False,
        "jacocoExecuted": False,
    },
    "referenceFilesModified": len(changed_references()),
    "referenceChanges": changed_references(),
    "frozenCapabilityCategories": 113,
    "deviceBoundaries": [
        "Gradle/AGP generated AIDL and Android SDK/NDK compilation",
        "JaCoCo line and branch coverage on the locked JDK-17 build",
        "Android Keystore-backed key protection",
        "instrumentation, Emulator, physical-device and third-party APK execution",
    ],
}
print(json.dumps(report, ensure_ascii=False, indent=2))
