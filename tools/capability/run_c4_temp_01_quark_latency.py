#!/usr/bin/env python3
"""C4-TEMP-01 RD benchmark for physical-vs-CAS Quark initialization.

The device and Quark package/component are discovered at runtime.  There is no retry loop: the
first failed direct or sandbox observation is snapshotted and terminates the benchmark.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import time
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "capability"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from run_c4_r01_rd import (  # noqa: E402
    HOST_ACTIVITY_PREFIX,
    adb_binary,
    badging,
    capture_snapshot,
    guest_window_state,
    value,
)
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    HOST_PACKAGE,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)


LABEL = "夸克"
TARGET = "quark"
REQUIRED_STAGES = ("REQUEST_ACCEPTED", "GUEST_READY", "ACTIVITY_RESUMED", "FIRST_FRAME_DRAWN")


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def discover_quark(serial: str, output: Path) -> dict[str, Any]:
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"], check=False).stdout
    candidates: list[dict[str, Any]] = []
    for line in packages.splitlines():
        package_name = line.removeprefix("package:").strip()
        if not package_name:
            continue
        paths = run_adb(serial, ["shell", "pm", "path", package_name], check=False).stdout
        remote_paths = [item.removeprefix("package:").strip()
                        for item in paths.splitlines() if item.startswith("package:")]
        if not remote_paths:
            continue
        base_remote = next((item for item in remote_paths if item.endswith("/base.apk")), remote_paths[0])
        local = output / "discovery" / (package_name.replace(".", "_") + ".apk")
        local.parent.mkdir(parents=True, exist_ok=True)
        pulled = run_adb(serial, ["pull", base_remote, str(local)], check=False)
        if pulled.returncode != 0 or not local.is_file():
            continue
        package_badging = badging(local)
        label = value(r"^application-label:'([^']*)'", package_badging)
        if label != LABEL:
            continue
        resolved = run_adb(serial, ["shell", "cmd", "package", "resolve-activity", "--brief",
                                    package_name], check=False).stdout
        component = next((row.strip() for row in resolved.splitlines()
                          if "/" in row.strip() and not row.strip().startswith("priority")), "")
        if "/" not in component:
            component = ""
        dump = run_adb(serial, ["shell", "dumpsys", "package", package_name], check=False).stdout
        candidates.append({
            "target": TARGET,
            "label": label,
            "package": package_name,
            "component": component,
            "versionName": value(r"^package: .*versionName='([^']*)'", package_badging),
            "versionCode": value(r"^package: .*versionCode='([^']*)'", package_badging),
            "baseAndSplits": [{"kind": "base" if item == base_remote else "split", "path": item}
                              for item in remote_paths],
            "primaryCpuAbi": next((row.strip().split("=", 1)[1] for row in dump.splitlines()
                                   if row.strip().startswith("primaryCpuAbi=")), ""),
            "secondaryCpuAbi": next((row.strip().split("=", 1)[1] for row in dump.splitlines()
                                     if row.strip().startswith("secondaryCpuAbi=")), ""),
            "discovery": "pm list packages -> pm path -> pull -> aapt2 label -> resolve-activity",
        })
    if len(candidates) != 1:
        raise RuntimeError(f"QUARK_DYNAMIC_DISCOVERY_EXPECTED_ONE:found={len(candidates)}")
    write_json(output / "quark-discovery.json", candidates[0])
    return candidates[0]


def screenshot(serial: str, path: Path) -> dict[str, Any]:
    path.parent.mkdir(parents=True, exist_ok=True)
    result = adb_binary(serial, ["exec-out", "screencap", "-p"], timeout=30)
    path.write_bytes(result.stdout)
    quality: dict[str, Any] = {"returncode": result.returncode, "bytes": len(result.stdout)}
    try:
        from PIL import Image, ImageStat
        image = Image.open(path).convert("RGBA")
        pixels = list(image.getdata())
        visible = [pixel for pixel in pixels if pixel[3] > 0]
        non_black = sum(1 for red, green, blue, alpha in visible
                        if alpha > 0 and red + green + blue > 30)
        quality.update({"width": image.width, "height": image.height,
                        "nonTransparent": bool(visible), "nonBlack": bool(non_black),
                        "nonBlackFraction": non_black / max(1, len(visible)),
                        "uniform": ImageStat.Stat(image).extrema[0][1]
                        == ImageStat.Stat(image).extrema[0][0]})
    except Exception as error:
        quality["decodeError"] = str(error)
    return quality


def _target_window_state(window_dump: str, surface_dump: str, package: str,
                         component: str = "", sandbox: bool = False) -> dict[str, Any]:
    blocks = re.split(r"(?=\s+Window #\d+ Window\{)", window_dump)
    package_blocks = ([block for block in blocks if HOST_PACKAGE in block]
                      if sandbox else [block for block in blocks if package in block])
    component_blocks = [block for block in package_blocks if component and component in block]
    target_blocks = component_blocks or package_blocks
    target = "\n".join(target_blocks)
    surface_rows = [line.strip() for line in surface_dump.splitlines()
                    if ((HOST_ACTIVITY_PREFIX in line or package in line)
                        if sandbox else package in line)]
    return {
        "windowCount": len(target_blocks),
        "hasSurface": "mHasSurface=true" in target,
        "readyForDisplay": "isReadyForDisplay()=true" in target,
        "drawStateHasDrawn": "mDrawState=HAS_DRAWN" in target,
        "visible": "isVisible=true" in target and "isOnScreen=true" in target,
        "surfaceShown": "Surface: shown=true" in target,
        "surfaceCount": len(surface_rows),
        "surfaceNonEmpty": bool(surface_rows),
        "matchedComponent": bool(component_blocks),
    }


def _activity_state(activity_dump: str, package: str, component: str = "") -> dict[str, Any]:
    blocks = re.split(r"(?=TASK\s+\d+:)", activity_dump)
    target_blocks = [block for block in blocks if package in block]
    target = "\n".join(target_blocks)
    component_match = not component or component in target
    return {
        "packagePresent": bool(target_blocks),
        "componentPresent": component_match,
        "resumed": bool(target_blocks) and "mResumed=true" in target,
        "targetActivityLines": target[-4000:],
    }


def visible_snapshot(serial: str, case_dir: Path, package: str, component: str = "",
                     deadline_sec: float = 30.0, *, sandbox: bool = False) -> dict[str, Any]:
    deadline = time.monotonic() + deadline_sec
    last: dict[str, Any] = {}
    first = True
    while first or time.monotonic() < deadline:
        first = False
        activity_command = "activities" if sandbox else "top"
        activity = run_adb(serial, ["shell", "dumpsys", "activity", activity_command],
                           check=False).stdout
        windows = run_adb(serial, ["shell", "dumpsys", "window", "windows"], check=False).stdout
        surfaces = run_adb(serial, ["shell", "dumpsys", "SurfaceFlinger", "--list"],
                            check=False).stdout
        shot = screenshot(serial, case_dir / "screenshot.png")
        if sandbox:
            guest_state = guest_window_state(activity)
            activity_state = {
                "packagePresent": package in activity,
                "componentPresent": package in activity,
                "resumed": guest_state["resumed_guest_stub_count"] > 0
                and not guest_state["windows_empty"]
                and not guest_state["reported_drawn_false"]
                and guest_state["drawn"],
                "guestWindowState": guest_state,
            }
        else:
            activity_state = _activity_state(activity, package, component)
        window_state = _target_window_state(windows, surfaces, package, component, sandbox)
        last = {"activity": activity, "screenshot": shot,
                "activityState": activity_state, "windowState": window_state}
        if (activity_state["resumed"] and window_state["hasSurface"]
                and window_state["readyForDisplay"] and window_state["drawStateHasDrawn"]
                and window_state["visible"] and window_state["surfaceShown"]
                and window_state["surfaceNonEmpty"] and shot.get("nonBlack")
                and shot.get("nonTransparent")):
            return last
        # Bounded polling is a readiness check; it is not a fixed readiness sleep or a retry of
        # the launch operation.  The operation itself is never issued again.
        time.sleep(0.2)
    return last


def dump_case(serial: str, case_dir: Path, package: str) -> dict[str, Any]:
    snapshot = capture_snapshot(serial, case_dir / "snapshot", package)
    write_json(case_dir / "snapshot-summary.json", {
        "activity": snapshot.get("activity", ""),
        "windows": snapshot.get("windows", ""),
        "surfaces": snapshot.get("surfaces", ""),
        "screenshot": snapshot.get("screenshot", {}),
        "guestWindowState": snapshot.get("guestWindowState", {}),
        "surfaceNonEmpty": snapshot.get("surfaceNonEmpty", False),
    })
    return snapshot


def direct_once(serial: str, package: str, component: str, case_dir: Path) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    operation_id = request_id + "-direct"
    started = time.monotonic()
    stopped = run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    launched = run_adb(serial, ["shell", "am", "start", "-W", "-n", component], check=False)
    visible = visible_snapshot(serial, case_dir, package, component)
    cleaned = run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    elapsed_ms = round((time.monotonic() - started) * 1000)
    row = {"kind": "direct", "requestId": request_id, "operationId": operation_id,
           "package": package, "component": component,
           "startedAt": now_iso(), "elapsedMs": elapsed_ms,
           "forceStop": {"returncode": stopped.returncode, "stdout": stopped.stdout, "stderr": stopped.stderr},
           "amStart": {"returncode": launched.returncode, "stdout": launched.stdout, "stderr": launched.stderr},
           "postObservationForceStop": {"returncode": cleaned.returncode,
                                         "stdout": cleaned.stdout, "stderr": cleaned.stderr},
           "visible": visible, "attempt": 1, "retryBudget": 0,
           "automaticRetryPerformed": False, "retryDecision": "NO_RETRY_FIRST_OBSERVATION"}
    if (launched.returncode != 0
            or not visible.get("activityState", {}).get("resumed")
            or not visible.get("windowState", {}).get("drawStateHasDrawn")
            or not visible.get("windowState", {}).get("surfaceNonEmpty")
            or not visible.get("screenshot", {}).get("nonBlack")
            or cleaned.returncode != 0):
        row["failure"] = "DIRECT_FIRST_FRAME_NOT_CONFIRMED"
        row["errorClassification"] = "DIRECT_FIRST_FRAME_NOT_CONFIRMED"
    write_json(case_dir / "case.json", row)
    if row.get("failure"):
        row["firstFailureFullSnapshot"] = dump_case(serial, case_dir / "first-failure-full", package)
        write_json(case_dir / "case.json", row)
    return row


def sandbox_once(serial: str, package: str, case_dir: Path, *, import_first: bool = False) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    started = time.monotonic()
    setup: dict[str, Any] | None = None
    target_stop = run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    if import_first:
        setup_id = uuid.uuid4().hex
        setup = debug_command(serial, ["--es", "command", "import-only", "--es", "package", package,
                                       "--ei", "user", "0", "--es", "requestId", setup_id,
                                       "--ez", "trustNativeGuest", "true"],
                              deadline_sec=180, force_stop_host=True)
        write_json(case_dir / "import-only.json", {"requestId": setup_id, "result": setup})
        if setup.get("status") != "PASS":
            row = {"kind": "sandbox", "requestId": request_id, "package": package,
                   "attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False,
                   "failure": "IMPORT_ONLY_FAILED", "setup": setup,
                   "targetStopBeforeSandbox": {"returncode": target_stop.returncode,
                                                "stdout": target_stop.stdout,
                                                "stderr": target_stop.stderr},
                   "elapsedMs": round((time.monotonic() - started) * 1000)}
            write_json(case_dir / "case.json", row)
            row["firstFailureFullSnapshot"] = dump_case(serial, case_dir / "first-failure-full", package)
            write_json(case_dir / "case.json", row)
            return row
    stop_id = uuid.uuid4().hex
    stopped = debug_command(serial, ["--es", "command", "stop", "--es", "package", package,
                                     "--ei", "user", "0", "--es", "requestId", stop_id,
                                     "--ez", "trustNativeGuest", "true"],
                            deadline_sec=60, force_stop_host=True)
    launched = debug_command(serial, ["--es", "command", "launch", "--es", "package", package,
                                      "--ei", "user", "0", "--es", "requestId", request_id,
                                      "--ez", "trustNativeGuest", "true"],
                             deadline_sec=120, force_stop_host=True)
    elapsed_ms = round((time.monotonic() - started) * 1000)
    result = (launched.get("result") or {})
    operation = result.get("operation") or {}
    visible = visible_snapshot(serial, case_dir, package, deadline_sec=0.0,
                               sandbox=True) if launched.get("status") != "PASS" \
        else visible_snapshot(serial, case_dir, package, deadline_sec=30.0, sandbox=True)
    timeline = str(operation.get("launchTimeline", ""))
    stages = [item.split("@", 1)[0] for item in timeline.strip("[]").split(", ") if item]
    row = {"kind": "sandbox", "requestId": request_id, "operationId": request_id + "-launch",
           "package": package, "startedAt": now_iso(), "elapsedMs": elapsed_ms,
           "setup": setup, "targetStopBeforeSandbox": {
               "returncode": target_stop.returncode, "stdout": target_stop.stdout,
               "stderr": target_stop.stderr}, "stop": stopped, "launch": launched,
           "operation": operation,
           "readinessElapsedMs": operation.get("launchReadinessElapsedMs"),
           "requiredStagesPresent": all(stage in stages for stage in REQUIRED_STAGES),
           "visible": visible, "attempt": 1, "retryBudget": 0, "automaticRetryPerformed": False}
    if (launched.get("status") != "PASS" or operation.get("status") != "LAUNCH_PASS"
            or operation.get("firstFrameDrawn") is not True
            or not row["requiredStagesPresent"]
            or not visible.get("activityState", {}).get("resumed")
            or not visible.get("windowState", {}).get("drawStateHasDrawn")
            or not visible.get("windowState", {}).get("surfaceNonEmpty")
            or not visible.get("screenshot", {}).get("nonBlack")
            or target_stop.returncode != 0):
        row["failure"] = "SANDBOX_FIRST_FRAME_NOT_CONFIRMED"
        row["errorClassification"] = ((launched.get("result") or {}).get("errorType")
                                       or "SANDBOX_FIRST_FRAME_NOT_CONFIRMED")
    write_json(case_dir / "case.json", row)
    if row.get("failure"):
        row["firstFailureFullSnapshot"] = dump_case(serial, case_dir / "first-failure-full", package)
        write_json(case_dir / "case.json", row)
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "verification/catch-up/C4-TEMP-01/quark-latency")
    args = parser.parse_args()
    if args.samples < 3:
        raise SystemExit("--samples must be at least 3")
    run_dir = args.output / dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)
    environment = resolve_rd_environment(args.instance_name)
    serial = environment["adb_serial"]
    write_json(run_dir / "environment.json", environment)
    install = install_rd_apks(serial)
    write_json(run_dir / "install.json", install)
    target = discover_quark(serial, run_dir)
    package, component = target["package"], target["component"]
    if not component:
        raise RuntimeError("QUARK_LAUNCH_COMPONENT_NOT_RESOLVED")

    # Clone is catalog-only in CAS; measure it separately so import bytes are not attributed to
    # launch.  The command is a single operation, never an implicit retry.
    clone_id = uuid.uuid4().hex
    clone = debug_command(serial, ["--es", "command", "lifecycle-clone", "--es", "package", package,
                                   "--ei", "user", "0", "--es", "requestId", clone_id,
                                   "--ez", "trustNativeGuest", "true"],
                          deadline_sec=90, force_stop_host=True)
    write_json(run_dir / "clone.json", {"requestId": clone_id, "result": clone})

    direct_rows: list[dict[str, Any]] = []
    for index in range(1, args.samples + 1):
        row = direct_once(serial, package, component, run_dir / "direct" / f"sample-{index:02d}")
        direct_rows.append(row)
        if row.get("failure"):
            write_json(run_dir / "summary.json", {"status": "BLOCKED", "firstFailure": row,
                                                    "environment": environment, "target": target})
            return 1

    # Import is measured once, then the launch samples use retained immutable package state.  The
    # first sandbox sample includes the cold process/prepare path after that import.
    sandbox_rows: list[dict[str, Any]] = []
    for index in range(1, args.samples + 1):
        row = sandbox_once(serial, package, run_dir / "sandbox" / f"sample-{index:02d}",
                           import_first=index == 1)
        sandbox_rows.append(row)
        if row.get("failure"):
            write_json(run_dir / "summary.json", {"status": "BLOCKED", "firstFailure": row,
                                                    "environment": environment, "target": target,
                                                    "direct": direct_rows})
            return 1

    direct_ms = [int(row["elapsedMs"]) for row in direct_rows]
    sandbox_ms = [int(row["elapsedMs"]) for row in sandbox_rows]
    ratios = [sandbox / max(1, direct) for sandbox, direct in zip(sandbox_ms, direct_ms)]
    summary = {"status": "PASS" if max(ratios) <= 10 else "FAIL",
               "hardLimitRatio": 10.0, "targetRatio": 3.0,
               "directElapsedMs": direct_ms, "sandboxElapsedMs": sandbox_ms,
               "ratios": ratios, "maxRatio": max(ratios), "medianRatio": sorted(ratios)[len(ratios) // 2],
               "clone": clone, "environment": environment, "target": target,
               "direct": direct_rows, "sandbox": sandbox_rows,
               "noAutomaticRetry": all(not row.get("automaticRetryPerformed") for row in sandbox_rows)}
    write_json(run_dir / "summary.json", summary)
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
