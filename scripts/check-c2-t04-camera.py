#!/usr/bin/env python3
"""Static contract gate for the C2-T04 Camera1/Camera2 campaign."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(relative: str, *tokens: str) -> None:
    text = read(relative)
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing {token!r}")


errors: list[str] = []
require(
    "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/CameraCampaignActivity.java",
    "Camera.open(0)", "CameraManager", "ImageReader.newInstance", "FORMAT_NV21",
    "FORMAT_JPEG", "FORMAT_YUV_420_888", "C2_T04_CAMERA1_PREVIEW",
    "C2_T04_CAMERA1_CAPTURE", "C2_T04_CAMERA2_IMAGE", "C2_T04_CAMERA2_RESULT_REQUESTED",
    "C2_T04_CAMERA2_SESSION_CLOSED", "C2_T04_CAMERA_SMOKE_PASS",
    "C2_T04_CAMERA_LOOPS_PASS", "C2_T04_CAMERA_PREVIEW_PASS",
    "C2_T04_CAMERA_RECOVERY_READY", "requireVirtualCameraPermission",
    "GUEST_CAMERA_PERMISSION_DENIED",
)
require(
    "fixture-basic/src/main/AndroidManifest.xml",
    "com.warden.controlledsandbox.fixture.CameraCampaignActivity",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java",
    "RuntimeKeys.INTENT_EXTRAS", "launchComponent(SandboxRecord record, int virtualUserId, String component,",
)
require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    "generateCameraSource", "componentIntentExtras", "pngChunk",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PeripheralCameraInvocationHandler.java",
    "capabilityLeases().register", "capabilityLeases().release", "CS_CAMERA_CLEANUP",
    "android.util.Log.i(\"CS_CAMERA_CLEANUP\"",
)
require(
    "tools/capability/run_c2_t04_rd.py",
    "resolve_rd_environment", "install_rd_apks", "--loops", "--pressure-seconds",
    "camera_policy_revoked", "C2_T04_CAMERA_PREVIEW_PASS", "C2_T04_CAMERA_RECOVERY_READY",
)
require(
    "docs/review/C2_T04_CAMERA_DESIGN.md",
    "30 分钟", "NV21", "JPEG", "YUV_420_888", "resource lifecycle",
)

try:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))
    from run_rd_campaign import FORBIDDEN_SERIALS

    runner = read("tools/capability/run_c2_t04_rd.py")
    for forbidden in FORBIDDEN_SERIALS:
        if forbidden in runner:
            errors.append(f"runner contains historical ADB endpoint {forbidden}")
except Exception as error:  # pragma: no cover - the gate must report import failures
    errors.append(f"could not load RD serial policy: {error}")

if errors:
    print("FAIL C2-T04 camera static checks")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)

print("PASS C2-T04 camera static checks")
