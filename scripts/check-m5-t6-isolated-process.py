#!/usr/bin/env python3
from __future__ import annotations

import json
import re
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


require(
    "docs/plans/M5_T6_DEVELOPMENT_PLAN.md",
    "Dedicated isolated Service transport",
    "four isolated slots",
    "SOURCE PASS / PRODUCTION PARTIAL / DEVICE BLOCKED",
)
require(
    "docs/M5_T6_DEVELOPMENT_REPORT.md",
    "Source status: PASS",
    "Production status: PARTIAL",
    "Device evidence: 0",
)
require(
    "docs/comparisons/M5_T6_VA_NBB_COMPARISON.md",
    "VirtualApp",
    "NewBlackbox",
    "ordinary Stub process",
    "Device evidence remains 0",
)
require("README.md", "## M5-T6 dedicated isolated Service source baseline")
require("docs/ROADMAP.md", "## M5-T6 dedicated isolated Service source baseline")

for name in ("IsolatedProcessRequest", "IsolatedProcessResult"):
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable", "processSlot", "generation")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/IsolatedProcessRequest.java",
        "capabilityToken")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/IsolatedProcessResult.java",
        "platformPid", "platformUid")
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IIsolatedGuestProcess.aidl",
    "IsolatedProcessResult prepare(in IsolatedProcessRequest request);",
    "IsolatedProcessResult invoke(in IsolatedProcessRequest request);",
    "IsolatedProcessResult status(in IsolatedProcessRequest request);",
    "void shutdown(String sessionId, long generation, String capabilityToken);",
)

manifest = require("sandbox-runtime/src/main/AndroidManifest.xml", 'android:isolatedProcess="true"')
if len(re.findall(r'IsolatedGuestProcessService[0-3]"[^>]*android:isolatedProcess="true"', manifest)) != 4:
    errors.append("runtime manifest must declare exactly four isolated Guest worker Services")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/BaseIsolatedGuestProcessService.java",
    "ISOLATED_PLATFORM_UID_NOT_ASSIGNED",
    "ISOLATED_OUTER_INNER_IDENTITY_MISMATCH",
    "ISOLATED_CAPABILITY_MISMATCH",
    "RUNTIME_BROKER_BINDER, null",
    "stopSelf()",
)
for slot in range(4):
    require(
        f"sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/IsolatedGuestProcessService{slot}.java",
        f"return {slot};",
    )
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java",
    "RuntimeIsolatedProcessCoordinator isolatedProcessCoordinator",
    "isolatedProcessCoordinator.invoke(request, isolatedMatch)",
    "isolatedProcessCoordinator.stopGuest",
    "isolatedProcessCoordinator.purgeExpiredForeground",
    "isolatedProcessCoordinator.close",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeIsolatedProcessCoordinator.java",
    "SLOT_COUNT = 4",
    "new SessionRegistry(SLOT_COUNT",
    "requireResult",
    "ISOLATED_PROCESS_UID_EQUALS_HOST_UID",
    "services.recoverSession",
    "handleDisconnect",
    "ISOLATED_CAPABILITY_TOKEN_COLLISION",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/IsolatedProcessRoutePolicy.java",
    "requireIsolatedService",
    "ISOLATED_PROCESS_ONLY_SERVICE_SUPPORTED",
    "ISOLATED_PROCESS_NON_SERVICE_OPERATION_REJECTED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/IsolatedComponentPolicy.java",
    "dedicatedIsolatedTransport",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/CombinedSessionMetricsRepository.java",
    "Math.addExact",
)

for rel, tokens in {
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/IsolatedProcessRoutePolicySelfTest.java":
        ("isolated provider", "wrongOperation", "dedicated isolated-process"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/IsolatedProcessContractSelfTest.java":
        ("defensively copy payload", "platform identity evidence", "invalid isolated contract"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/IsolatedProcessArchitectureSelfTest.java":
        ("SessionRegistry(8", "SessionRegistry(4", "fifth isolated lease", "advance generation"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/IsolatedComponentPolicySelfTest.java":
        ("dedicated-transport", "true"),
}.items():
    require(rel, *tokens)

runner = text("tools/static_android_compile.py")
for test in ("IsolatedProcessRoutePolicySelfTest", "IsolatedProcessContractSelfTest",
             "IsolatedProcessArchitectureSelfTest", "IsolatedComponentPolicySelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

try:
    preflight = json.loads(text("verification/m5-t6-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T6 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T6 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain": errors.append("M5-T6 Android build must remain blocked-toolchain")
    evidence = preflight.get("deviceEvidence", {})
    for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
        if evidence.get(field) != 0: errors.append(f"M5-T6 device {field} must remain zero")
    if preflight.get("isolatedSlotCount") != 4: errors.append("M5-T6 isolated slot count must be four")
except Exception as exc:
    errors.append(f"invalid M5-T6 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    by_id = {item.get("id"): item for item in capabilities}
    isolated = by_id.get("process.declared-isolated-planning", {})
    if isolated.get("sourceStatus") != "complete": errors.append("isolated source status must be complete")
    if isolated.get("productionStatus") != "partial": errors.append("isolated production status must be partial")
    if isolated.get("deviceStatus") != "blocked": errors.append("isolated device status must remain blocked")
    production = Counter(item.get("productionStatus") for item in capabilities)
    if production != Counter({"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append(f"M5-T6 production status matrix is unexpected: {dict(production)}")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

verify_all = text("scripts/verify-all.sh")
if "check-m5-t6-isolated-process.py" not in verify_all:
    errors.append("verify-all.sh missing M5-T6 gate")

if errors:
    print("FAIL M5-T6 dedicated isolated-process checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T6 dedicated isolated-process checks: source wired to a guarded Service-only route; production partial and device blocked")
