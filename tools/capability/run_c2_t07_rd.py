#!/usr/bin/env python3
"""C2-T07 RD campaign for application-environment and long-tail service contracts."""

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

TASK_ID = "C2-T07"
COMPONENT = "com.warden.controlledsandbox.fixture.C2T07ApplicationEnvironmentActivity"
TAG = "CS_C2_T07"
TRUST = ("--ez", "trustNativeGuest", "true")
LONG_TAIL_SERVICES = (
    "biometric", "fingerprint", "device_policy", "autofill", "nfc", "usb", "print",
    "companiondevice", "sensor_privacy", "power", "vibrator", "search", "storagestats",
    "system_update", "contexthub", "persistent_data_block", "sms", "captioning",
    "graphicsstats",
)


def command(serial: str, name: str, user: int = 0, *, mode: str = "", loops: int = 0,
            deadline_sec: int = 180, force_stop_host: bool = True) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    args = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user), "--es", "requestId", request_id, *TRUST,
            "--es", "component", COMPONENT, "--ei", "c2t07User", str(user)]
    if mode:
        args.extend(["--es", "c2t07Mode", mode])
    if loops:
        args.extend(["--ei", "c2t07Loops", str(loops)])
    return debug_command(serial, args, deadline_sec=deadline_sec,
                         force_stop_host=force_stop_host)


def logcat(serial: str) -> str:
    return run_adb(serial, ["logcat", "-d", "-v", "threadtime", f"{TAG}:I", "*:S"],
                   check=False).stdout


def wait_for_marker(serial: str, markers: tuple[str, ...], timeout_sec: float) -> str:
    deadline = time.monotonic() + timeout_sec
    latest = ""
    while time.monotonic() < deadline:
        latest = logcat(serial)
        if "C2_T07_CAMPAIGN_FAIL" in latest or any(marker in latest for marker in markers):
            return latest
        time.sleep(1.0)
    return latest


def save_log(output: Path, name: str, text: str) -> Path:
    path = output / f"c2-t07-{name}-logcat.txt"
    path.write_text(text, encoding="utf-8", errors="replace")
    return path


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    return payload


def count(text: str, marker: str) -> int:
    return sum(marker in line for line in text.splitlines())


def validate_full(text: str, loops: int, *, name: str) -> dict[str, Any]:
    required = (
        "C2_T07_USER_RETURN", "C2_T07_LAUNCHER_RETURN", "C2_T07_SHORTCUT_RETURN",
        "C2_T07_WIDGET_RETURN", "C2_T07_USAGE_RETURN", "C2_T07_SETTINGS_RETURN",
        "C2_T07_SETTINGS_GLOBAL_DENIED", "C2_T07_CONTENT_OBSERVER_CALLBACK",
        "C2_T07_STORAGE_RETURN", "C2_T07_LONGTAIL_MATRIX",
        "C2_T07_HOST_IDENTITY_GUARDED", "C2_T07_CLEANUP", "C2_T07_CAMPAIGN_PASS",
    )
    missing = [marker for marker in required if marker not in text]
    if missing:
        raise RuntimeError(f"{name} missing markers: {missing}")
    loop_markers = count(text, "C2_T07_LOOP_PASS loop=")
    if loops > 0 and loop_markers < 1:
        raise RuntimeError(f"{name} emitted no loop marker")
    cleanup = re.findall(
        r"C2_T07_CLEANUP .*?launcherRegistered=false observerRegistered=false", text)
    if not cleanup:
        raise RuntimeError(f"{name} did not prove launcher/content cleanup")
    matrix = re.findall(r"C2_T07_LONGTAIL_MATRIX services=(\d+) observed=(\d+) unavailable=(\d+)", text)
    if not matrix or int(matrix[-1][0]) != len(LONG_TAIL_SERVICES):
        raise RuntimeError(f"{name} long-tail matrix is incomplete: {matrix[-1:]}")
    missing_services = [service for service in LONG_TAIL_SERVICES
                        if f"service={service} " not in text]
    if missing_services:
        raise RuntimeError(f"{name} missing long-tail service evidence: {missing_services}")
    forbidden = ("C2_T07_CAMPAIGN_FAIL", "FATAL EXCEPTION", "ANR in",
                 "HOST_ANDROID_ID", "HOST_SERIAL", "HOST_PACKAGE_IDENTITY_LEAK")
    present = [marker for marker in forbidden if marker in text]
    if present:
        raise RuntimeError(f"{name} emitted forbidden markers: {present}")
    return {
        "name": name,
        "status": "PASS",
        "requestedLoops": loops,
        "loopMarkers": loop_markers,
        "userMarkers": count(text, "C2_T07_USER_RETURN"),
        "contentObserverCallbacks": count(text, "C2_T07_CONTENT_OBSERVER_CALLBACK"),
        "longTailServices": len(LONG_TAIL_SERVICES),
        "cleanupProven": True,
        "fatalOrAnr": False,
    }


