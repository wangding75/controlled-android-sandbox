#!/usr/bin/env python3
from __future__ import annotations

import json
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
    "docs/plans/M5_T5_DEVELOPMENT_PLAN.md",
    "32-bit device fixture",
    "Four-APK device-lab build contract",
    "SOURCE PASS / ANDROID BUILD AND DEVICE BLOCKED",
)
require(
    "docs/M5_T5_DEVELOPMENT_REPORT.md",
    "Source status: PASS",
    "Android APK build: BLOCKED",
    "Emulator evidence: 0",
)
require(
    "docs/comparisons/M5_T5_VA_NBB_COMPARISON.md",
    "VirtualApp",
    "NewBlackbox",
    "Device evidence remains 0",
)
require("README.md", "## M5-T5 locked four-APK device-lab source baseline")
require("docs/ROADMAP.md", "## M5-T5 locked four-APK device-lab source baseline")
require("docs/EMULATOR_TEST.md", "## M5-T5 formal four-APK device lab")

settings = require("settings.gradle", "':fixture-compat32'")
fixture_gradle = require(
    "fixture-compat32/build.gradle",
    "applicationId 'com.warden.controlledsandbox.fixture32'",
    "abiFilters 'armeabi-v7a', 'x86'",
    "../fixture-basic/src/main/java",
    "../fixture-basic/src/main/cpp/CMakeLists.txt",
)
require(
    "fixture-compat32/src/main/AndroidManifest.xml",
    'android:authorities="${applicationId}.provider"',
    'android:process=":remote"',
    'android:isolatedProcess="true"',
)
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/FixtureNative.java",
    "probe(File filesDir)",
    "nativeProbe(String path)",
)
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/MainActivity.java",
    'getPackageName() + ".DYNAMIC_PING"',
    "FixtureNative.probe(getFilesDir())",
)
require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    "NativeAbiRoutePlanner.requiresCompanion",
    "NativeCompanionClient",
    "processBitness",
    "companionJson",
)

for rel, tokens in {
    "scripts/bootstrap-m5-device-lab.sh": ("JDK 17", "sha256sum", "deviceLab", "sdkmanager"),
    "scripts/bootstrap-m5-device-lab.ps1": ("Assert-Java17", "Assert-Sha256", "commandLineToolsVersion"),
    "scripts/build-device-lab-apks.sh": ("deviceLabBuild", "--profile device-lab"),
    "scripts/build-device-lab-apks.ps1": ("deviceLabBuild", "--profile", "device-lab"),
    "scripts/run-emulator-m5.sh": ("m5_device_lab.py",),
    "scripts/run-emulator-m5.ps1": ("m5_device_lab.py", "StabilitySeconds"),
    "scripts/m5_device_lab.py": (
        "formalStabilitySeconds",
        "fixture64",
        "fixture32",
        "Companion32",
        "fatalFindings",
        "runtimeDiagnostics",
        "multiple Android devices are online",
    ),
    "scripts/check-m5-device-evidence.py": ("validate_formal_evidence", "git", "rev-parse"),
    "scripts/test-m5-device-lab.sh": ("forbidden Fixture32 ABI", "test_m5_device_lab.py"),
}.items():
    require(rel, *tokens)

try:
    lock = json.loads(text("build-environment.lock.json"))
    build = lock.get("deviceLabBuild", {})
    ids = [item.get("id") for item in build.get("artifacts", [])]
    if ids != ["host", "fixture64", "fixture32", "companion32"]:
        errors.append("deviceLabBuild four-APK order is incorrect")
    by_id = {item.get("id"): item for item in build.get("artifacts", [])}
    if by_id.get("fixture64", {}).get("allowedAbis") != ["arm64-v8a", "x86_64"]:
        errors.append("Fixture64 ABI contract is incorrect")
    if by_id.get("fixture32", {}).get("allowedAbis") != ["armeabi-v7a", "x86"]:
        errors.append("Fixture32 ABI contract is incorrect")
    if by_id.get("companion32", {}).get("allowedAbis") != ["armeabi-v7a", "x86"]:
        errors.append("Companion32 ABI contract is incorrect")
    lab = lock.get("deviceLab", {})
    if lab.get("requiredDeviceAbis") != ["x86_64", "x86"]:
        errors.append("deviceLab required ABI pair is incorrect")
    if lab.get("formalStabilitySeconds") != 1200:
        errors.append("formal device-lab duration must be 1200 seconds")
except Exception as exc:
    errors.append(f"invalid build lock: {exc}")

try:
    preflight = json.loads(text("verification/m5-t5-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass":
        errors.append("M5-T5 sourceStatus must be pass")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T5 Android build must remain blocked-toolchain")
    evidence = preflight.get("deviceEvidence", {})
    for field in ("verifiedCapabilities", "emulatorRuns", "physicalDeviceRuns", "stabilityMinutes"):
        if evidence.get(field) != 0:
            errors.append(f"M5-T5 device {field} must remain zero")
    contract = preflight.get("deviceLabContract", {})
    if contract.get("artifacts") != ["host", "fixture64", "fixture32", "companion32"]:
        errors.append("M5-T5 preflight artifact contract is incorrect")
    if contract.get("formalStabilitySeconds") != 1200 or contract.get("companionBitness") != 32:
        errors.append("M5-T5 preflight duration/bitness contract is incorrect")
except Exception as exc:
    errors.append(f"invalid M5-T5 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    source = Counter(item.get("sourceStatus") for item in capabilities)
    production = Counter(item.get("productionStatus") for item in capabilities)
    device = Counter(item.get("deviceStatus") for item in capabilities)
    if len(capabilities) != 113 or source != Counter({"complete": 113}):
        errors.append("M5-T5 must preserve 113 source-complete capabilities")
    baseline_production = Counter({"wired": 110, "blocked": 1, "not-applicable": 1, "partial": 1})
    m5_t6_production = Counter({"wired": 110, "not-applicable": 1, "partial": 2})
    if production not in (baseline_production, m5_t6_production):
        errors.append(f"M5-T5 production status changed unexpectedly: {dict(production)}")
    if production == m5_t6_production:
        isolated = next((item for item in capabilities if item.get("id") == "process.declared-isolated-planning"), {})
        if isolated.get("productionStatus") != "partial" or not (ROOT / "scripts/check-m5-t6-isolated-process.py").is_file():
            errors.append("only the gated M5-T6 isolated capability may advance the M5-T5 production counters")
    if device != Counter({"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append(f"M5-T5 device status changed unexpectedly: {dict(device)}")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

verify_all = text("scripts/verify-all.sh")
for token in ("check-m5-t5-device-lab.py", "test-m5-device-lab.sh"):
    if token not in verify_all:
        errors.append(f"verify-all.sh missing M5-T5 gate: {token}")

if errors:
    print("FAIL M5-T5 device-lab source checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T5 device-lab source checks: source ready; Android build/device evidence remains blocked/zero")
