#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")


def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require("docs/plans/M5_T18_ARCHITECTURE_QUALITY.md",
        "Binder reconnection", "AES-GCM", "legacy Bundle", "Android Keystore",
        "Android/device evidence remains zero")
require("docs/M5_T18_DEVELOPMENT_REPORT.md",
        "Source status: PASS", "Production status: PARTIAL", "Device evidence: 0",
        "fourteen production Java classes")
require("docs/comparisons/M5_T18_VA_NBB_COMPARISON.md",
        "VirtualApp", "NewBlackbox", "device evidence remains 0")
require("README.md", "## M5-T18 architecture-quality source baseline")
require("docs/ROADMAP.md", "## M5-T18 architecture-quality baseline")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnector.java",
        "interface BinderAdapter", "linkToDeath", "onBindingDied", "retryDelayMs",
        "DEFAULT_MAX_RETRY_MS")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
        "next request must rebind after death", "connector must retry a rejected first binding",
        "close must release adapted session")
for rel in (
    "app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
    "app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimePermissionPackageClient.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeVirtualSystemServicePackageClient.java",
):
    require(rel, "RebindableServiceConnector")
    if "new ServiceConnection(" in text(rel):
        errors.append(f"legacy one-shot ServiceConnection remains in {rel}")

require("app/src/main/java/com/warden/controlledsandbox/VirtualSecretCipher.java",
        "AES/GCM/NoPadding", "aesgcm:v1:", "GCMParameterSpec", "ATOMIC_MOVE")
require("app/src/main/java/com/warden/controlledsandbox/VirtualAccountAuthority.java",
        "snapshots", "setPassword", "setToken", "invalidateToken")
codec = require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStoreCodec.java",
                "passwordEncrypted", "tokensEncrypted", "requiresSecretMigration")
if '.put("password",' in codec or '.put("tokens",' in codec:
    errors.append("schema 6 codec persists plaintext account secrets")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "VirtualSecretCipher", "VirtualAccountAuthority", "VirtualSystemServiceLimits")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceLimits.java",
        "SCHEMA = 6", "MAX_PAYLOAD_BYTES", "MAX_JOBS_PER_SCOPE")
require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
        "schema 6 must not persist plaintext secret fields",
        "legacy account secrets must be rewritten to encrypted schema immediately",
        "encrypted store without its installation key must fail closed")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java",
        "InvocationMethodMatcher", 'named(name, "unregisterCallback"', 'named(name, "registerCallback"')
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentVirtualizationSelfTest.java",
        'launcher.unregisterCallback("guest.pkg", listener)')
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesInvocationInterceptor.java",
        'containsAny(name, "addtosavebuffer"')
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesVirtualizationSelfTest.java",
        "add-to-save-buffer must not be misclassified as cleanup")

require(".github/workflows/source-gates.yml", "actions/setup-java@v4", "java-version: '17'",
        "./scripts/verify-all.sh", "actions/upload-artifact@v4")
app_gradle = require("app/build.gradle",
        "testInstrumentationRunner 'com.warden.controlledsandbox.SourceBaselineInstrumentation'",
        "versionCode rootProject.ext.controlledVersionCode",
        "versionName rootProject.ext.controlledVersionName")
release_identity = require(
        "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ControlledReleaseIdentity.java",
        "VERSION_CODE", "VERSION_NAME")
version_code_match = __import__("re").search(r"VERSION_CODE\s*=\s*(\d+)", release_identity)
if not version_code_match or int(version_code_match.group(1)) < 18:
    errors.append("shared release versionCode must preserve or advance the M5-T18 baseline")
require("app/src/androidTest/java/com/warden/controlledsandbox/SourceBaselineInstrumentation.java",
        "extends Instrumentation", "SOURCE_BASELINE_INSTRUMENTATION_READY", "getTargetContext()")

