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


require("docs/plans/M5_T17_PRIVILEGED_ENVIRONMENT_SERVICES.md",
        "SearchManager", "StorageStats", "GraphicsStats", "ContextHub",
        "PersistentDataBlock", "SystemUpdate", "Android/device evidence remains zero")
require("docs/M5_T17_DEVELOPMENT_REPORT.md",
        "Source status: PASS", "Production status: PARTIAL", "Device evidence: 0",
        "Process-local mutation overlays")
require("docs/comparisons/M5_T17_VA_NBB_COMPARISON.md",
        "VirtualApp", "NewBlackbox", "device evidence remain 0")
require("README.md", "## M5-T17 privileged environment-services source baseline")
require("docs/ROADMAP.md", "## M5-T17 privileged environment-services baseline")

contracts = (
    "VirtualSearchProfileSnapshot",
    "VirtualStorageStatsProfileSnapshot",
    "VirtualGraphicsStatsProfileSnapshot",
    "VirtualContextHubSnapshot",
    "VirtualContextHubProfileSnapshot",
    "VirtualPersistentDataBlockProfileSnapshot",
    "VirtualSystemUpdateProfileSnapshot",
    "VirtualPrivilegedServicesProfileSnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable")

require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getPrivilegedServicesProfile", "setPrivilegedServicesProfile", "resetPrivilegedServicesProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getPrivilegedServicesProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onPrivilegedServicesProfileChanged")

require("app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesDefaults.java",
        "Deterministic fail-closed defaults", "MODE_STATIC")
require("app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStore.java",
        "PRIVILEGED_SERVICES_PROFILE_VERSION_CONFLICT", "deleteScopeBestEffort", "scope limit exceeded")
require("app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStorePersistence.java",
        "CRC32", ".corrupt", "DurableAtomicFile.replacePrepared")
require("app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStoreCodec.java",
        "PRIVILEGED_SERVICES_SCOPE_DUPLICATE", "maximumSuggestionResults",
        "maximumBuffers", "maximumClients", "maximumDataBytes")
require("app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
        "getPrivilegedServicesProfile", "setPrivilegedServicesProfile",
        "resetPrivilegedServicesProfile")
require("app/src/main/java/com/warden/controlledsandbox/PackageProfileAuthority.java",
        "notifyPrivilegedServicesProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
        "privilegedServicesProfile", "setPrivilegedServicesProfile", "resetPrivilegedServicesProfile")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "onPrivilegedServicesProfileChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesInvocationInterceptor.java",
        'case "search"', 'case "storagestats"', 'case "graphicsstats"',
        'case "contexthub"', 'case "persistentdatablock"', 'case "systemupdate"',
        "VIRTUAL_GRAPHICS_BUFFER_LIMIT_EXCEEDED",
        "VIRTUAL_CONTEXT_HUB_CLIENT_LIMIT_EXCEEDED",
        "VIRTUAL_PERSISTENT_DATA_LIMIT_EXCEEDED",
        "VIRTUAL_SYSTEM_UPDATE_SUBMISSION_DENIED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PrivilegedInvocationValues.java",
        "component(", "storageStats(", "contextHub(", "systemUpdateBundle(")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java",
        "PrivilegedServicesInvocationInterceptor")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("search"', 'attempt("storageStats"', 'attempt("graphicsStats"',
        'attempt("contextHub"', 'attempt("persistentDataBlock"', 'attempt("systemUpdate"')
for hook in (
    "SearchManagerServiceHook", "StorageStatsManagerServiceHook", "GraphicsStatsServiceHook",
    "ContextHubServiceHook", "PersistentDataBlockServiceHook", "SystemUpdateServiceHook",
):
    require(f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
            "install")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PrivilegedServicesProxyReadiness.java",
        "VIRTUAL_SEARCH_PROXY_REQUIRED", "VIRTUAL_STORAGE_STATS_PROXY_REQUIRED",
        "VIRTUAL_GRAPHICS_STATS_PROXY_REQUIRED", "VIRTUAL_CONTEXT_HUB_PROXY_REQUIRED",
        "VIRTUAL_PERSISTENT_DATA_BLOCK_PROXY_REQUIRED", "VIRTUAL_SYSTEM_UPDATE_PROXY_REQUIRED")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "PrivilegedServicesProxyReadiness.require")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onPrivilegedServicesProfileChanged", "getPrivilegedServicesProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStoreSelfTest.java",
        "per-user privileged scope isolation", "defaults fail closed", "stale privileged update rejected",
        "corrupt privileged store quarantined", "PASS M5-T17 privileged-services profile store self-test")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesVirtualizationSelfTest.java",
        "global search component projected", "storage totals projected", "graphics buffer quota",
        "ContextHub client quota", "persistent data block size limit", "system update submission",
        "HOST search passes through", "persistent data virtualization does not call Host",
        "PASS M5-T17 privileged-services virtualization self-test")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PersistentDataBlockServiceContractSelfTest.java",
        "PersistentDataBlock service name", "API32/API35 manager cache compatibility",
        "PASS PersistentDataBlock API32/API35 contract self-test")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/PrivilegedServicesProxyReadinessSelfTest.java",
        "missing StorageStats proxy blocks startup", "missing SystemUpdate proxy blocks startup",
        "PASS M5-T17 privileged-services proxy readiness self-test")

