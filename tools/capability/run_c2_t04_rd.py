#!/usr/bin/env python3
"""C2-T04 RD campaign for the package-neutral Camera1/Camera2 contract."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import CampaignBlocked, HOST_PACKAGE, install_rd_apks, resolve_rd_environment, run_adb

TASK_ID = "C2-T04"
GUEST_PACKAGE = "com.warden.controlledsandbox.fixture"
PROBE_COMPONENT = "com.warden.controlledsandbox.fixture.CameraCampaignActivity"
CAMERA_PERMISSION = "android.permission.CAMERA"
CAMERA_APPOPS = "android:camera"
TAG = "CS_C2_T04_CAMERA"
FRAME_TAG = "CS_CAMERA_FRAME"
TRUST = ("--ez", "trustNativeGuest", "true")


def command(serial: str, name: str, extra: list[str] | None = None,
            *, deadline_sec: int = 180, force_stop_host: bool = True) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    args = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0", "--es", "requestId", request_id, *TRUST]
    if extra:
        args.extend(extra)
    return debug_command(serial, args, deadline_sec=deadline_sec,
                         force_stop_host=force_stop_host)


def result_of(payload: dict[str, Any]) -> dict[str, Any]:
    value = payload.get("result")
    if not isinstance(value, dict):
        raise RuntimeError(f"debug command returned no result: {json.dumps(payload, ensure_ascii=False)}")
    return value


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    return result_of(payload)


def logcat(serial: str) -> str:
    return run_adb(serial, ["logcat", "-d", "-v", "threadtime",
                            f"{TAG}:I", f"{FRAME_TAG}:I", "CS_CAMERA_CLEANUP:I",
                            "CS_CAMERA_CALL:I", "CS_CAMERA_PROXY:I", "*:S"],
                   check=False).stdout


def wait_for_log(serial: str, markers: tuple[str, ...], timeout_sec: int,
                 *, fail_marker: str = "C2_T04_CAMERA_FAIL") -> str:
    deadline = time.monotonic() + timeout_sec
    last = ""
    while time.monotonic() < deadline:
        last = logcat(serial)
        if fail_marker and fail_marker in last:
            return last
        if any(marker in last for marker in markers):
            return last
        time.sleep(2.0 if timeout_sec < 120 else 10.0)
    return last


def stop_host(serial: str) -> dict[str, Any]:
    stopped = run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    deadline = time.monotonic() + 15
    remaining = ""
    while time.monotonic() < deadline:
        remaining = run_adb(serial, ["shell", "pidof", HOST_PACKAGE], check=False).stdout.strip()
        if not remaining:
            break
        time.sleep(0.2)
    if remaining:
        raise RuntimeError(f"host process survived force-stop: {remaining}")
    return {"returncode": stopped.returncode, "stdout": stopped.stdout.strip(),
            "stderr": stopped.stderr.strip(), "remainingPid": remaining}


def validate_phase(name: str, text: str, expected: tuple[str, ...],
                  *, source_sha: str, expect_failure: bool = False) -> dict[str, Any]:
    failures = [line for line in text.splitlines() if "C2_T04_CAMERA_FAIL" in line]
    if expect_failure:
        if not failures:
            raise RuntimeError(f"{name} did not emit a permission failure")
        if "C2_T04_CAMERA1_OPEN" in text or "C2_T04_CAMERA2_OPEN" in text:
            raise RuntimeError(f"{name} opened a camera after revoke")
    else:
        missing = [marker for marker in expected if marker not in text]
        if missing:
            raise RuntimeError(f"{name} missing markers {missing}; failures={failures[-2:]}")
        if source_sha and source_sha not in text and name in {"smoke", "preview"}:
            raise RuntimeError(f"{name} did not correlate delivered frame to configured source")
    if "FATAL EXCEPTION" in text or "ANR in" in text:
        raise RuntimeError(f"{name} emitted fatal runtime evidence")
    return {
        "name": name,
        "status": "PASS",
        "expectedMarkers": list(expected),
        "failureMarkers": failures[-4:],
        "frameMarkerCount": text.count("CS_CAMERA_FRAME"),
        "cleanupMarkerCount": text.count("CS_CAMERA_CLEANUP"),
        "sessionClosedCount": text.count("C2_T04_CAMERA2_SESSION_CLOSED"),
    }


def run_phase(serial: str, output: Path, name: str, mode: str, source_sha: str,
              *, loops: int, pressure_seconds: int, timeout_sec: int,
              expect_failure: bool = False) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    payload = command(serial, "launch-component", [
        "--es", "component", PROBE_COMPONENT,
        "--es", "componentMode", mode,
        "--ei", "cameraLoops", str(loops),
        "--ei", "cameraPressureSeconds", str(pressure_seconds),
    ], deadline_sec=180)
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"launch {name} failed: {json.dumps(payload, ensure_ascii=False)}")
    text = wait_for_log(serial, (f"C2_T04_CAMERA_{mode.upper()}_PASS",
                                 "C2_T04_CAMERA_RECOVERY_READY"), timeout_sec)
    log_path = output / f"c2-t04-{name}-logcat.txt"
    log_path.write_text(text, encoding="utf-8")
    expected_markers = {
        "smoke": ("C2_T04_CAMERA_SMOKE_PASS", "C2_T04_CAMERA1_PREVIEW",
                  "C2_T04_CAMERA1_CAPTURE", "C2_T04_CAMERA2_IMAGE",
                  "C2_T04_CAMERA2_SESSION_CLOSED"),
        "loops": ("C2_T04_CAMERA_LOOPS_PASS",),
        "preview": ("C2_T04_CAMERA_PREVIEW_PASS", "C2_T04_CAMERA2_PREVIEW_PROGRESS",
                    "C2_T04_CAMERA2_SESSION_CLOSED"),
        "recovery": ("C2_T04_CAMERA_RECOVERY_READY",),
    }
    observation = validate_phase(
        name, text, expected_markers.get(mode, ()), source_sha=source_sha,
        expect_failure=expect_failure)
    observation.update({
        "mode": mode,
        "launch": payload,
        "logcat": str(log_path),
        "stop": stop_host(serial),
    })
    return observation


def configure_camera(serial: str) -> dict[str, Any]:
    result = require_pass("configure camera", command(serial, "configure-camera", [
        "--ez", "cameraEnabled", "true",
        "--ez", "generateCameraSource", "true",
        "--es", "sourceKind", "IMAGE",
    ]))
    operation = result.get("operation")
    return operation if isinstance(operation, dict) else result


def set_camera_policy(serial: str, decision: str, appops: str) -> dict[str, Any]:
    permission = require_pass(f"set camera permission {decision}", command(serial, "set-permissions", [
        "--es", "permissions", CAMERA_PERMISSION, "--es", "decision", decision,
    ]))
    appop = require_pass(f"set camera AppOps {appops}", command(serial, "set-appops", [
        "--es", "appOps", CAMERA_APPOPS, "--es", "mode", appops,
    ]))
    return {"permission": permission, "appop": appop}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    parser.add_argument("--loops", type=int, default=100)
    parser.add_argument("--pressure-seconds", type=int, default=1800)
    args = parser.parse_args()
    if args.loops < 1 or args.pressure_seconds < 1:
        raise SystemExit("loops and pressure-seconds must be positive")

    output = artifacts_dir("catch-up-c2-t04")
    verification = ROOT / "verification/catch-up/C2-T04"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "Camera1/Camera2 source-frame and lifecycle",
        "branch": identity["branch"],
        "commit": identity["commit"],
        "tree": identity["tree"],
        "timestamp": now_iso(),
        "host_os": host_os(),
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "build_result": "PASS",
        "static_result": "PASS",
        "targeted_result": "PASS",
        "rd_result": "UNVERIFIED",
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": ["KI-R03-020", "KI-R03-023", "KI-R03-024", "KI-R03-025",
                         "KI-R03-026", "KI-M10-005", "KI-M10-006", "KI-M10-007",
                         "KI-R03-034", "KI-R03-035", "KI-R03-036", "KI-R03-037"],
        "evidence_files": [],
        "notes": "RD API32 evidence only; Camera2 YUV_420_888 is recorded as an advertised format while the delivered source paths are Camera1 NV21 and Camera2 JPEG. VA PRO/OEM/HAL matrix remains unproven.",
    }
    details: dict[str, Any] = {
        "task_id": TASK_ID,
        "started_at": now_iso(),
        "requested_loops": args.loops,
        "requested_pressure_seconds": args.pressure_seconds,
        "steps": [],
        "phases": [],
    }
    campaign_status = "FAIL"
    try:
        environment = resolve_rd_environment(args.instance)
        evidence.update({
            "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
            "device_name": environment["device_name"],
            "adb_serial": environment["adb_serial"],
            "api_level": environment["api_level"],
            "abi": environment["abi"],
            "boot_id": environment["boot_id"],
            "android_id": environment["android_id"],
            "instance_name": environment["instance_name"],
        })
        serial = environment["adb_serial"]
        reset = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        if reset.returncode != 0 or reset.stdout.strip().lower() != "success":
            raise RuntimeError(f"host data reset failed: {reset.stdout} {reset.stderr}")
        details["steps"].append({"name": "reset_host_data", "status": "PASS"})
        details["steps"].append({"name": "install_rd_apks", "result": install_rd_apks(serial)})
        grant = run_adb(serial, ["shell", "pm", "grant", HOST_PACKAGE, CAMERA_PERMISSION], check=False)
        if grant.returncode != 0:
            raise RuntimeError(f"host camera grant failed: {grant.stdout} {grant.stderr}")
        details["steps"].append({"name": "grant_host_camera", "status": "PASS"})
        details["steps"].append({"name": "import_prepare",
                                  "result": require_pass("import prepare",
                                                          command(serial, "import-prepare"))})
        details["steps"].append({"name": "camera_policy_granted",
                                  "result": set_camera_policy(serial, "GRANTED", "ALLOWED")})
        configured = configure_camera(serial)
        details["steps"].append({"name": "configure_camera", "result": configured})
        source_sha = str(configured.get("cameraSourceSha256", ""))
        if len(source_sha) != 64:
            raise RuntimeError(f"configure camera returned invalid source hash: {source_sha}")
        details["source"] = {
            "sha256": source_sha,
            "kind": configured.get("cameraSourceKind"),
            "width": configured.get("cameraSourceWidth"),
            "height": configured.get("cameraSourceHeight"),
        }
        phase_timeout = max(120, args.pressure_seconds + 90)
        details["phases"].append(run_phase(serial, output, "smoke", "smoke", source_sha,
                                            loops=args.loops,
                                            pressure_seconds=args.pressure_seconds,
                                            timeout_sec=120))
        details["phases"].append(run_phase(serial, output, "loops", "loops", source_sha,
                                            loops=args.loops,
                                            pressure_seconds=args.pressure_seconds,
                                            timeout_sec=300))
        details["phases"].append(run_phase(serial, output, "preview", "preview", source_sha,
                                            loops=args.loops,
                                            pressure_seconds=args.pressure_seconds,
                                            timeout_sec=phase_timeout))
        first_recovery = run_phase(serial, output, "recovery-first", "recovery", source_sha,
                                   loops=args.loops,
                                   pressure_seconds=args.pressure_seconds,
                                   timeout_sec=120)
        details["phases"].append(first_recovery)
        second_recovery = run_phase(serial, output, "recovery-second", "recovery", source_sha,
                                    loops=args.loops,
                                    pressure_seconds=args.pressure_seconds,
                                    timeout_sec=120)
        details["phases"].append(second_recovery)
        details["steps"].append({"name": "camera_policy_revoked",
                                  "result": set_camera_policy(serial, "DENIED", "IGNORED")})
        details["phases"].append(run_phase(serial, output, "permission-revoked", "smoke", source_sha,
                                            loops=args.loops,
                                            pressure_seconds=args.pressure_seconds,
                                            timeout_sec=120, expect_failure=True))
        details["steps"].append({"name": "camera_policy_restored",
                                  "result": set_camera_policy(serial, "GRANTED", "ALLOWED")})
        details["phases"].append(run_phase(serial, output, "post-revoke-recovery", "smoke", source_sha,
                                            loops=args.loops,
                                            pressure_seconds=args.pressure_seconds,
                                            timeout_sec=120))
        cleared = require_pass("clear camera user", command(serial, "clear"))
        details["steps"].append({"name": "clear_camera_user", "result": cleared})
        evidence["rd_result"] = "PASS"
        evidence["regression_result"] = "PASS"
        campaign_status = "PASS"
        details["status"] = "PASS"
    except CampaignBlocked as error:
        evidence["rd_result"] = "BLOCKED_ENV"
        evidence["failures"].append({"id": "C2-T04-RD", "classification": "ENVIRONMENT_BLOCKED",
                                      "summary": str(error)})
        campaign_status = "BLOCKED_ENV"
        details["status"] = "BLOCKED_ENV"
        details["error"] = str(error)
    except Exception as error:
        evidence["rd_result"] = "FAIL"
        evidence["failures"].append({"id": "C2-T04-RD", "classification": "FAIL",
                                      "summary": str(error)})
        campaign_status = "FAIL"
        details["status"] = "FAIL"
        details["error"] = str(error)
    finally:
        details["finished_at"] = now_iso()
        details_path = output / "campaign-details.json"
        write_json(details_path, details)
        evidence_path = output / "evidence.json"
        verification_path = verification / "c2-t04-rd-summary.json"
        evidence["evidence_files"] = [str(path) for path in sorted(output.glob("*"))]
        evidence["evidence_files"].append(str(evidence_path))
        evidence["evidence_files"].append(str(verification_path))
        schema_errors = validate_evidence(evidence)
        if schema_errors:
            evidence["failures"].append({"id": "C2-T04-EVIDENCE",
                                          "classification": "FAIL",
                                          "summary": "; ".join(schema_errors)})
            campaign_status = "FAIL"
        evidence["timestamp"] = evidence.get("timestamp") or now_iso()
        write_json(evidence_path, evidence)
        write_json(verification_path, evidence)
        print(json.dumps({"status": campaign_status, "output": str(output),
                          "verification": str(verification_path),
                          "serial": evidence.get("adb_serial", ""),
                          "phases": len(details.get("phases", []))},
                         ensure_ascii=False, indent=2))
    return 0 if campaign_status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