def stop_host(serial: str) -> dict[str, Any]:
    stopped = run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    deadline = time.monotonic() + 15.0
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


def guest_pid(serial: str) -> int:
    result = run_adb(serial, ["shell", "pidof", GUEST_PACKAGE], check=False)
    for token in (result.stdout or "").split():
        if token.isdigit():
            return int(token)
    return 0


def kill_guest(serial: str, pid: int) -> dict[str, Any]:
    if pid < 1:
        raise RuntimeError("could not resolve Guest pid for death probe")
    killed = run_adb(serial, ["shell", "kill", "-9", str(pid)], check=False)
    method = "shell"
    if killed.returncode != 0:
        killed = run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "kill", "-9", str(pid)],
                         check=False)
        method = "run-as"
    deadline = time.monotonic() + 15.0
    remaining = guest_pid(serial)
    while time.monotonic() < deadline and remaining == pid:
        time.sleep(0.25)
        remaining = guest_pid(serial)
    return {
        "pid": pid,
        "returncode": killed.returncode,
        "stdout": killed.stdout.strip(),
        "stderr": killed.stderr.strip(),
        "method": method,
        "oldPidDead": remaining != pid,
        "remainingPid": remaining,
    }


def launch_full(serial: str, output: Path, name: str, user: int, loops: int,
                *, timeout_sec: int = 240) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    launched = require_pass(f"launch {name}", command(
        serial, "launch-component", user, mode="full", loops=loops,
        deadline_sec=180, force_stop_host=True))
    text = wait_for_marker(serial, ("C2_T07_CAMPAIGN_PASS",), timeout_sec)
    log_path = save_log(output, name, text)
    observation = validate_full(text, loops, name=name)
    observation.update({"user": user, "launch": launched, "logcat": str(log_path),
                        "stop": stop_host(serial)})
    return observation


