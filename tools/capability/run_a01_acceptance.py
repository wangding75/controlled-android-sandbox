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

    # 3. ActivityResult delivery
    if api in {"32", "35"}:
        print("Testing ActivityResult transport...")
        comp_result = "com.warden.controlledsandbox.fixture.FrameworkActivityResultParentActivity"
        r_result = debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_result, *TRUST],
            deadline_sec=60,
        )
        result_status = r_result.get("status")
        results["tests"]["activity_result"] = {
            "component": comp_result,
            "status": result_status,
            "operation": (r_result.get("result") or {}).get("operation"),
        }
        print(f"  -> {result_status}")

    # 4. Task semantics & onNewIntent reuse (singleTask)
    if api in {"32", "35"}:
        print("Testing TaskSemantics (singleTask / onNewIntent reuse)...")
        comp_task = "com.warden.controlledsandbox.fixture.TaskSemanticsProbeActivity"
        r_task = debug_command(
            serial,
            ["-e", "command", "launch-component", "-e", "package", "com.warden.controlledsandbox.fixture", "-e", "component", comp_task, *TRUST],
            deadline_sec=60,
        )
        task_status = r_task.get("status")
        results["tests"]["task_semantics"] = {
            "component": comp_task,
            "status": task_status,
            "operation": (r_task.get("result") or {}).get("operation"),
        }
        print(f"  -> {task_status}")

    # 5. Process Death & Recovery
    print("Testing Guest Process Death & Clean Recovery...")
    pkg_death = "com.warden.controlledsandbox.fixture.scale" if api == "36" else "com.warden.controlledsandbox.fixture"
    l1 = debug_command(serial, ["-e", "command", "import-launch", "-e", "package", pkg_death, *TRUST], deadline_sec=60)
    pid1 = run_adb(serial, ["shell", "pidof", pkg_death], check=False).stdout.strip()
    if not pid1:
        ps_out = run_adb(serial, ["shell", "ps", "-A"], check=False).stdout or ""
        for line in ps_out.splitlines():
            if pkg_death in line or f"{HOST_PACKAGE}:guest" in line:
                pid1 = line.split()[1]
                break
    print(f"  Initial PID: {pid1}")
    if pid1:
        run_adb(serial, ["shell", "kill", "-9", pid1], check=False)
        time.sleep(1)
    l2 = debug_command(serial, ["-e", "command", "import-launch", "-e", "package", pkg_death, *TRUST], deadline_sec=60)
    pid2 = run_adb(serial, ["shell", "pidof", pkg_death], check=False).stdout.strip()
    if not pid2:
        ps_out = run_adb(serial, ["shell", "ps", "-A"], check=False).stdout or ""
        for line in ps_out.splitlines():
            if pkg_death in line or f"{HOST_PACKAGE}:guest" in line:
                pid2 = line.split()[1]
                break
    print(f"  PID after recovery: {pid2}")
    recovery_pass = bool(pid1 and pid2 and pid1 != pid2 and l2.get("status") == "PASS")
    results["tests"]["process_death_recovery"] = {
        "pid_before": pid1,
        "pid_after": pid2,
        "relaunch_status": l2.get("status"),
        "pass": recovery_pass,
    }
    print(f"  -> Recovery Confirmed: {recovery_pass}")

    # 6. Neighboring components (Service & PendingIntent)
    if api in {"32", "35"}:
        print("Testing Service neighbor smoke...")
        r_svc = debug_command(
            serial,
            ["-e", "command", "slot-campaign", "-e", "package", "com.warden.controlledsandbox.fixture", "--ei", "slotTarget", "0", *TRUST],
            deadline_sec=60,
        )
        svc_status = r_svc.get("status")
        results["tests"]["service_neighbor"] = {
            "status": svc_status,
            "operation": (r_svc.get("result") or {}).get("operation"),
        }
        print(f"  -> Service: {svc_status}")

        print("Testing PendingIntent neighbor smoke...")
        r_pi = debug_command(
            serial,
            ["-e", "command", "pi-system-holder", "-e", "package", "com.warden.controlledsandbox.fixture", *TRUST],
            deadline_sec=60,
        )
        pi_status = r_pi.get("status")
        results["tests"]["pending_intent_neighbor"] = {
            "status": pi_status,
            "operation": (r_pi.get("result") or {}).get("operation"),
        }
        print(f"  -> PendingIntent: {pi_status}")

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
