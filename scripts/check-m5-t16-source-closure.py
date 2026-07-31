#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def require(rel: str, *tokens: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    value = path.read_text(encoding="utf-8-sig")
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value

require("docs/plans/M5_T16_SOURCE_CLOSURE.md", "typed V2 Runtime", "exact-first", "RestrictionsManager")
require("docs/M5_T16_DEVELOPMENT_REPORT.md", "Source status: PASS", "Production status: PARTIAL", "Device evidence: 0")
require("docs/comparisons/M5_T16_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox", "Remaining source gap")
require("README.md", "## M5-T16 global source-closure baseline")
require("docs/ROADMAP.md", "## M5-T16 global source-closure baseline")

for name in ["RuntimeOperationRequest", "RuntimeOperationResult"]:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl", f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java", "Parcelable", "protocolVersion", "requestId", "operation")

for rel in [
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl",
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IGuestProcess.aidl",
]:
    require(rel, "RuntimeOperationResult executeV2(in RuntimeOperationRequest request);")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java",
        "executeV2(request)", "RUNTIME_OPERATION_CORRELATION_MISMATCH")
for rel in [
    "app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
    "app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/RouteBrokerClient.java",
]:
    require(rel, "RuntimeOperationTransport")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcher.java", "Exact-first", "named(", "startsWith(")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/DeviceServiceVirtualizationSelfTest.java", 'activeCount("sensor") == 0')
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/ApplicationEnvironmentVirtualizationSelfTest.java",
        "unregister content observer removes", "application restriction projection", "restrictions mutation denied")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/RestrictionsManagerServiceHook.java",
        '"restrictions"', "IRestrictionsManager")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java", 'attempt("restrictions"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/ApplicationEnvironmentProxyReadiness.java", '"restrictions"')

subprocess.run([sys.executable, str(ROOT / "scripts/audit-m5-t16-source-closure.py")], cwd=ROOT, check=True)
audit = json.loads((ROOT / "verification/m5-t16-source-closure-audit.json").read_text())
if audit["typedRuntimeTransport"]["internalLegacyDirectCalls"]:
    errors.append("repository-owned Runtime callers still use legacy Bundle methods")
if audit["typedRuntimeTransport"]["legacyCompatibilityDeclarations"] > 12:
    errors.append("legacy compatibility declarations may only decrease after M5-T16")
if not all(audit["methodClassification"].values()):
    errors.append("method-classification regression evidence incomplete")
if not all(audit["falseCoverageClosed"].values()):
    errors.append("RestrictionsManager false coverage is not closed")
if audit["deviceEvidenceCount"] != 0:
    errors.append("M5-T16 must not claim device evidence")
if audit["referenceFilesModified"] != 0:
    errors.append("reference files must remain read-only")
if audit["frozenCapabilityCategories"] != 113:
    errors.append("frozen capability category count changed")

preflight = json.loads((ROOT / "verification/m5-t16-source-preflight.json").read_text())
if preflight.get("sourceStatus") != "PASS" or preflight.get("productionStatus") != "PARTIAL":
    errors.append("invalid M5-T16 source/production status")
if preflight.get("deviceEvidenceCount") != 0:
    errors.append("preflight device evidence must remain zero")

legacy_pattern = re.compile(r"\b(?:broker|guest|requireBroker\(\))\.(?:prepareGuest|launchActivity|invokeComponent|grantUriPermission|revokeUriPermission|consumeRoute|activityEvent|sessionStatus)\s*\(")
for base in [ROOT / "app/src/main/java", ROOT / "sandbox-runtime/src/main/java"]:
    for source in base.rglob("*.java"):
        if source.name in {"RuntimeBrokerService.java", "BaseGuestProcessService.java", "RuntimeBrokerOperationAdapter.java"}:
            continue
        if legacy_pattern.search(source.read_text()):
            errors.append(f"legacy internal Runtime call: {source.relative_to(ROOT)}")

changed_refs = subprocess.run(
    ["git", "diff", "--name-only", "50d1be1", "--", "ref/upstream"],
    cwd=ROOT, text=True, capture_output=True, check=True).stdout.strip()
if changed_refs:
    errors.append("M5-T16 modifies ref/upstream")

if errors:
    print("FAIL M5-T16 source-closure gate", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T16 source-closure gate")