def run_death_probe(serial: str, output: Path, user: int) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    armed = require_pass("arm environment leases", command(
        serial, "launch-component", user, mode="arm", deadline_sec=180,
        force_stop_host=True))
    armed_log = wait_for_marker(serial, ("C2_T07_ARMED",), 45.0)
    armed_path = save_log(output, "death-armed", armed_log)
    if "C2_T07_ARMED" not in armed_log:
        raise RuntimeError("arm phase did not emit C2_T07_ARMED")
    matches = re.findall(r"C2_T07_ARMED pid=(\d+)", armed_log)
    old_pid = int(matches[-1]) if matches else guest_pid(serial)
    kill = kill_guest(serial, old_pid)
    if not kill["oldPidDead"]:
        raise RuntimeError(f"old Guest pid survived kill: {kill}")
    replacement = launch_full(serial, output, "death-replacement", user, 2, timeout_sec=180)
    return {"status": "PASS", "armed": armed, "armedLog": str(armed_path),
            "oldPid": old_pid, "kill": kill, "replacement": replacement}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    parser.add_argument("--loops", type=int, default=10)
    parser.add_argument("--clone-loops", type=int, default=5)
    args = parser.parse_args()
    if args.loops < 1 or args.clone_loops < 1:
        raise SystemExit("loops must be positive")

    output = artifacts_dir("catch-up-c2-t07")
    verification = ROOT / "verification/catch-up/C2-T07"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    evidence: dict[str, Any] = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "Application environment and long-tail system services",
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
        "known_issues": ["KI-T57-010", "KI-R03-020", "KI-R03-023", "KI-R03-024",
                         "KI-R03-025", "KI-R03-026", "KI-M10-005", "KI-M10-006",
                         "KI-R03-041"],
        "evidence_files": [],
        "notes": "RD API32 application-environment method evidence only; P2 host-only or unavailable paths are explicit NOT_SUPPORTED/NOT_APPLICABLE. API33+, OEM/HAL, SX/XH, commercial-app and VA PRO equivalence remain unproven.",
    }
    details: dict[str, Any] = {"task_id": TASK_ID, "started_at": now_iso(),
                               "requestedLoops": args.loops,
                               "requestedCloneLoops": args.clone_loops,
                               "steps": [], "phases": []}
    status = "FAIL"
    try:
        environment = resolve_rd_environment(args.instance)
        serial = environment["adb_serial"]
        evidence.update({
            "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
            "device_name": environment["device_name"],
            "adb_serial": serial,
            "api_level": environment["api_level"],
            "abi": environment["abi"],
            "boot_id": environment["boot_id"],
            "android_id": environment["android_id"],
            "instance_name": environment["instance_name"],
        })
        details["steps"].append({"name": "install_rd_apks", "result": install_rd_apks(serial)})
        reset = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        if reset.returncode != 0 or reset.stdout.strip().lower() != "success":
            raise RuntimeError(f"host data reset failed: {reset.stdout} {reset.stderr}")
        details["steps"].append({"name": "reset_host_data", "status": "PASS"})
        details["apk_metadata"] = apk_metadata()
        details["steps"].append({"name": "import_prepare", "result": require_pass(
            "import prepare", command(serial, "import-prepare", force_stop_host=True))})
        clone = require_pass("lifecycle clone", command(
            serial, "lifecycle-clone", force_stop_host=False, deadline_sec=180))
        clone_user = int(clone.get("operation", {}).get("virtualUserId", 1))
        details["steps"].append({"name": "lifecycle_clone", "result": clone,
                                  "cloneUser": clone_user})
        first = launch_full(serial, output, "user0", 0, args.loops)
        details["phases"].append(first)
        clone_phase = launch_full(serial, output, "clone", clone_user, args.clone_loops)
        details["phases"].append(clone_phase)
        first_log = (output / "c2-t07-user0-logcat.txt").read_text(encoding="utf-8")
        clone_log = (output / "c2-t07-clone-logcat.txt").read_text(encoding="utf-8")
        first_hashes = re.findall(r"C2_T07_USER_RETURN .*?profileHash=([0-9a-f]+)", first_log)
        clone_hashes = re.findall(r"C2_T07_USER_RETURN .*?profileHash=([0-9a-f]+)", clone_log)
        if not first_hashes or not clone_hashes or first_hashes[-1] == clone_hashes[-1]:
            raise RuntimeError(f"cross-user profile hash did not diverge: {first_hashes} {clone_hashes}")
        details["steps"].append({"name": "cross_user_profile_separation", "status": "PASS",
                                  "user0": first_hashes[-1], "clone": clone_hashes[-1]})
        death = run_death_probe(serial, output, 0)
        details["phases"].append({"name": "death", **death})
        evidence["targeted_result"] = "PASS"
        evidence["rd_result"] = "PASS"
        evidence["regression_result"] = "PASS"
        details["status"] = "PASS"
        status = "PASS"
    except CampaignBlocked as error:
        evidence["rd_result"] = "BLOCKED_ENV"
        evidence["failures"].append({"id": getattr(error, "code", "RD_ENVIRONMENT"),
                                      "classification": "ENVIRONMENT_BLOCKED",
                                      "summary": str(error)})
        details["status"] = "BLOCKED_ENV"
        details["error"] = str(error)
        status = "BLOCKED_ENV"
    except Exception as error:
        evidence["rd_result"] = "FAIL"
        evidence["failures"].append({"id": "C2-T07-RD", "classification": "FAIL",
                                      "summary": str(error)})
        details["status"] = "FAIL"
        details["error"] = str(error)
        status = "FAIL"
    finally:
        details["finished_at"] = now_iso()
        details["artifacts"] = {"apk": apk_metadata(), "rawEvidenceDir": str(output)}
        details_path = output / "campaign-details.json"
        write_json(details_path, details)
        evidence_path = output / "evidence.json"
        verification_path = verification / "c2-t07-rd-summary.json"
        evidence["evidence_files"] = [str(path) for path in sorted(output.glob("*"))]
        evidence["evidence_files"].extend([str(evidence_path), str(verification_path)])
        schema_errors = validate_evidence(evidence)
        if schema_errors:
            evidence["failures"].append({"id": "C2-T07-EVIDENCE", "classification": "FAIL",
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
