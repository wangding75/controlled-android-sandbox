#!/usr/bin/env python3
"""C4-R01 fail-fast evidence collector for the dynamically resolved RD测试 instance.

This collector is diagnostic only. It performs one attempt per operation, assigns a
request/operation id at the harness boundary, and never retries a failed import or launch.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "capability"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    HOST_PACKAGE,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C4-R01"
TARGET_LABELS = ("夸克", "红果免费短剧", "番茄免费小说")
HOST_ACTIVITY_PREFIX = "com.warden.controlledsandbox.runtime.component.activity.StubActivity"


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def write_json(path: Path, value: Any) -> None:
    write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def transaction_evidence_path(case_dir: Path, safe_name: str) -> Path:
    """Choose a Windows-safe path without dropping the transaction evidence.

    Long C4-R03 lane names can put the final ``*.lastgood`` transaction path at
    the legacy MAX_PATH boundary.  Keep the descriptive path when it fits; if
    it does not, store a short, deterministic file under the case directory's
    parent and record that location in the structured snapshot.
    """
    preferred = case_dir / "transactions" / safe_name
    if len(str(preferred)) < 240:
        return preferred
    digest = hashlib.sha256(safe_name.encode("utf-8")).hexdigest()[:10]
    short_name = f"{digest}-{Path(safe_name).name}"
    return case_dir.parent / "tx" / short_name


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def adb_binary(serial: str, args: list[str], *, timeout: int = 60) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout,
                          check=False)


def text_probe(serial: str, args: list[str]) -> dict[str, Any]:
    result = run_adb(serial, args, check=False)
    return {
        "args": args,
        "returncode": result.returncode,
        "stdout": result.stdout or "",
        "stderr": result.stderr or "",
    }


def aapt2_path() -> Path:
    sdk = Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "build-tools"
    candidates = sorted(sdk.glob("*/aapt2.exe"), reverse=True)
    if not candidates:
        raise RuntimeError("AAPT2_NOT_FOUND")
    return candidates[0]


def badging(apk: Path) -> str:
    result = subprocess.run([str(aapt2_path()), "dump", "badging", str(apk)],
                            capture_output=True, timeout=60, check=False)
    return result.stdout.decode("utf-8", errors="replace")


def value(pattern: str, text: str) -> str:
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else ""


def discover_commercial_samples(serial: str, raw_dir: Path) -> list[dict[str, Any]]:
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"]).stdout
    installed = sorted(line.removeprefix("package:").strip() for line in packages.splitlines()
                       if line.startswith("package:"))
    discoveries: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="c4-r01-badging-") as temporary:
        temporary_root = Path(temporary)
        for package_name in installed:
            paths_result = run_adb(serial, ["shell", "pm", "path", package_name], check=False)
            remote_paths = [line.removeprefix("package:").strip()
                            for line in paths_result.stdout.splitlines()
                            if line.startswith("package:")]
            if not remote_paths:
                continue
            base_remote = next((item for item in remote_paths if item.endswith("/base.apk")),
                               remote_paths[0])
            local_apk = temporary_root / f"{package_name}.apk"
            pulled = adb_binary(serial, ["pull", base_remote, str(local_apk)], timeout=180)
            if pulled.returncode != 0 or not local_apk.is_file():
                continue
            package_badging = badging(local_apk)
            label = value(r"^application-label:'([^']*)'", package_badging)
            if label not in TARGET_LABELS:
                continue
            dumpsys = run_adb(serial, ["shell", "dumpsys", "package", package_name],
                              check=False).stdout
            path_rows = []
            for remote in remote_paths:
                stat = run_adb(serial, ["shell", "stat", "-c", "%s", remote], check=False)
                path_rows.append({
                    "kind": "base" if remote == base_remote else "split",
                    "path": remote,
                    "bytes": int(stat.stdout.strip()) if stat.stdout.strip().isdigit() else -1,
                })
            discoveries.append({
                "label": label,
                "package": value(r"^package: name='([^']+)'", package_badging) or package_name,
                "version_name": value(r"^package: .*versionName='([^']*)'", package_badging),
                "version_code": value(r"^package: .*versionCode='([^']*)'", package_badging),
                "launchable_activity": value(r"^launchable-activity: name='([^']+)'", package_badging),
                "base_and_splits": path_rows,
                "split_count": max(0, len(path_rows) - 1),
                "primary_cpu_abi": value(r"^\s*primaryCpuAbi=(.*)$", dumpsys),
                "secondary_cpu_abi": value(r"^\s*secondaryCpuAbi=(.*)$", dumpsys),
                "apk_native_code": re.findall(r"^native-code: (.*)$", package_badging,
                                               re.MULTILINE),
                "discovery": "pm list packages -3 -> pm path -> pulled base.apk -> aapt2 label match",
            })
    discoveries.sort(key=lambda row: TARGET_LABELS.index(row["label"]))
    write_json(raw_dir / "commercial-samples.json", discoveries)
    missing = [label for label in TARGET_LABELS
               if label not in {row["label"] for row in discoveries}]
    if missing:
        raise RuntimeError(f"COMMERCIAL_SAMPLES_MISSING:{','.join(missing)}")
    return discoveries


def run_as_file(serial: str, relative: str) -> dict[str, Any]:
    return text_probe(serial, ["shell", "run-as", HOST_PACKAGE, "cat", relative])


def capture_snapshot(serial: str, case_dir: Path, package_name: str) -> dict[str, Any]:
    captured_at = now_iso()
    screen = adb_binary(serial, ["exec-out", "screencap", "-p"], timeout=30)
    screen_path = case_dir / "screenshot.png"
    screen_path.parent.mkdir(parents=True, exist_ok=True)
    screen_path.write_bytes(screen.stdout)

    probes = {
        "logcat.txt": ["logcat", "-d", "-v", "threadtime"],
        "activity-activities.txt": ["shell", "dumpsys", "activity", "activities"],
        "activity-processes.txt": ["shell", "dumpsys", "activity", "processes"],
        "window-windows.txt": ["shell", "dumpsys", "window", "windows"],
        "surface-list.txt": ["shell", "dumpsys", "SurfaceFlinger", "--list"],
        "surface-dump.txt": ["shell", "dumpsys", "SurfaceFlinger"],
        "processes.txt": ["shell", "ps", "-A", "-o", "USER,PID,PPID,NAME,ARGS"],
        "host-package.txt": ["shell", "dumpsys", "package", HOST_PACKAGE],
        "target-package.txt": ["shell", "dumpsys", "package", package_name],
        "device-getprop.txt": ["shell", "getprop"],
        "adb-devices.txt": ["devices", "-l"],
        "host-files.txt": ["shell", "run-as", HOST_PACKAGE, "find", "files", "-maxdepth", "5", "-type", "f"],
    }
    rows: dict[str, Any] = {}
    for filename, args in probes.items():
        probe = text_probe(serial, args)
        write_text(case_dir / filename, probe["stdout"] + ("\nSTDERR:\n" + probe["stderr"]
                                                          if probe["stderr"] else ""))
        rows[filename] = {"returncode": probe["returncode"]}

    transaction_files = (
        "files/debug-command-result.json",
        "files/sandbox-catalog.json",
        "files/sandbox-catalog.json.lastgood",
        "files/package-lifecycle-transactions.json",
        "files/package-lifecycle-transactions.json.lastgood",
        "files/runtime/activity-tasks.checkpoint",
        "files/runtime/virtual-uids.registry",
    )
    transactions: dict[str, Any] = {}
    for relative in transaction_files:
        probe = run_as_file(serial, relative)
        safe_name = relative.removeprefix("files/").replace("/", "__")
        stored_path = transaction_evidence_path(case_dir, safe_name)
        write_text(stored_path, probe["stdout"])
        transactions[relative] = {"returncode": probe["returncode"],
                                  "bytes": len(probe["stdout"].encode("utf-8")),
                                  "storedPath": str(stored_path.relative_to(case_dir.parent))}
    return {
        "captured_at": captured_at,
        "screenshot": {"returncode": screen.returncode, "bytes": screen_path.stat().st_size,
                       "sha256": sha256(screen_path)},
        "probes": rows,
        "transactions": transactions,
    }


def guest_window_state(activity_dump: str) -> dict[str, Any]:
    blocks = re.split(r"(?=\s+\* Hist\s+#\d+: ActivityRecord)", activity_dump)
    guest = [block for block in blocks if HOST_ACTIVITY_PREFIX in block and "state=RESUMED" in block]
    return {
        "resumed_guest_stub_count": len(guest),
        "windows_empty": any("windows=[]" in block for block in guest),
        "reported_drawn_false": any("reportedDrawn=false" in block for block in guest),
        "has_visible_false": any("hasVisible=false" in block for block in guest),
        "drawn": any("reportedDrawn=true" in block or "firstWindowDrawn=true" in block
                     for block in guest),
    }


def classify(command_result: dict[str, Any], activity_state: dict[str, Any]) -> dict[str, Any]:
    if activity_state["windows_empty"] and activity_state["reported_drawn_false"]:
        return {"error_classification": "BLACK_SCREEN_WINDOW_NOT_DRAWN", "retryable": False}
    status = str(command_result.get("status") or "").upper()
    raw = json.dumps(command_result, ensure_ascii=False)
    if "timeout" in raw.lower() or "unavailable" in raw.lower():
        return {"error_classification": "START_OR_BIND_TIMEOUT", "retryable": "UNCLASSIFIED"}
    if "LAUNCH_GATE_FAILED" in raw or "create/resume/window not confirmed" in raw:
        return {"error_classification": "START_READINESS_GATE_FAILED", "retryable": False}
    if status != "PASS":
        if any(token in raw for token in ("IMPORT", "INSTALL", "APK", "NATIVE_", "PACKAGE")):
            return {"error_classification": "CAS_IMPORT_OR_CATALOG_FAILURE", "retryable": False}
        return {"error_classification": "FIRST_ATTEMPT_FAILURE_UNCLASSIFIED", "retryable": False}
    return {"error_classification": "NONE", "retryable": False}


def timeline(request_id: str, operation_id: str, package_name: str,
             started_at: str, completed_at: str, command_result: dict[str, Any],
             snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    operation = (command_result.get("result") or {}).get("operation") or {}
    return [
        {"phase": "UI_COMMAND_ENQUEUED", "at": started_at, "request_id": request_id,
         "operation_id": operation_id, "package": package_name, "confirmed": True,
         "correlation": "HARNESS_AND_DEBUG_COMMAND"},
        {"phase": "IMPORT_CATALOG_BIND_PREPARE_ATTACH_ACTIVITY_WINDOW_FIRST_DRAW",
         "at": "UNAVAILABLE_AS_SINGLE_CORRELATED_TIMELINE", "request_id": request_id,
         "operation_id": operation_id, "package": package_name, "confirmed": True,
         "correlation": "GAP: requestId/operationId are not propagated by current production path"},
        {"phase": "DEBUG_COMMAND_RESULT", "at": completed_at, "request_id": request_id,
         "operation_id": operation_id, "package": package_name, "confirmed": True,
         "status": command_result.get("status"), "runtime_operation": operation},
        {"phase": "DEVICE_SNAPSHOT", "at": snapshot["captured_at"],
         "request_id": request_id, "operation_id": operation_id,
         "package": package_name, "confirmed": True},
    ]


def one_attempt(serial: str, raw_dir: Path, case_name: str, package_name: str,
                command: str = "import-launch", extra: list[str] | None = None) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    operation_id = f"{TASK_ID.lower()}-{case_name}-{request_id[:12]}"
    case_dir = raw_dir / "cases" / case_name
    run_adb(serial, ["logcat", "-c"], check=False)
    started_at = now_iso()
    start = time.monotonic()
    extras = ["--es", "command", command, "--es", "package", package_name,
              "--ei", "user", "0", "--es", "requestId", request_id,
              "--ez", "trustNativeGuest", "true", *(extra or [])]
    result = debug_command(serial, extras, deadline_sec=45, force_stop_host=True)
    completed_at = now_iso()
    elapsed_ms = round((time.monotonic() - start) * 1000)
    snapshot = capture_snapshot(serial, case_dir, package_name)
    activity_dump = (case_dir / "activity-activities.txt").read_text(encoding="utf-8",
                                                                    errors="replace")
    state = guest_window_state(activity_dump)
    classification = classify(result, state)
    row = {
        "task_id": TASK_ID,
        "case": case_name,
        "command": command,
        "package": package_name,
        "request_id": request_id,
        "operation_id": operation_id,
        "attempt": 1,
        "retry_budget": 0,
        "automatic_retry_performed": False,
        "retryable": classification["retryable"],
        "error_classification": classification["error_classification"],
        "started_at": started_at,
        "completed_at": completed_at,
        "elapsed_ms": elapsed_ms,
        "command_result": result,
        "guest_window_state": state,
        "snapshot": snapshot,
        "timeline": timeline(request_id, operation_id, package_name, started_at, completed_at,
                             result, snapshot),
    }
    write_json(case_dir / "case.json", row)
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    raw_dir = args.output or ROOT / "artifacts" / "capability-audit" / "catch-up-c4-r01" / stamp
    raw_dir.mkdir(parents=True, exist_ok=True)

    environment = resolve_rd_environment(args.instance_name)
    serial = environment["adb_serial"]
    write_json(raw_dir / "environment.json", environment)
    install = install_rd_apks(serial)
    write_json(raw_dir / "install.json", install)
    samples = discover_commercial_samples(serial, raw_dir)
    by_label = {row["label"]: row["package"] for row in samples}

    cases = [
        one_attempt(serial, raw_dir, "quark-positive-control", by_label["夸克"]),
        one_attempt(serial, raw_dir, "hongguo-first-attempt", by_label["红果免费短剧"]),
        one_attempt(serial, raw_dir, "fanqie-first-attempt", by_label["番茄免费小说"]),
        one_attempt(serial, raw_dir, "hidden-retry-first-failure",
                    "com.warden.controlledsandbox.fixture", "launch-virtual-component",
                    ["--es", "component", "com.warden.controlledsandbox.fixture.DoesNotExist"]),
    ]
    all_timeline = [event for case in cases for event in case["timeline"]]
    summary = {
        "schema_version": 1,
        "task_id": TASK_ID,
        "status": "EVIDENCE_CAPTURED",
        "captured_at": now_iso(),
        "branch": subprocess.run(["git", "branch", "--show-current"], cwd=ROOT,
                                 capture_output=True, text=True, check=True).stdout.strip(),
        "commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                 capture_output=True, text=True, check=True).stdout.strip(),
        "instance_name": environment["instance_name"],
        "device": {key: environment.get(key) for key in
                   ("device_name", "api_level", "abi", "boot_id", "android_id")},
        "commercial_samples": samples,
        "sample_rule": "Quark is a positive control only and cannot close Hongguo/Fanqie compatibility",
        "cases": cases,
        "unified_timeline": all_timeline,
        "retry_policy": {"attempts_per_operation": 1, "retry_budget": 0,
                         "automatic_operation_retries": 0},
        "raw_directory": str(raw_dir.resolve()),
    }
    write_json(raw_dir / "c4-r01-rd-evidence.json", summary)
    print(json.dumps({"status": "PASS", "output": str(raw_dir),
                      "case_statuses": {case["case"]: case["command_result"].get("status")
                                        for case in cases}}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