runner = text("tools/static_android_compile.py")
if runner.count("'com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnectorSelfTest'") != 1:
    errors.append("static Android compiler must execute RebindableServiceConnectorSelfTest exactly once")

try:
    audit = json.loads(text("verification/m5-t18-architecture-quality-audit.json"))
    architecture = audit.get("architectureGovernance", {})
    secrets = audit.get("secretPersistence", {})
    ipc = audit.get("ipcDebt", {})
    classification = audit.get("methodClassification", {})
    ci = audit.get("ci", {})
    if audit.get("sourceStatus") != "PASS" or audit.get("productionStatus") != "PARTIAL":
        errors.append("invalid M5-T18 source/production status")
    if audit.get("deviceEvidenceCount") != 0:
        errors.append("M5-T18 must not invent device evidence")
    if architecture.get("rebindableConnectorClients") != 5:
        errors.append("M5-T18 must migrate five Binder clients")
    if any(architecture.get("legacyServiceConnectionPatterns", {}).values()):
        errors.append("M5-T18 migrated clients still contain legacy one-shot connections")
    if architecture.get("largeProductionClassesOver500") != 14:
        errors.append("M5-T18 must honestly record fourteen remaining large production classes")
    if secrets.get("schemaVersion") != 6 or secrets.get("cipher") != "AES/GCM/NoPadding":
        errors.append("M5-T18 encrypted secret schema evidence is invalid")
    if secrets.get("androidKeystoreUsed") is not False:
        errors.append("M5-T18 must not claim Android Keystore evidence")
    if ipc.get("legacyBundleCompatibilityDeclarations") != 12:
        errors.append("M5-T18 must preserve and disclose twelve legacy Bundle compatibility methods")
    if not all(classification.get(key) for key in (
            "launcherUnregisterRegression", "graphicsSaveBufferRegression", "exactFirstMatcherUsed")):
        errors.append("M5-T18 method-classification regression evidence is incomplete")
    if ci.get("workflowPresent") is not True or ci.get("executedOnGithub") is not False:
        errors.append("M5-T18 CI evidence must distinguish definition from execution")
    if ci.get("androidInstrumentationEntrypoints") != 1 or ci.get("instrumentationExecuted") is not False:
        errors.append("M5-T18 instrumentation evidence must distinguish source entry from execution")
    if audit.get("referenceFilesModified") != 0:
        errors.append("M5-T18 modifies ref/upstream")
except Exception as exc:
    errors.append(f"invalid M5-T18 architecture audit: {exc}")

try:
    preflight = json.loads(text("verification/m5-t18-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS" or preflight.get("productionStatus") != "PARTIAL":
        errors.append("invalid M5-T18 source preflight status")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T18 preflight invents device evidence")
    evidence = preflight.get("evidence", {})
    if evidence.get("rebindableConnectorClients") != 5:
        errors.append("M5-T18 preflight must record five migrated connectors")
    if evidence.get("legacyBundleCompatibilityDeclarations") != 12:
        errors.append("M5-T18 preflight must disclose twelve legacy Bundle methods")
    if evidence.get("referenceFilesModified") != 0:
        errors.append("M5-T18 preflight must record zero reference changes")
    if evidence.get("androidInstrumentationEntrypoints") != 1:
        errors.append("M5-T18 preflight must record one instrumentation entry")
except Exception as exc:
    errors.append(f"invalid M5-T18 source preflight: {exc}")

changed = subprocess.run(
    ["git", "diff", "--name-only", "caa6e15", "--", "ref/upstream"],
    cwd=ROOT, text=True, capture_output=True, check=True).stdout.strip()
if changed:
    errors.append("M5-T18 modifies ref/upstream")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T18 must preserve 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("frozen source counters changed")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("frozen production counters changed")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T18 invented device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t18-architecture-quality.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T18 gate")

if errors:
    print("FAIL M5-T18 architecture-quality checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T18 architecture-quality and release-source governance gate")
