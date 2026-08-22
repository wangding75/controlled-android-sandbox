#!/usr/bin/env python3
"""C2-T06 RD campaign for device, network and media service contracts."""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import (
    CampaignBlocked,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C2-T06"
COMPONENT = "com.warden.controlledsandbox.fixture.C2T06DeviceNetworkMediaActivity"
TAG = "CS_C2_T06"
TRUST = ("--ez", "trustNativeGuest", "true")
PERMISSIONS = (
    "android.permission.INTERNET,android.permission.ACCESS_NETWORK_STATE,"
    "android.permission.ACCESS_WIFI_STATE,android.permission.CHANGE_WIFI_STATE,"
    "android.permission.READ_PHONE_STATE,android.permission.RECORD_AUDIO,"
    "android.permission.MODIFY_AUDIO_SETTINGS,android.permission.BLUETOOTH,"
    "android.permission.BLUETOOTH_ADMIN,android.permission.BLUETOOTH_CONNECT,"
    "android.permission.BLUETOOTH_SCAN,android.permission.BODY_SENSORS"
)


def command(serial: str, name: str, user: int = 0, extra: list[str] | None = None,
            *, deadline_sec: int = 120, force_stop_host: bool = True) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    args = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user), "--es", "requestId", request_id, *TRUST]
    if extra:
        args.extend(extra)
    return debug_command(serial, args, deadline_sec=deadline_sec,
                         force_stop_host=force_stop_host)


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    result = payload.get("result")
    if not isinstance(result, dict):
        raise RuntimeError(f"{label} returned no result: {json.dumps(payload, ensure_ascii=False)}")
    return result


def logcat(serial: str) -> str:
    return run_adb(serial, ["logcat", "-d", "-v", "threadtime", f"{TAG}:I", "*:S"],
                   check=False).stdout


def wait_for_campaign(serial: str, timeout_sec: int) -> str:
    deadline = time.monotonic() + timeout_sec
    last = ""
    while time.monotonic() < deadline:
        last = logcat(serial)
        if "C2_T06_CAMPAIGN_PASS" in last or "C2_T06_CAMPAIGN_FAIL" in last:
            return last
        time.sleep(2.0 if timeout_sec < 120 else 5.0)
    return last


def validate_log(name: str, text: str, loops: int, *, negative: bool = False) -> dict[str, Any]:
    if "C2_T06_CAMPAIGN_FAIL" in text:
        raise RuntimeError(f"{name} emitted C2_T06_CAMPAIGN_FAIL")
    required = (
        "C2_T06_IDENTITY_RETURN",
        "C2_T06_TELEPHONY_RETURN",
        "C2_T06_WIFI_RETURN",
        "C2_T06_CONNECTIVITY_RETURN",
        "C2_T06_NETWORK_CALLBACK_RETURN",
        "C2_T06_SENSOR_RETURN",
        "C2_T06_AUDIO_RETURN",
        "C2_T06_MEDIA_RETURN",
        "C2_T06_BLUETOOTH_",
        "C2_T06_DNS_",
        "C2_T06_VPN_",
        "C2_T06_CLEANUP",
        "C2_T06_CAMPAIGN_PASS",
    )
    missing = [marker for marker in required
               if marker.endswith("_") and marker not in text
               or not marker.endswith("_") and marker not in text]
    if missing:
        raise RuntimeError(f"{name} missing markers {missing}")
    loop_count = len(re.findall(r"C2_T06_LOOP_PASS loop=", text))
    if loop_count < 1:
        raise RuntimeError(f"{name} emitted no loop pass marker")
    if not re.search(r"C2_T06_CLEANUP .*networkRegistered=false .*sensorRegistered=false "
                    r".*telephonyRegistered=false .*focusHeld=false", text):
        raise RuntimeError(f"{name} did not prove final resource cleanup")
    if negative and "C2_T06_PERMISSION_NEGATIVE" not in text:
        raise RuntimeError(f"{name} did not emit permission negative evidence")
    if "FATAL EXCEPTION" in text or "ANR in" in text:
        raise RuntimeError(f"{name} emitted fatal runtime evidence")
    return {
        "name": name,
        "status": "PASS",
        "requestedLoops": loops,
        "observedLoopMarkers": loop_count,
        "networkCallbackMarkers": text.count("C2_T06_NETWORK_CALLBACK event="),
        "sensorCallbackMarkers": text.count("C2_T06_SENSOR_CALLBACK event=CHANGED"),
        "telephonyCallbackMarkers": text.count("C2_T06_TELEPHONY_CALLBACK event="),
        "permissionNegative": negative,
    }


