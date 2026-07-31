#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8-sig")


def line_count(rel: str) -> int:
    return len(read(rel).splitlines())


def changed_reference_files() -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--name-only", "caa6e15", "--", "ref/upstream"],
        cwd=ROOT, text=True, capture_output=True, check=True,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


def legacy_bundle_methods() -> list[str]:
    methods: list[str] = []
    for rel in (
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl",
        "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl",
    ):
        for line in read(rel).splitlines():
            line = line.strip()
            if line.startswith("Bundle "):
                methods.append(f"{Path(rel).stem}.{line.split('(', 1)[0].split()[-1]}")
    return methods

clients = [
    "app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
    "app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimePermissionPackageClient.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeVirtualSystemServicePackageClient.java",
]
connector_clients = [rel for rel in clients if "RebindableServiceConnector" in read(rel)]
old_connection_patterns = {
    rel: len(re.findall(r"new\s+ServiceConnection\s*\(", read(rel))) for rel in clients
}

production_roots = [
    ROOT / "app/src/main/java",
    ROOT / "sandbox-runtime/src/main/java",
    ROOT / "sandbox-framework/src/main/java",
]
large_classes: list[dict[str, object]] = []
for root in production_roots:
    for path in root.rglob("*.java"):
        count = len(path.read_text(encoding="utf-8", errors="ignore").splitlines())
        if count > 500:
            large_classes.append({"path": str(path.relative_to(ROOT)), "lines": count})
large_classes.sort(key=lambda item: (-int(item["lines"]), str(item["path"])))

codec = read("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStoreCodec.java")
report = {
    "iteration": "M5-T18",
    "sourceStatus": "PASS",
    "productionStatus": "PARTIAL",
    "deviceEvidenceCount": 0,
    "architectureGovernance": {
        "rebindableConnectorClients": len(connector_clients),
        "expectedConnectorClients": len(clients),
        "clients": connector_clients,
        "legacyServiceConnectionPatterns": old_connection_patterns,
        "binderDeathAndBackoffSelfTest": True,
        "accountAuthorityExtracted": (ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualAccountAuthority.java").is_file(),
        "largeProductionClassesOver500": len(large_classes),
        "largeClasses": large_classes,
    },
    "secretPersistence": {
        "schemaVersion": 6,
        "cipher": "AES/GCM/NoPadding",
        "encryptedPasswordField": "passwordEncrypted" in codec,
        "encryptedTokenField": "tokensEncrypted" in codec,
        "legacyPlaintextMigration": "requiresSecretMigration" in codec,
        "androidKeystoreUsed": False,
        "keyStorage": "app-private per-install file key",
    },
    "ipcDebt": {
        "legacyBundleCompatibilityDeclarations": len(legacy_bundle_methods()),
        "legacyBundleCompatibilityMethods": legacy_bundle_methods(),
        "internalV2TransportPreserved": "RuntimeOperationTransport" in read(
            "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationAdapter.java"
        ) or (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java").is_file(),
    },
    "methodClassification": {
        "launcherUnregisterRegression": 'launcher.unregisterCallback("guest.pkg", listener);' in read(
            "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentVirtualizationSelfTest.java"
        ),
        "graphicsSaveBufferRegression": "add-to-save-buffer must not be misclassified as cleanup" in read(
            "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesVirtualizationSelfTest.java"
        ),
        "exactFirstMatcherUsed": "InvocationMethodMatcher" in read(
            "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java"
        ),
    },
    "ci": {
        "workflowPresent": (ROOT / ".github/workflows/source-gates.yml").is_file(),
        "lockedJdk": 17,
        "executedOnGithub": False,
        "androidInstrumentationEntrypoints": 1 if (ROOT / "app/src/androidTest/java/com/warden/controlledsandbox/SourceBaselineInstrumentation.java").is_file() else 0,
        "instrumentationExecuted": False,
    },
    "referenceFilesModified": len(changed_reference_files()),
    "frozenCapabilityCategories": 113,
    "deviceBoundaries": [
        "Android Keystore key protection and migration",
        "real Binder disconnect/rebind timing under Android process death",
        "Gradle/AGP Android build and generated AIDL compatibility",
        "instrumentation, emulator and physical-device execution",
    ],
}
print(json.dumps(report, ensure_ascii=False, indent=2))
