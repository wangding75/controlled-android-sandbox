#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "8ecb7d6"
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


require("docs/plans/M5_T19_ARCHITECTURE_DECOUPLING.md",
        "Remove all legacy Bundle methods", "critical-path regression ownership",
        "device evidence count 0")
require("docs/M5_T19_DEVELOPMENT_REPORT.md",
        "Source status: PASS", "Production status: PARTIAL", "Device evidence: 0",
        "Legacy Bundle AIDL declarations", "Production classes above 500 lines")
require("docs/comparisons/M5_T19_VA_NBB_COMPARISON.md",
        "VirtualApp", "NewBlackbox", "real-device evidence gap")
require("README.md", "## M5-T19 architecture-decoupling source baseline")
require("docs/ROADMAP.md", "## M5-T19 architecture decoupling baseline")
require("docs/TEST_REPORT.md", "## M5-T19 source-quality verification")

for rel in (
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl",
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl",
):
    aidl = require(rel, "RuntimeOperationResult executeV2")
    if re.search(r"^\s*Bundle\s+\w+\s*\(", aidl, re.MULTILINE):
        errors.append(f"legacy Bundle AIDL method remains in {rel}")
    if "import android.os.Bundle" in aidl:
        errors.append(f"legacy Bundle AIDL import remains in {rel}")

required_extractions = {
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceDependencies.java": "deleteScopeBestEffort",
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java": "extends IPackageManagementSession.Stub",
    "app/src/main/java/com/warden/controlledsandbox/PackageRuntimePermissionSession.java": "extends IRuntimePermissionSession.Stub",
    "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java": "extends IVirtualSystemServiceSession.Stub",
    "app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java": "final class PackageProfileAuthority",
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceRecords.java": "final class VirtualSystemServiceRecords",
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskCheckpointCoordinator.java": "final class ActivityTaskCheckpointCoordinator",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java": "final class RuntimeGuestConnectionPool",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeBrokerOperationHandler.java": "interface RuntimeBrokerOperationHandler",
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationValues.java": "final class ApplicationEnvironmentInvocationValues",
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentUsageValues.java": "final class ApplicationEnvironmentUsageValues",
}
for rel, token in required_extractions.items():
    require(rel, token)

line_limits = {
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java": 200,
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java": 500,
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java": 1500,
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java": 1550,
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java": 1300,
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentInvocationInterceptor.java": 500,
}
for rel, maximum in line_limits.items():
    count = len(text(rel).splitlines())
    if count > maximum:
        errors.append(f"{rel} has {count} lines; limit is {maximum}")

matcher = require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcher.java",
    "static boolean named", "static boolean startsWith", "static boolean containsAny")
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcherSelfTest.java",
    "inverse operation names must not match exactly",
    "inverse operation prefixes must not overlap")
method_pattern = re.compile(r"method(?:Name)?\.(?:contains|startsWith|endsWith)\(")
for root in ("app/src/main/java", "sandbox-runtime/src/main/java", "sandbox-framework/src/main/java"):
    for path in (ROOT / root).rglob("*.java"):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if method_pattern.search(line):
                errors.append(f"direct method-name routing remains: {path.relative_to(ROOT)}:{number}")

require("scripts/check-critical-test-ownership.py",
        "lineCoverageClaimed", "branchCoverageClaimed", "critical source test ownership")
try:
    ownership = json.loads(text("build/verification/m5-t19-critical-test-ownership.json"))
    if ownership.get("ownerCount") != 12 or ownership.get("mappedOwnerCount") != 12:
        errors.append("M5-T19 must map all twelve critical source owners")
    if ownership.get("lineCoverageClaimed") is not False or ownership.get("branchCoverageClaimed") is not False:
        errors.append("critical ownership gate must not claim line/branch coverage")
except Exception as exc:
    errors.append(f"invalid M5-T19 ownership report: {exc}")

try:
    audit = json.loads(text("verification/m5-t19-architecture-decoupling-audit.json"))
    if audit.get("sourceStatus") != "PASS" or audit.get("productionStatus") != "PARTIAL":
        errors.append("invalid M5-T19 source/production status")
    if audit.get("deviceEvidenceCount") != 0:
        errors.append("M5-T19 invents device evidence")
    if audit.get("typeDebt", {}).get("legacyBundleAidlDeclarations") != 0:
        errors.append("M5-T19 must eliminate legacy Bundle AIDL declarations")
    if audit.get("architecture", {}).get("largeProductionClassesOver500") > 12:
        errors.append("M5-T19 must reduce large production classes to at most twelve")
    if audit.get("methodRouting", {}).get("directSubstringRoutingCount") != 0:
        errors.append("M5-T19 direct method-name routing count must be zero")
    if audit.get("testGovernance", {}).get("jacocoExecuted") is not False:
        errors.append("M5-T19 must not fabricate JaCoCo execution")
    if audit.get("referenceFilesModified") != 0:
        errors.append("M5-T19 modifies ref/upstream")
except Exception as exc:
    errors.append(f"invalid M5-T19 architecture audit: {exc}")

try:
    preflight = json.loads(text("verification/m5-t19-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS" or preflight.get("productionStatus") != "PARTIAL":
        errors.append("invalid M5-T19 source preflight status")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T19 preflight invents device evidence")
    evidence = preflight.get("evidence", {})
    if evidence.get("legacyBundleAidlDeclarations") != 0:
        errors.append("M5-T19 preflight must record zero legacy Bundle declarations")
    if evidence.get("largeProductionClassesOver500") > 12:
        errors.append("M5-T19 preflight large-class count is too high")
    if evidence.get("mappedCriticalTestOwners") != 12:
        errors.append("M5-T19 preflight test ownership is incomplete")
except Exception as exc:
    errors.append(f"invalid M5-T19 source preflight: {exc}")

app_gradle = require("app/build.gradle", "versionCode 19", "versionName '0.5.19-source'")
runner = text("tools/static_android_compile.py")
if runner.count("InvocationMethodMatcherSelfTest") != 1:
    errors.append("static Android compiler must execute InvocationMethodMatcherSelfTest exactly once")

changed = subprocess.run(
    ["git", "diff", "--name-only", BASE, "--", "ref/upstream"],
    cwd=ROOT, text=True, capture_output=True, check=True,
).stdout.strip()
if changed:
    errors.append("M5-T19 modifies ref/upstream")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T19 must preserve 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("frozen source counters changed")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("frozen production counters changed")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T19 invented device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

verify = text("scripts/verify-all.sh")
for gate in ("check-critical-test-ownership.py", "check-m5-t19-architecture-decoupling.py"):
    if gate not in verify:
        errors.append(f"verify-all.sh missing {gate}")

if errors:
    print("FAIL M5-T19 architecture-decoupling checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19 architecture decoupling, type-debt elimination and test-ownership gate")
