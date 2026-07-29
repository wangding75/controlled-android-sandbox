#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")

def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value

require("docs/plans/M5_T4_DEVELOPMENT_PLAN.md", "Native network", "Dynamic loader", "Crash and ANR diagnostics")
require("docs/M5_T4_DEVELOPMENT_REPORT.md", "Source status: PASS", "Device evidence: 0")
require("docs/comparisons/M5_T4_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox", "Device evidence remains 0")
require("README.md", "## M5-T4 native network, loader and diagnostics source baseline")
require("docs/ROADMAP.md", "## M5-T4 native network, loader and diagnostics source baseline")

require("sandbox-native/src/main/cpp/native_interceptors.cpp",
        "controlled_socket", "controlled_bind", "controlled_sendto", "controlled_recvfrom",
        "controlled_setsockopt", "controlled_if_nametoindex", "validate_android_dlext")
require("sandbox-native/src/main/cpp/native_network.cpp",
        "MAX_TRACKED_SOCKETS", "project_bind_address", "virtual_if_nametoindex",
        "SO_BINDTODEVICE", "native_network_status_string")
require("sandbox-native/src/main/cpp/native_loader.cpp",
        "validate_elf_bytes", "validate_library_fd", "validate_android_dlext",
        "DLEXT_USE_RELRO", "DLEXT_USE_NAMESPACE")
require("sandbox-native/src/main/cpp/native_crash.cpp",
        "sigaltstack", "SIGSEGV", "si_code", "generation", "tgkill")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/diagnostics/AnrEpisodeTracker.java",
        "STARTED", "CONTINUING", "RECOVERED")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/diagnostics/RuntimeDiagnostics.java",
        "writeAnrEvidence", "Thread.getAllStackTraces", "sha256", "fileCount")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "nativeNetworkStatus", "nativeLoaderStatus")

for rel, tokens in {
    "sandbox-native/src/test/cpp/native_network_self_test.cpp":
        ("native_project_bind_address", "SO_BINDTODEVICE", "Connectivity status projection"),
    "sandbox-native/src/test/cpp/native_loader_self_test.cpp":
        ("ELF ABI mismatch denied", "RELRO", "namespace"),
    "sandbox-native/src/test/cpp/native_crash_self_test.cpp":
        ("SIGSEGV", "native crash evidence"),
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/diagnostics/RuntimeDiagnosticsSelfTest.java":
        ("ANR episode monotonic id", "manifest count", "manifest hashes"),
}.items():
    require(rel, *tokens)

try:
    preflight = json.loads(text("verification/m5-t4-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T4 source status must be pass")
    if preflight.get("androidBuildStatus") != "blocked-toolchain": errors.append("Android build must remain blocked-toolchain")
    for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
        if preflight.get("deviceEvidence", {}).get(field) != 0:
            errors.append(f"device {field} must remain zero")
    expected = {"native.network-hook", "native.dynamic-loader-hook", "diagnostics.crash-anr-events"}
    if set(preflight.get("resolvedM4ProductionIds", [])) != expected:
        errors.append("M5-T4 resolved capability set is incorrect")
except Exception as exc:
    errors.append(f"invalid M5-T4 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    by_id = {item.get("id"): item for item in matrix.get("capabilities", [])}
    for capability in ("native.network-hook", "native.dynamic-loader-hook", "diagnostics.crash-anr-events"):
        item = by_id.get(capability, {})
        if item.get("sourceStatus") != "complete" or item.get("productionStatus") != "wired":
            errors.append(f"{capability} must be source complete and production wired")
        if item.get("deviceStatus") != "not-tested":
            errors.append(f"{capability} device status must remain not-tested")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

runner = text("tools/static_android_compile.py")
if "RuntimeDiagnosticsSelfTest" not in runner:
    errors.append("static compiler does not run RuntimeDiagnosticsSelfTest")

if errors:
    print("FAIL M5-T4 native network/loader/diagnostics checks", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T4 native network/loader/diagnostics checks: source wired; build/device evidence remains blocked/not-tested")
