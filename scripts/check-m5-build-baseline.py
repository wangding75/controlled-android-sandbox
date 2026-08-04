#!/usr/bin/env python3
"""Static gate for the M5 real-build baseline and ABI artifact contract."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    raise SystemExit(message)


def require(path: str, token: str) -> None:
    text = (ROOT / path).read_text()
    if token not in text:
        fail(f"Missing M5 build baseline token in {path}: {token}")


def main() -> int:
    lock = json.loads((ROOT / "build-environment.lock.json").read_text())
    android = lock["toolchain"]["android"]
    build = lock.get("deviceTestBuild", {})
    if build.get("schemaVersion") != 1 or build.get("variant") != "debug":
        fail("deviceTestBuild schema/variant is not frozen")
    expected_ids = ["host", "fixture", "companion32"]
    artifacts = build.get("artifacts", [])
    if [item.get("id") for item in artifacts] != expected_ids:
        fail("Locked device-test artifact order or identifiers changed")
    if android.get("hostAbis") != ["arm64-v8a", "x86_64"]:
        fail("Host ABI lock changed")
    if android.get("companionAbis") != ["armeabi-v7a", "x86"]:
        fail("Companion ABI lock changed")
    expected_packages = {
        "platform-tools",
        "platforms;android-36",
        "build-tools;35.0.0",
        "ndk;27.2.12479018",
        "cmake;3.22.1",
    }
    if set(android.get("sdkPackages", [])) != expected_packages:
        fail("Locked Android SDK package set changed")

    settings = (ROOT / "settings.gradle").read_text()
    for module in (":app", ":fixture-basic", ":fixture-compat32", ":sandbox-companion32"):
        if f"include '{module}'" not in settings:
            fail(f"Missing device-test module in settings.gradle: {module}")

    module_text = {
        "host": (ROOT / "app/build.gradle").read_text(),
        "sharedNative": (ROOT / "sandbox-native/build.gradle").read_text(),
        "fixture": (ROOT / "fixture-basic/build.gradle").read_text(),
        "companion32": (ROOT / "sandbox-companion32/build.gradle").read_text(),
    }
    for abi in android["hostAbis"]:
        if abi not in module_text["host"] or abi not in module_text["fixture"]:
            fail(f"Host ABI not declared in Host and fixture APKs: {abi}")
    for abi in android["hostAbis"] + android["companionAbis"]:
        if abi not in module_text["sharedNative"]:
            fail(f"Shared native runtime does not build ABI: {abi}")
    for abi in android["companionAbis"]:
        if abi not in module_text["companion32"]:
            fail(f"Companion ABI not declared: {abi}")
    for forbidden in android["companionAbis"]:
        if re.search(rf"abiFilters[^\n]*['\"]{re.escape(forbidden)}['\"]", module_text["host"]):
            fail(f"32-bit ABI leaked into Host native module: {forbidden}")

    require("scripts/build-device-test-apks.sh", "verify-device-test-artifacts.py")
    require("scripts/build-device-test-apks.ps1", "verify-device-test-artifacts.py")
    require("scripts/build-device-test-apks.sh", "check-build-environment.py --android")
    require("scripts/build-device-test-apks.ps1", "check-build-environment.py --android")
    require("scripts/reproducible-build.sh", ":sandbox-companion32:assembleRelease")
    require("scripts/reproducible-build.ps1", ":sandbox-companion32:assembleRelease")
    require("scripts/reproducible-build.sh", ":fixture-compat32:assembleRelease")
    require("scripts/reproducible-build.ps1", ":fixture-compat32:assembleRelease")
    require("scripts/reproducible-build.sh", "release_apk_signing.py")
    require("scripts/reproducible-build.ps1", "release_apk_signing.py")
    require("app/build.gradle", "release-signing.gradle")
    require("sandbox-companion32/build.gradle", "release-signing.gradle")
    require("app/src/main/AndroidManifest.xml", "BIND_NATIVE_COMPANION")
    require("sandbox-companion32/src/main/AndroidManifest.xml", "BIND_NATIVE_COMPANION")
    require("sandbox-companion32/src/main/AndroidManifest.xml", "android:exported=\"true\"")
    require("docs/plans/M5_T1_DEVELOPMENT_PLAN.md", "M5-T1")
    require("docs/M5_T1_DEVELOPMENT_REPORT.md", "Actual Android APK build in this execution environment: **BLOCKED**")
    preflight = json.loads((ROOT / "verification/m5-t1-build-preflight.json").read_text())
    if preflight.get("schemaVersion") != 1 or preflight.get("sourceImplementation") != "PASS":
        fail("M5-T1 source preflight status is not PASS")
    if preflight.get("androidBuild") != "BLOCKED" or preflight.get("androidBuildEvidence") is not False:
        fail("M5-T1 preflight must not claim Android build evidence")
    if preflight.get("deviceRuns") != 0:
        fail("M5-T1 preflight must not claim device runs")

    print("PASS M5 real-build baseline and four-ABI artifact contract")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