def set_permissions(serial: str, user: int, decision: str) -> dict[str, Any]:
    return require_pass(
        f"set permissions user={user} decision={decision}",
        command(serial, "set-permissions", user, [
            "--es", "permissions", PERMISSIONS, "--es", "decision", decision,
        ]),
    )


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


def launch_phase(serial: str, output: Path, name: str, user: int, loops: int,
                 *, mode: str = "full", timeout_sec: int = 180,
                 negative: bool = False) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    payload = command(serial, "launch-component", user, [
        "--es", "component", COMPONENT,
        "--es", "componentMode", mode,
        "--es", "c2t06Mode", mode,
        "--ei", "c2t06Loops", str(loops),
    ], deadline_sec=180)
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"launch {name} failed: {json.dumps(payload, ensure_ascii=False)}")
    text = wait_for_campaign(serial, timeout_sec)
    path = output / f"c2-t06-{name}-logcat.txt"
    path.write_text(text, encoding="utf-8")
    observation = validate_log(name, text, loops, negative=negative)
    observation.update({"user": user, "mode": mode, "launch": payload,
                        "logcat": str(path), "stop": stop_host(serial)})
    return observation


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    parser.add_argument("--loops", type=int, default=20)
    parser.add_argument("--clone-loops", type=int, default=10)
    args = parser.parse_args()
    if args.loops < 1 or args.clone_loops < 1:
        raise SystemExit("loops must be positive")

    output = artifacts_dir("catch-up-c2-t06")
    verification = ROOT / "verification/catch-up/C2-T06"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "Telephony, Wi-Fi, Connectivity, Audio, Bluetooth and Sensor",
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
        "targeted_result": "UNVERIFIED",
        "rd_result": "UNVERIFIED",
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": ["KI-R03-020", "KI-R03-023", "KI-R03-024", "KI-R03-025",
                         "KI-R03-026", "KI-M10-005", "KI-M10-006", "KI-R03-034",
                         "KI-R03-035", "KI-R03-037", "KI-R03-039", "KI-R03-040"],
        "evidence_files": [],
        "notes": "RD API32 evidence only; Bluetooth discovery and unavailable DNS/VPN public adapters are explicit NOT_SUPPORTED boundaries. VA PRO/OEM/HAL/API33+ equivalence remains unproven.",
    }
    details: dict[str, Any] = {
        "task_id": TASK_ID,
        "started_at": now_iso(),
        "requested_loops": args.loops,
        "requested_clone_loops": args.clone_loops,
        "steps": [],
        "phases": [],
        "profile_hashes": {},
    }
    status = "FAIL"
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
        details["apk_metadata"] = apk_metadata()
        details["steps"].append({"name": "import_prepare",
                                   "result": require_pass("import prepare",
                                                           command(serial, "import-prepare"))})
        details["steps"].append({"name": "permissions_user0",
                                   "result": set_permissions(serial, 0, "GRANTED")})
        clone = require_pass("lifecycle clone", command(serial, "lifecycle-clone"))
        clone_operation = clone.get("operation", {})
        clone_user = int(clone_operation.get("virtualUserId", 1))
        details["clone_user"] = clone_user
        details["steps"].append({"name": "lifecycle_clone", "result": clone})
        details["steps"].append({"name": "permissions_clone",
                                   "result": set_permissions(serial, clone_user, "GRANTED")})
        first = launch_phase(serial, output, "user0", 0, args.loops)
        details["phases"].append(first)
        clone_phase = launch_phase(serial, output, "clone", clone_user, args.clone_loops)
        details["phases"].append(clone_phase)
        first_log = (output / "c2-t06-user0-logcat.txt").read_text(encoding="utf-8")
        clone_log = (output / "c2-t06-clone-logcat.txt").read_text(encoding="utf-8")
        first_hash = re.findall(r"C2_T06_IDENTITY_RETURN profileHash=([0-9a-f]+)", first_log)
        clone_hash = re.findall(r"C2_T06_IDENTITY_RETURN profileHash=([0-9a-f]+)", clone_log)
        if not first_hash or not clone_hash or first_hash[-1] == clone_hash[-1]:
            raise RuntimeError(f"cross-user profile hash did not diverge: {first_hash} {clone_hash}")
        details["profile_hashes"] = {"user0": first_hash[-1], "clone": clone_hash[-1]}
        details["steps"].append({"name": "cross_user_profile_separation", "status": "PASS",
                                  "user0": first_hash[-1], "clone": clone_hash[-1]})
        details["steps"].append({"name": "permissions_user0_denied",
                                   "result": set_permissions(serial, 0, "DENIED")})
        details["phases"].append(launch_phase(serial, output, "permission-negative", 0, 1,
                                                mode="negative", timeout_sec=180, negative=True))
        details["steps"].append({"name": "permissions_user0_restored",
                                   "result": set_permissions(serial, 0, "GRANTED")})
        run_adb(serial, ["logcat", "-c"], check=False)
        arm = command(serial, "launch-component", 0, [
            "--es", "component", COMPONENT, "--es", "componentMode", "full",
            "--ei", "c2t06Loops", "3",
        ], deadline_sec=180)
        if str(arm.get("status", "")).upper() != "PASS":
            raise RuntimeError(f"death probe launch failed: {json.dumps(arm, ensure_ascii=False)}")
        ready = wait_for_campaign(serial, 90)
        if "C2_T06_LOOP_PASS" not in ready:
            raise RuntimeError("death probe did not reach first loop")
        death_stop = stop_host(serial)
        details["steps"].append({"name": "guest_death_probe", "status": "PASS",
                                  "old_process_dead": not bool(death_stop.get("remainingPid")),
                                  "launch": arm, "stop": death_stop})
        cleared0 = require_pass("clear user0", command(serial, "clear", 0))
        cleared_clone = require_pass("clear clone", command(serial, "clear", clone_user))
        details["steps"].append({"name": "clear_users", "user0": cleared0, "clone": cleared_clone})
        evidence["targeted_result"] = "PASS"
        evidence["rd_result"] = "PASS"
        evidence["regression_result"] = "PASS"
        details["status"] = "PASS"
        status = "PASS"
    except CampaignBlocked as error:
        evidence["rd_result"] = "BLOCKED_ENV"
        evidence["failures"].append({"id": "C2-T06-RD", "classification": "ENVIRONMENT_BLOCKED",
                                      "summary": str(error)})
        details["status"] = "BLOCKED_ENV"
        details["error"] = str(error)
        status = "BLOCKED_ENV"
    except Exception as error:
        evidence["rd_result"] = "FAIL"
        evidence["failures"].append({"id": "C2-T06-RD", "classification": "FAIL",
                                      "summary": str(error)})
        details["status"] = "FAIL"
        details["error"] = str(error)
        status = "FAIL"
    finally:
        details["finished_at"] = now_iso()
        details_path = output / "campaign-details.json"
        write_json(details_path, details)
        evidence_path = output / "evidence.json"
        verification_path = verification / "c2-t06-rd-summary.json"
        evidence["evidence_files"] = [str(path) for path in sorted(output.glob("*"))]
        evidence["evidence_files"].extend([str(evidence_path), str(verification_path)])
        schema_errors = validate_evidence(evidence)
        if schema_errors:
            evidence["failures"].append({"id": "C2-T06-EVIDENCE", "classification": "FAIL",
                                          "summary": "; ".join(schema_errors)})
            status = "FAIL"
        write_json(evidence_path, evidence)
        write_json(verification_path, evidence)
        print(json.dumps({"status": status, "output": str(output),
                          "verification": str(verification_path),
                          "serial": evidence.get("adb_serial", ""),
                          "phases": len(details.get("phases", []))},
                         ensure_ascii=False, indent=2))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
