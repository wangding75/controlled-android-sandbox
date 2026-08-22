#!/usr/bin/env python3
"""Static contract gate for the C2-T06 device/network/media campaign."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *tokens: str) -> None:
    text = read(relative)
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing {token!r}")


require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/C2T06DeviceNetworkMediaActivity.java",
    "C2_T06_IDENTITY_RETURN", "C2_T06_TELEPHONY_RETURN", "registerTelephonyCallback",
    "C2_T06_TELEPHONY_CALLBACK", "C2_T06_WIFI_RETURN", "getScanResults",
    "C2_T06_CONNECTIVITY_RETURN", "registerDefaultNetworkCallback",
    "C2_T06_NETWORK_CALLBACK", "unregisterNetworkCallback", "C2_T06_DNS_CALLBACK",
    "C2_T06_VPN_RETURN", "C2_T06_AUDIO_RETURN", "requestAudioFocus",
    "C2_T06_BLUETOOTH_RETURN", "C2_T06_SENSOR_RETURN", "onSensorChanged",
    "flush", "unregisterListener", "C2_T06_PERMISSION_NEGATIVE",
    "C2_T06_CLEANUP", "C2_T06_CAMPAIGN_PASS",
)
require(
    "fixture-basic/src/main/AndroidManifest.xml",
    "C2T06DeviceNetworkMediaActivity", "ACCESS_NETWORK_STATE", "RECORD_AUDIO",
    "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BODY_SENSORS",
)
require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    "c2t06Mode", "c2t06Loops", "componentIntentExtras",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/DeviceServiceInvocationInterceptor.java",
    "SENSOR_CALLBACK_EXECUTOR", "EXPLICIT_TELEPHONY_RELEASE", "invokeTelephonyCallback",
    "invokeSensorFlushCallback", "cancelSensorCallback",
)
require(
    "docs/review/C2_T06_DEVICE_NETWORK_MEDIA_DESIGN.md",
    "request/return/callback", "cross-user", "RD_API32_L3", "NOT_PROVEN",
)
require(
    "docs/review/KNOWN_ISSUES.yaml",
    "KI-R03-039", "KI-R03-040", "PACKAGE_NEUTRAL_C2_T06_RD_METHOD_CAMPAIGN",
)

try:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))
    from run_rd_campaign import FORBIDDEN_SERIALS

    runner = read("tools/capability/run_c2_t06_rd.py")
    for forbidden in FORBIDDEN_SERIALS:
        if forbidden in runner:
            errors.append(f"runner contains historical ADB endpoint {forbidden}")
except Exception as error:  # pragma: no cover
    errors.append(f"could not load RD serial policy: {error}")

if errors:
    print("FAIL C2-T06 device/network/media static checks")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)

print("PASS C2-T06 device/network/media static checks")
