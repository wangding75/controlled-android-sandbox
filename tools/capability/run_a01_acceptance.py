#!/usr/bin/env python3
"""T57-R03-P4-FIX02-A01 Full Acceptance Matrix Runner."""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, validate_evidence, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, apk_metadata, run_adb

CAMPAIGN_ID = "T57-R03-P4-FIX02-A01"
TRUST = ("--ez", "trustNativeGuest", "true")
SCALE_INDICES = (0, 63, 64, 95, 127)


def check_logcat_marker(
    serial: str,
    pass_marker: str,
    fail_marker: str | None = None,
    wait_sec: float = 2.0,
) -> dict[str, Any]:
    time.sleep(wait_sec)
    logcat = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
    has_pass = pass_marker in logcat
    has_fail = (fail_marker in logcat) if fail_marker else False
    if has_fail:
        verdict = "FIXTURE_SEMANTIC_FAIL"
    elif has_pass:
        verdict = "FIXTURE_SEMANTIC_PASS"
    else:
        verdict = "FIXTURE_SEMANTIC_TIMEOUT"
    return {
        "verdict": verdict,
        "pass_marker_found": has_pass,
        "fail_marker_found": has_fail,
    }


def run_device_matrix(serial: str, api: str, model: str) -> dict[str, Any]:
    print(f"\n==========================================")
    print(f"Running A01 Acceptance Matrix on {serial} (API {api}, Model {model})")
    print(f"==========================================")

    device_dir = artifacts_dir("a01-acceptance")
    results: dict[str, Any] = {
        "serial": serial,
        "api": api,
        "model": model,
        "tests": {},
    }

    # Clear logcat
    run_adb(serial, ["logcat", "-c"], check=False)

    # 1. Scale boundary indices (0, 63, 64, 95, 127)
    scale_results = {}
    for idx in SCALE_INDICES:
        comp = f"com.warden.controlledsandbox.fixture.scale.ScaleActivity{idx:03d}"
        print(f"Testing ScaleActivity{idx:03d}...")
        r = debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture.scale", "-e", "component", comp, *TRUST],
            deadline_sec=60,
        )
        status = r.get("status")
        scale_results[f"ScaleActivity{idx:03d}"] = {
            "status": status,
            "operation": (r.get("result") or {}).get("operation"),
        }
        print(f"  -> {status}")
    results["tests"]["scale_boundary_indices"] = scale_results

    # 2. Basic Package / Activity Launch
    print("Testing basic package launch...")
    pkg = "com.warden.controlledsandbox.fixture.scale" if api == "36" else "com.warden.controlledsandbox.fixture"
    r_basic = debug_command(serial, ["-e", "command", "import-launch", "-e", "package", pkg, *TRUST], deadline_sec=60)
    basic_status = r_basic.get("status")
    results["tests"]["basic_launch"] = {
        "package": pkg,
        "status": basic_status,
        "operation": (r_basic.get("result") or {}).get("operation"),
    }
    print(f"  -> {basic_status}")

    # 3. ActivityResult delivery (All APIs)
    print("Testing ActivityResult transport...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_result = "com.warden.controlledsandbox.fixture.FrameworkActivityResultParentActivity"
    r_result = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_result, *TRUST],
        deadline_sec=60,
    )
    result_cmd_status = r_result.get("status")
    result_marker = check_logcat_marker(
        serial,
        "FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS",
        "FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL",
        wait_sec=2.5,
    )
    results["tests"]["activity_result"] = {
        "component": comp_result,
        "command_status": result_cmd_status,
        "semantic_verdict": result_marker["verdict"],
        "pass": result_cmd_status == "PASS" and result_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
        "operation": (r_result.get("result") or {}).get("operation"),
    }
    print(f"  -> Command: {result_cmd_status}, Semantic: {result_marker['verdict']}")

    # 4. Task Mode Matrix (standard, singleTop, singleTask, CLEAR_TOP, REORDER_TO_FRONT)
    task_matrix_results: dict[str, Any] = {}

    # 4a. standard
    print("Testing Task Mode: standard...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_std = "com.warden.controlledsandbox.fixture.StandardTaskProbeActivity"
    r_std = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_std, *TRUST],
        deadline_sec=60,
    )
    std_marker = check_logcat_marker(serial, "FRAMEWORK_PROBE_TASK_STANDARD_PASS", wait_sec=2.0)
    task_matrix_results["standard"] = {
        "component": comp_std,
        "command_status": r_std.get("status"),
        "semantic_verdict": std_marker["verdict"],
        "pass": r_std.get("status") == "PASS" and std_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }
    print(f"  -> standard: {std_marker['verdict']}")

    # 4b. singleTop
    print("Testing Task Mode: singleTop...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_stop = "com.warden.controlledsandbox.fixture.SingleTopProbeActivity"
    r_stop = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_stop, *TRUST],
        deadline_sec=60,
    )
    stop_marker = check_logcat_marker(
        serial,
        "FRAMEWORK_PROBE_TASK_SINGLETOP_PASS",
        "FRAMEWORK_PROBE_TASK_SINGLETOP_FAIL",
        wait_sec=2.0,
    )
    task_matrix_results["singleTop"] = {
        "component": comp_stop,
        "command_status": r_stop.get("status"),
        "semantic_verdict": stop_marker["verdict"],
        "pass": r_stop.get("status") == "PASS" and stop_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }
    print(f"  -> singleTop: {stop_marker['verdict']}")

    # 4c. singleTask
    print("Testing Task Mode: singleTask...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_task = "com.warden.controlledsandbox.fixture.TaskSemanticsProbeActivity"
    r_task = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_task, *TRUST],
        deadline_sec=60,
    )
    task_cmd_status = r_task.get("status")
    task_marker = check_logcat_marker(
        serial,
        "FRAMEWORK_PROBE_TASK_REUSE_PASS",
        "FRAMEWORK_PROBE_TASK_REUSE_FAIL",
        wait_sec=2.5,
    )
    task_matrix_results["singleTask"] = {
        "component": comp_task,
        "command_status": task_cmd_status,
        "semantic_verdict": task_marker["verdict"],
        "pass": task_cmd_status == "PASS" and task_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }
    print(f"  -> singleTask: Command: {task_cmd_status}, Semantic: {task_marker['verdict']}")

    # 4d. CLEAR_TOP
    print("Testing Task Mode: CLEAR_TOP...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_clear = "com.warden.controlledsandbox.fixture.ClearTopProbeActivity"
    r_clear = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_clear, *TRUST],
        deadline_sec=60,
    )
    clear_marker = check_logcat_marker(
        serial,
        "FRAMEWORK_PROBE_TASK_CLEAR_TOP_PASS",
        "FRAMEWORK_PROBE_TASK_CLEAR_TOP_FAIL",
        wait_sec=4.0,
    )
    task_matrix_results["CLEAR_TOP"] = {
        "component": comp_clear,
        "command_status": r_clear.get("status"),
        "semantic_verdict": clear_marker["verdict"],
        "pass": r_clear.get("status") == "PASS" and clear_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }
    print(f"  -> CLEAR_TOP: {clear_marker['verdict']}")

    # 4e. REORDER_TO_FRONT
    print("Testing Task Mode: REORDER_TO_FRONT...")
    run_adb(serial, ["logcat", "-c"], check=False)
    comp_reorder = "com.warden.controlledsandbox.fixture.ReorderToFrontProbeActivity"
    r_reorder = debug_command(
        serial,
        ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_reorder, *TRUST],
        deadline_sec=60,
    )
    reorder_marker = check_logcat_marker(
        serial,
        "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_PASS",
        "FRAMEWORK_PROBE_TASK_REORDER_TO_FRONT_FAIL",
        wait_sec=4.0,
    )
    task_matrix_results["REORDER_TO_FRONT"] = {
        "component": comp_reorder,
        "command_status": r_reorder.get("status"),
        "semantic_verdict": reorder_marker["verdict"],
        "pass": r_reorder.get("status") == "PASS" and reorder_marker["verdict"] == "FIXTURE_SEMANTIC_PASS",
    }
    print(f"  -> REORDER_TO_FRONT: {reorder_marker['verdict']}")

    results["tests"]["task_mode_matrix"] = task_matrix_results

    # 5. Process Death and Session Fencing
    print("Testing Process Death and Session Fencing...")
    r_proc1 = debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    op1 = (r_proc1.get("result") or {}).get("operation") or {}
    sess1 = op1.get("sessionId", "")
    gen1 = op1.get("generation", 0)
    pid1 = op1.get("platformPid", 0) or op1.get("pid", 0)

    # Force-kill guest process
    if pid1:
        run_adb(serial, ["shell", "kill", "-9", str(pid1)], check=False)

    # Relaunch to verify session continuity & clean generation
    r_proc2 = debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
        force_stop_host=False,
    )
    op2 = (r_proc2.get("result") or {}).get("operation") or {}
    sess2 = op2.get("sessionId", "")
    gen2 = op2.get("generation", 0)
    pid2 = op2.get("platformPid", 0) or op2.get("pid", 0)

    pid_relaunch_pass = (r_proc2.get("status") == "PASS" and pid2 != 0 and pid2 != pid1)
    session_fencing_pass = (sess1 != "" and sess2 != "" and (sess1 != sess2 or gen2 >= gen1))

    results["tests"]["process_death_and_session_fencing"] = {
        "old_session": sess1,
        "old_generation": gen1,
        "old_pid": pid1,
        "new_session": sess2,
        "new_generation": gen2,
        "new_pid": pid2,
        "pid_death_relaunch": "PID_DEATH_RELAUNCH_PASS" if pid_relaunch_pass else "PID_DEATH_RELAUNCH_FAIL",
        "stale_session_fencing": "STALE_SESSION_FENCING_PASS" if session_fencing_pass else "STALE_SESSION_FENCING_FAIL",
        "pass": pid_relaunch_pass and session_fencing_pass,
    }
    print(f"  -> PID Death Relaunch: {results['tests']['process_death_and_session_fencing']['pid_death_relaunch']}")
    print(f"  -> Stale Session Fencing: {results['tests']['process_death_and_session_fencing']['stale_session_fencing']}")

    # 6. Neighboring components (Service/Provider, PendingIntent) on all APIs
    print("Testing Neighboring components (Service/Provider, PendingIntent)...")
    r_svc = debug_command(
        serial,
        ["-e", "command", "import-prepare", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    svc_status = r_svc.get("status")

    r_pi = debug_command(
        serial,
        ["-e", "command", "pi-system-holder", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
        deadline_sec=60,
    )
    pi_status = r_pi.get("status")

    results["tests"]["neighbor_smoke"] = {
        "service_and_provider_prepare": {
            "status": svc_status,
            "operation": (r_svc.get("result") or {}).get("operation"),
        },
        "pending_intent": {
            "status": pi_status,
            "operation": (r_pi.get("result") or {}).get("operation"),
        },
        "pass": svc_status == "PASS" and pi_status == "PASS",
    }
    print(f"  -> Service/Provider Prepare: {svc_status}, PendingIntent: {pi_status}")

    # Capture raw logs and dumpsys
    logcat_out = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout or ""
    safe_serial = serial.replace(":", "_").replace(".", "_")
    (device_dir / f"{safe_serial}-logcat.txt").write_text(logcat_out, encoding="utf-8", errors="replace")
    dumpsys_out = run_adb(serial, ["shell", "dumpsys", "activity", "activities"], check=False).stdout or ""
    (device_dir / f"{safe_serial}-dumpsys.txt").write_text(dumpsys_out, encoding="utf-8", errors="replace")

    return results


def main() -> int:
    devices_out = run_adb(None, ["devices", "-l"], check=False).stdout or ""
    device_rows = []
    for line in devices_out.splitlines():
        if "\tdevice" not in line and " device " not in line:
            continue
        serial = line.split()[0]
        if serial == "List":
            continue
        api = run_adb(serial, ["shell", "getprop", "ro.build.version.sdk"], check=False).stdout.strip()
        model = run_adb(serial, ["shell", "getprop", "ro.product.model"], check=False).stdout.strip()
        device_rows.append((serial, api, model))

    print(f"Found {len(device_rows)} devices: {device_rows}")

    out_dir = artifacts_dir("a01-acceptance")
    all_results = []
    for serial, api, model in device_rows:
        res = run_device_matrix(serial, api, model)
        all_results.append(res)

    evidence = {
        "campaign": CAMPAIGN_ID,
        "generated_at": now_iso(),
        "git": git_identity(),
        "host_os": host_os(),
        "apk": apk_metadata(),
        "devices": all_results,
    }
    write_json(out_dir / "evidence.json", evidence)
    print("\n==========================================")
    print(f"A01 Acceptance Suite complete. Evidence written to {out_dir / 'evidence.json'}")
    print("==========================================")
    print(json.dumps(all_results, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
