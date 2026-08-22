#!/usr/bin/env python3
"""C2-T02 RD campaign for PMS, permission, AppOps and attribution identity."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, install_rd_apks, resolve_rd_environment, run_adb

TASK_ID = "C2-T02"
GUEST_PACKAGE = "com.warden.controlledsandbox.fixture"
PROBE_COMPONENT = "com.warden.controlledsandbox.fixture.PmsPermissionAttributionProbeActivity"
CAMERA_PERMISSION = "android.permission.CAMERA"
CAMERA_OP = "android:camera"
TRUST = ("--ez", "trustNativeGuest", "true")


def command(serial: str, name: str, user: int = 0,
            extra: list[str] | None = None) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    extras = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
              "--ei", "user", str(user), "--es", "requestId", request_id, *TRUST]
    if extra:
        extras.extend(extra)
    return debug_command(serial, extras, deadline_sec=120)


def result_of(payload: dict[str, Any]) -> dict[str, Any]:
    result = payload.get("result")
    if not isinstance(result, dict):
        raise RuntimeError(f"debug command returned no result: {json.dumps(payload, ensure_ascii=False)}")
    return result


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    return result_of(payload)


def policy_state(serial: str, user: int) -> dict[str, Any]:
    return require_pass(f"policy-state user={user}", command(serial, "policy-state", user))


def extract_probe(logcat: str) -> dict[str, Any]:
    failures = [line for line in logcat.splitlines() if "C2_T02_PROBE_FAIL " in line]
    if failures:
        raise RuntimeError(f"Guest probe failed: {failures[-1]}")
    lines = [line for line in logcat.splitlines() if "C2_T02_PROBE_PASS " in line]
    if not lines:
        raise RuntimeError("Guest probe did not emit C2_T02_PROBE_PASS")
    encoded = lines[-1].split("C2_T02_PROBE_PASS ", 1)[1].strip()
    try:
        value = json.loads(encoded)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"Guest probe emitted invalid JSON: {encoded}") from error
    if not isinstance(value, dict):
        raise RuntimeError("Guest probe result is not an object")
    return value


def validate_probe(probe: dict[str, Any], *, expected_permission: int,
                   expected_app_op: int) -> None:
    if probe.get("packageName") != GUEST_PACKAGE:
        raise RuntimeError("Guest probe package identity mismatch")
    if probe.get("opPackageName") != GUEST_PACKAGE:
        raise RuntimeError("Guest Context opPackageName leaked Host identity")
    if probe.get("contextCamera") != expected_permission \
            or probe.get("packageCamera") != expected_permission \
            or probe.get("hostPackageCamera") != -1:
        raise RuntimeError(f"permission projection mismatch: {probe}")
    for key in ("appOpsCheck", "appOpsNote", "appOpsStart", "appOpsProxy"):
        if probe.get(key) != expected_app_op:
            raise RuntimeError(f"AppOps {key} mismatch: {probe}")
    if probe.get("contextInternet") != 0:
        raise RuntimeError(f"declared default Internet permission not granted: {probe}")
    attribution = probe.get("attribution") or {}
    package_uid = probe.get("packageUid", -1)
    if attribution.get("packageName") != GUEST_PACKAGE \
            or attribution.get("uid") != package_uid:
        raise RuntimeError(f"Guest AttributionSource identity mismatch: {probe}")
    callback = probe.get("callback") or {}
    if callback.get("callingPackage") != GUEST_PACKAGE \
            or callback.get("callingAttributionPackage") != GUEST_PACKAGE \
            or callback.get("callingAttributionUid") != package_uid:
        raise RuntimeError(f"callback attribution identity mismatch: {probe}")


def launch_probe(serial: str, user: int, expected_permission: int,
                 expected_app_op: int, output: Path) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    launched = require_pass(
        f"launch C2-T02 probe user={user}",
        command(serial, "launch-component", user,
                ["--es", "component", PROBE_COMPONENT]),
    )
    time.sleep(1.5)
    logcat = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False).stdout
    log_path = output / f"c2-t02-user{user}-logcat.txt"
    log_path.write_text(logcat, encoding="utf-8")
    probe = extract_probe(logcat)
    validate_probe(probe, expected_permission=expected_permission,
                   expected_app_op=expected_app_op)
    return {"launch": launched, "probe": probe, "logcat": str(log_path)}


def state_row(state: dict[str, Any]) -> dict[str, Any]:
    operation = state.get("operation") or {}
    return {
        "status": operation.get("status"),
        "user": state.get("virtualUserId"),
        "cameraPermission": operation.get("cameraPermission"),
        "internetPermission": operation.get("internetPermission"),
        "cameraAppOp": operation.get("cameraAppOp"),
        "cameraAppOpPolicy": operation.get("cameraAppOpPolicy"),
        "cameraAppOpResetReason": operation.get("cameraAppOpResetReason"),
        "cameraAppOpResetSequence": operation.get("cameraAppOpResetSequence"),
        "recordAudioAppOp": operation.get("recordAudioAppOp"),
        "permissionCount": operation.get("permissionCount"),
        "appOpCount": operation.get("appOpCount"),
    }


def require_default_state(label: str, state: dict[str, Any], *, require_policy_reset: bool = False) -> None:
    row = state_row(state)
    effective_default = (row["cameraAppOp"] == "DEFAULT"
                         or row["cameraAppOp"] == "IGNORED")
    raw_default = (row["cameraAppOpPolicy"] == "DEFAULT"
                   if require_policy_reset else True)
    if row["cameraPermission"] != "DEFAULT" or not effective_default or not raw_default:
        raise RuntimeError(f"{label} did not converge to DEFAULT: {row}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    args = parser.parse_args()

    output = artifacts_dir("catch-up-c2-t02")
    verification = ROOT / "verification/catch-up/C2-T02"
    verification.mkdir(parents=True, exist_ok=True)
    evidence: dict[str, Any] = {
        "task_id": TASK_ID,
        "started_at": now_iso(),
        "host_os": host_os(),
        "git": git_identity(),
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "method_matrix": [
            {"surface": "PMS", "methods": ["getApplicationInfo", "getPackageInfo",
                "resolveActivity", "queryIntentActivities", "checkPermission"],
             "evidence": "guest probe"},
            {"surface": "Permission", "methods": ["Context.checkSelfPermission",
                "IPermissionController.checkPermission"], "evidence": "guest probe"},
            {"surface": "AppOps", "methods": ["checkOpNoThrow", "noteOpNoThrow",
                "startOpNoThrow", "finishOp", "noteProxyOpNoThrow", "checkPackage"],
             "evidence": "guest probe"},
            {"surface": "Attribution", "methods": ["Context.getAttributionSource",
                "ContentProvider.getCallingAttributionSource"], "evidence": "guest/provider callback"},
        ],
        "steps": [],
        "negative_tests": [],
        "cross_user_isolation": [],
        "known_issues": ["KI-R03-020", "KI-R03-023", "KI-R03-024", "KI-R03-025",
                          "KI-R03-026", "KI-M10-005", "KI-M10-006", "KI-M10-007"],
    }
    try:
        environment = resolve_rd_environment(args.instance)
        evidence["environment"] = environment
        reset = run_adb(environment["adb_serial"], ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        evidence["steps"].append({"name": "reset_host_data", "result": {
            "returncode": reset.returncode, "stdout": reset.stdout.strip(),
            "stderr": reset.stderr.strip()}})
        if reset.returncode != 0 or reset.stdout.strip().lower() != "success":
            raise RuntimeError(f"host data reset failed: {reset.stdout} {reset.stderr}")
        serial = environment["adb_serial"]
        evidence["steps"].append({"name": "install_rd_apks", "result": install_rd_apks(serial)})
        grant = run_adb(serial, ["shell", "pm", "grant", HOST_PACKAGE, CAMERA_PERMISSION], check=False)
        evidence["steps"].append({"name": "grant_host_camera_for_virtual_allow", "result": {
            "returncode": grant.returncode, "stdout": grant.stdout.strip(),
            "stderr": grant.stderr.strip()}})
        if grant.returncode != 0:
            raise RuntimeError(f"host CAMERA grant failed: {grant.stdout} {grant.stderr}")
        evidence["steps"].append({"name": "import_prepare", "result":
                                    require_pass("import prepare", command(serial, "import-prepare"))})
        evidence["steps"].append({"name": "package_state_campaign", "result":
                                    require_pass("package state campaign", command(serial, "package-state-campaign"))})

        evidence["steps"].append({"name": "set_user0_permission_denied", "result":
            require_pass("set user0 permission denied", command(serial, "set-permissions", 0,
                ["--es", "permissions", CAMERA_PERMISSION, "--es", "decision", "DENIED"]))})
        evidence["steps"].append({"name": "set_user0_appop_ignored", "result":
            require_pass("set user0 AppOps ignored", command(serial, "set-appops", 0,
                ["--es", "appOps", CAMERA_OP, "--es", "mode", "IGNORED"]))})
        evidence["steps"].append({"name": "set_user1_permission_granted", "result":
            require_pass("set user1 permission granted", command(serial, "set-permissions", 1,
                ["--es", "permissions", CAMERA_PERMISSION, "--es", "decision", "GRANTED"]))})
        evidence["steps"].append({"name": "set_user1_appop_allowed", "result":
            require_pass("set user1 AppOps allowed", command(serial, "set-appops", 1,
                ["--es", "appOps", CAMERA_OP, "--es", "mode", "ALLOWED"]))})

        user0_state = policy_state(serial, 0)
        user1_state = policy_state(serial, 1)
        evidence["cross_user_isolation"].append({"user0": state_row(user0_state),
                                                  "user1": state_row(user1_state)})
        if (user0_state.get("operation", {}).get("cameraPermission") != "DENIED"
                or user0_state.get("operation", {}).get("cameraAppOp") != "IGNORED"
                or user1_state.get("operation", {}).get("cameraPermission") != "GRANTED"
                or user1_state.get("operation", {}).get("cameraAppOp") != "ALLOWED"):
            raise RuntimeError("cross-user policy transition did not persist independently")

        user0_probe = launch_probe(serial, 0, -1, 1, output)
        user1_probe = launch_probe(serial, 1, 0, 0, output)
        evidence["steps"].extend([
            {"name": "guest_probe_user0_denied", "result": user0_probe},
            {"name": "guest_probe_user1_allowed", "result": user1_probe},
        ])
        evidence["negative_tests"].extend([
            {"name": "host_package_pms_lookup", "result": "NameNotFound-shaped"},
            {"name": "host_package_permission", "result": "PERMISSION_DENIED"},
            {"name": "host_package_appops", "result": "VIRTUAL_APPOPS_HOST_PACKAGE_HIDDEN"},
        ])

        evidence["steps"].append({"name": "clear_user0", "result":
                                    require_pass("clear user0", command(serial, "clear", 0))})
        cleared = policy_state(serial, 0)
        require_default_state("clear user0", cleared, require_policy_reset=True)
        evidence["steps"].append({"name": "state_after_clear_user0", "result": cleared})

        evidence["steps"].append({"name": "set_user1_policy_before_delete", "result":
            require_pass("set user1 policy before delete", command(serial, "set-permissions", 1,
                ["--es", "permissions", CAMERA_PERMISSION, "--es", "decision", "DENIED"]))})
        evidence["steps"].append({"name": "set_user1_appop_before_delete", "result":
            require_pass("set user1 AppOps before delete", command(serial, "set-appops", 1,
                ["--es", "appOps", CAMERA_OP, "--es", "mode", "IGNORED"]))})
        evidence["steps"].append({"name": "delete_user1", "result":
                                    require_pass("delete user1", command(serial, "delete", 1))})
        deleted = policy_state(serial, 1)
        require_default_state("delete user1 and recreate", deleted)
        user0_after_delete = policy_state(serial, 0)
        require_default_state("user0 after user1 delete", user0_after_delete,
                             require_policy_reset=True)
        evidence["steps"].append({"name": "state_after_delete_user1", "result": deleted})
        evidence["cross_user_isolation"].append({"name": "after_delete_user1",
                                                  "user0": state_row(user0_after_delete),
                                                  "user1": state_row(deleted)})
        evidence["status"] = "PASS"
        evidence["finished_at"] = now_iso()
    except Exception as error:
        evidence["status"] = "FAIL"
        evidence["error"] = str(error)
        evidence["finished_at"] = now_iso()
        write_json(output / "evidence.json", evidence)
        write_json(verification / "c2-t02-rd-summary.json", evidence)
        print(json.dumps({"status": "FAIL", "output": str(output), "error": str(error)},
                         ensure_ascii=False, indent=2))
        return 1

    write_json(output / "evidence.json", evidence)
    write_json(verification / "c2-t02-rd-summary.json", evidence)
    print(json.dumps({"status": "PASS", "output": str(output),
                      "verification": str(verification / "c2-t02-rd-summary.json"),
                      "serial": evidence["environment"]["adb_serial"],
                      "steps": len(evidence["steps"])}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
