#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def text(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")

plan = text("docs/plans/M5_T2_DEVELOPMENT_PLAN.md")
report = text("docs/M5_T2_DEVELOPMENT_REPORT.md")
comparison = text("docs/comparisons/M5_T2_VA_NBB_COMPARISON.md")
readme = text("README.md")
client = text("app/src/main/java/com/warden/controlledsandbox/NativeCompanionClient.java")
runtime_client = text("app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java")
workspace = text("sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionWorkspaceStore.java")
companion_manifest = text("sandbox-companion32/src/main/AndroidManifest.xml")
host_manifest = text("app/src/main/AndroidManifest.xml")
companion_gradle = text("sandbox-companion32/build.gradle")
host_gradle = text("app/build.gradle")
native_gradle = text("sandbox-native/build.gradle")
isolated = text("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/IsolatedProcessRoutePolicy.java")

for needle in (
    "32-bit Guest Runtime", "Activity", "Service", "Receiver", "Provider",
    "Real APK build and emulator/device execution remain separately reported",
    "Execution status: PASS",
):
    if needle not in plan:
        errors.append(f"M5-T2 plan missing: {needle}")


for document, name in ((report, "report"), (comparison, "comparison")):
    for needle in ("Device evidence", "isolated", "Companion"):
        if needle not in document:
            errors.append(f"M5-T2 {name} missing: {needle}")
if "## M5-T2 cross-width runtime source baseline" not in readme:
    errors.append("README must publish the M5-T2 source baseline")

for needle in (
    "INativeCompanionArtifactService", "stageArtifact", "prepareWorkspace",
    "requireBroker().prepareGuest", "requireBroker().launchActivity",
    "requireBroker().invokeComponent", "requireBroker().stopGuest",
    "resetControlBinding", "resetArtifactBinding", "resetBrokerBinding",
):
    if needle not in client:
        errors.append(f"companion client missing production route: {needle}")
if "NATIVE_COMPANION_CROSS_WIDTH_EXECUTION_NOT_WIRED" in runtime_client + client:
    errors.append("obsolete cross-width execution blocker remains in production route")
for needle in ("companionRoute(record)", "nativeCompanion.prepare", "nativeCompanion.launchActivity", "nativeCompanion.invokeComponent"):
    if needle not in runtime_client:
        errors.append(f"RuntimeClient missing 32-bit route: {needle}")

for needle in (
    "MAX_WORKSPACES = 64", "MAX_ARTIFACTS_PER_WORKSPACE = 512",
    "MAX_WORKSPACE_BYTES", "SHA-256", "getCanonicalFile", "ATOMIC_MOVE",
    "COMPANION_ARTIFACT_HASH_MISMATCH", "NATIVE_COMPANION_PROTOCOL_MISMATCH",
):
    if needle not in workspace:
        errors.append(f"workspace safety control missing: {needle}")

for needle in ("sandbox-runtime", "armeabi-v7a", "x86"):
    if needle not in companion_gradle:
        errors.append(f"Companion Gradle missing: {needle}")
for needle in ("arm64-v8a", "x86_64"):
    if needle not in host_gradle:
        errors.append(f"Host ABI filter missing: {needle}")
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86"):
    if abi not in native_gradle:
        errors.append(f"shared native module missing ABI: {abi}")

for manifest, name in ((companion_manifest, "Companion"), (host_manifest, "Host")):
    if "com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION" not in manifest:
        errors.append(f"{name} manifest missing signature companion permission")
if "NativeCompanionArtifactService" not in companion_manifest or "RuntimeBrokerService" not in companion_manifest:
    errors.append("Companion manifest must expose artifact and Runtime Broker services")
if "ISOLATED_PROCESS_DEDICATED_UID_TRANSPORT_REQUIRED" not in isolated:
    errors.append("isolated process must fail before ordinary process allocation")

try:
    preflight = json.loads(text("verification/m5-t2-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T2 source status must be pass")
    if preflight.get("androidBuildStatus") != "blocked-toolchain": errors.append("Android build status must remain blocked-toolchain")
    device = preflight.get("deviceEvidence", {})
    for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
        if device.get(field) != 0: errors.append(f"M5-T2 device {field} must remain zero")
    if preflight.get("resolvedM4ProductionIds") != ["runtime.native-abi-routing"]:
        errors.append("M5-T2 must explicitly resolve only runtime.native-abi-routing")
except Exception as exc:
    errors.append(f"invalid M5-T2 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    by_id = {item.get("id"): item for item in matrix.get("capabilities", [])}
    if by_id.get("runtime.native-abi-routing", {}).get("productionStatus") != "wired":
        errors.append("runtime.native-abi-routing must be wired")
    if by_id.get("native.four-abi-build-architecture", {}).get("productionStatus") != "partial":
        errors.append("four ABI build architecture must remain partial until real build")
    if by_id.get("process.declared-isolated-planning", {}).get("productionStatus") != "blocked":
        errors.append("isolated process must remain blocked until dedicated UID transport")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if errors:
    print("FAIL M5-T2 cross-width runtime checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T2 cross-width runtime checks: 32-bit production route wired; build/device evidence remains blocked/not-tested")
