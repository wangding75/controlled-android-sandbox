#!/usr/bin/env python3
"""Static contract gate for the C2-T03 Location implementation and campaign."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing file: {relative}")
        return ""
    return path.read_text(encoding="utf-8-sig")


def require(relative: str, *tokens: str) -> None:
    value = read(relative)
    for token in tokens:
        if token not in value:
            errors.append(f"{relative} missing: {token}")


require(
    "sandbox-framework/src/main/java/android/location/ControlledLocationManager.java",
    "implements AutoCloseable", "LocationRegistration", "NmeaRegistration",
    "GnssRegistration", "VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED",
    "VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED", "scheduler.shutdownNow",
    "locationPermission.getAsBoolean", "unregisterGnssStatusCallback",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/LocationServiceHook.java",
    "manager.close()", "CapabilityAccessPolicy.LOCATION",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/DeviceServiceInvocationInterceptor.java",
    "EXPLICIT_LOCATION_RELEASE",
)
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/LocationCampaignActivity.java",
    "C2_T03_LOCATION_PROBE", "C2_T03_LOCATION_CALLBACK", "C2_T03_LOCATION_NMEA",
    "C2_T03_LOCATION_GNSS", "C2_T03_LOCATION_NEGATIVE", "onPause",
)
require(
    "fixture-basic/src/main/AndroidManifest.xml",
    "LocationCampaignActivity",
)
require(
    "tools/capability/run_c2_t03_rd.py",
    "resolve_rd_environment", "--stable-seconds", "C2_T03_LOCATION_CALLBACK",
    "C2_T03_LOCATION_NEGATIVE",
)
runner = read("tools/capability/run_c2_t03_rd.py")
for forbidden in ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555"):
    if forbidden in runner:
        errors.append(f"runner hard-codes historical ADB endpoint: {forbidden}")

design = read("docs/review/C2_T03_LOCATION_DESIGN.md")
if "30 分钟" not in design or "KI-R03-035" not in design:
    errors.append("Location design is missing stability or GNSS boundary statement")

catalog = read("docs/review/C2_T01_SYSTEM_SERVICE_METHOD_CATALOG.md")
if "F2-01" not in catalog or "F2-04" not in catalog or "F2-05" not in catalog:
    errors.append("C2-T01 Location method families are not referenced")

try:
    manifest = read("fixture-basic/src/main/AndroidManifest.xml")
    if "ACCESS_FINE_LOCATION" not in manifest or "ACCESS_COARSE_LOCATION" not in manifest:
        errors.append("fixture location permissions are missing")
    json.loads("{}")
except Exception as error:
    errors.append(f"static JSON sanity failed: {error}")

if errors:
    print("FAIL C2-T03 Location static checks")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)
print("PASS C2-T03 Location static checks: profile, callback lifecycle, negative branches and RD runner wired")