runner = text("tools/static_android_compile.py")
for class_name in (
    "com.warden.controlledsandbox.VirtualPrivilegedServicesStoreSelfTest",
    "com.warden.controlledsandbox.framework.core.PrivilegedServicesVirtualizationSelfTest",
    "com.warden.controlledsandbox.framework.core.PersistentDataBlockServiceContractSelfTest",
    "com.warden.controlledsandbox.runtime.guest.PrivilegedServicesProxyReadinessSelfTest",
):
    if runner.count(f"'{class_name}'") != 1:
        errors.append(f"static Android compiler must execute {class_name} exactly once")

for rel in (
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PrivilegedServicesInvocationInterceptor.java",
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PrivilegedInvocationValues.java",
    "app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStore.java",
    "app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStoreCodec.java",
    "app/src/main/java/com/warden/controlledsandbox/VirtualPrivilegedServicesStorePersistence.java",
):
    lines = text(rel).splitlines()
    if len(lines) > 500:
        errors.append(f"M5-T17 production class exceeds 500 lines: {rel} ({len(lines)})")

for root in (
    "app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
    "sandbox-native", "sandbox-native-companion",
):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"upstream implementation namespace in product source: {path.relative_to(ROOT)}")

changed = subprocess.run(
    ["git", "diff", "--name-only", "c665cc6", "--", "ref/upstream"],
    cwd=ROOT, text=True, capture_output=True, check=True).stdout.strip()
if changed:
    errors.append("M5-T17 modifies ref/upstream")

try:
    closure = json.loads(text("verification/m5-t17-source-closure-audit.json"))
    if closure.get("remainingM5T16AuditedCandidates") != 0:
        errors.append("M5-T17 must close all six M5-T16 audited candidates")
    if len(closure.get("m5T16CandidatesClosed", [])) != 6:
        errors.append("M5-T17 closure audit must list six closed candidates")
    if closure.get("frameworkHookCount") != 64:
        errors.append("M5-T17 closure audit must record 64 hook groups")
except Exception as exc:
    errors.append(f"invalid M5-T17 closure audit: {exc}")

try:
    preflight = json.loads(text("verification/m5-t17-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS" or preflight.get("productionStatus") != "PARTIAL":
        errors.append("invalid M5-T17 source/production status")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T17 must not invent device evidence")
    evidence = preflight.get("evidence", {})
    if evidence.get("typedParcelableContracts") != 8:
        errors.append("M5-T17 preflight must record eight typed contracts")
    if evidence.get("profileDomains") != 6 or evidence.get("newFrameworkHookGroups") != 6:
        errors.append("M5-T17 preflight must record six domains and six hook groups")
    if evidence.get("referenceFilesModified") != 0:
        errors.append("M5-T17 preflight must record zero reference changes")
except Exception as exc:
    errors.append(f"invalid M5-T17 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T17 must preserve 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("frozen source counters changed")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("frozen production counters changed")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T17 invented device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t17-privileged-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T17 gate")

if errors:
    print("FAIL M5-T17 privileged environment-services checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T17 Search/StorageStats/GraphicsStats/ContextHub/PersistentDataBlock/SystemUpdate source gate")
