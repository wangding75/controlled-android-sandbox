#!/usr/bin/env python3
"""C4-R03 launch readiness/window contract matrix on dynamically resolved RD测试.

This runner is intentionally fail-fast and diagnostic.  Each operation has one attempt and a
zero retry budget.  It accepts a launch only when the production result carries the complete
REQUEST_ACCEPTED -> GUEST_READY -> ACTIVITY_RESUMED -> FIRST_FRAME_DRAWN trace and the device
proves a non-empty Guest window, SurfaceFlinger entry and non-black screenshot.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
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
    HOST_PACKAGE,
    aapt2_path,
    adb_binary,
    badging,
    capture_snapshot,
    guest_window_state,
    value,
)
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    GUEST_PACKAGE,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C4-R03"
VISUAL_CONTENT_DEADLINE_SEC = 30
TARGET_ALIASES = {
    "quark": {"夸克"},
    "hongguo": {"红果免费短剧"},
    "fanqie": {"番茄免费小说"},
}
REQUIRED_STAGES = ("REQUEST_ACCEPTED", "GUEST_READY", "ACTIVITY_RESUMED", "FIRST_FRAME_DRAWN")
FATAL_MARKERS = (
    "FATAL EXCEPTION", "Fatal signal", "ANR in", "BadTokenException",
    "View not attached to window manager", "WINDOW_PUBLISH_AFTER_RESUME failed",
)
LOGCAT_THREADTIME = re.compile(
    r"^(?P<month>\d{2})-(?P<day>\d{2}) (?P<hour>\d{2}):"
    r"(?P<minute>\d{2}):(?P<second>\d{2})\.(?P<millis>\d{3})\s"
)


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload or "", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def discover_commercial_targets(serial: str, output: Path) -> dict[str, dict[str, Any]]:
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"], check=False).stdout
    rows: list[dict[str, Any]] = []
    with __import__("tempfile").TemporaryDirectory(prefix="c4-r03-discovery-") as temporary:
        temporary_root = Path(temporary)
        for line in packages.splitlines():
            package_name = line.removeprefix("package:").strip()
            if not package_name:
                continue
            paths = run_adb(serial, ["shell", "pm", "path", package_name], check=False).stdout
            remote_paths = [item.removeprefix("package:").strip()
                            for item in paths.splitlines() if item.startswith("package:")]
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
            target = next((name for name, labels in TARGET_ALIASES.items() if label in labels), None)
            if target is None:
                continue
            package_dump = run_adb(serial, ["shell", "dumpsys", "package", package_name],
                                   check=False).stdout
            artifact_rows = []
            for remote in remote_paths:
                stat = run_adb(serial, ["shell", "stat", "-c", "%s", remote], check=False)
                artifact_rows.append({
                    "kind": "base" if remote == base_remote else "split",
                    "path": remote,
                    "bytes": int(stat.stdout.strip()) if stat.stdout.strip().isdigit() else -1,
                })
            rows.append({
                "target": target,
                "label": label,
                "package": value(r"^package: name='([^']+)'", package_badging) or package_name,
                "versionName": value(r"^package: .*versionName='([^']*)'", package_badging),
                "versionCode": value(r"^package: .*versionCode='([^']*)'", package_badging),
                "launchableActivity": value(r"^launchable-activity: name='([^']+)'", package_badging),
                "baseAndSplits": artifact_rows,
                "baseCount": 1,
                "splitCount": max(0, len(artifact_rows) - 1),
                "primaryCpuAbi": value(r"^\s*primaryCpuAbi=(.*)$", package_dump),
                "secondaryCpuAbi": value(r"^\s*secondaryCpuAbi=(.*)$", package_dump),
                "apkNativeCode": re.findall(r"^native-code: (.*)$", package_badging, re.MULTILINE),
                "discovery": "pm list packages -> pm path -> aapt2 label -> dumpsys package",
            })
    rows.sort(key=lambda row: row["target"])
    write_json(output / "commercial-sample-discovery.json", rows)
    indexed = {row["target"]: row for row in rows}
    missing = sorted(set(TARGET_ALIASES) - set(indexed))
    if missing:
        raise RuntimeError("COMMERCIAL_SAMPLES_MISSING:" + ",".join(missing))
    return indexed


def discover_dingtalk(serial: str, output: Path) -> dict[str, Any]:
    packages = run_adb(serial, ["shell", "pm", "list", "packages", "-3"], check=False).stdout
    matches: list[dict[str, Any]] = []
    for line in packages.splitlines():
        package_name = line.removeprefix("package:").strip()
        if not package_name:
            continue
        dump = run_adb(serial, ["shell", "dumpsys", "package", package_name], check=False).stdout
        version_name = next((row.strip().split("=", 1)[1].split()[0]
                             for row in dump.splitlines()
                             if row.strip().startswith("versionName=")), "")
        version_code = next((row.strip().split("=", 1)[1].split()[0]
                             for row in dump.splitlines()
                             if row.strip().startswith("versionCode=")), "")
        if version_name != "7.8.10" or version_code != "1178":
            continue
        paths = [row.removeprefix("package:").strip() for row in
                 run_adb(serial, ["shell", "pm", "path", package_name]).stdout.splitlines()
                 if row.startswith("package:")]
        matches.append({
            "target": "dingtalk",
            "label": "DingTalk (identified by required version; package discovered at runtime)",
            "package": package_name,
            "versionName": version_name,
            "versionCode": version_code,
            "baseAndSplits": [{"kind": "base" if path.endswith("/base.apk") else "split",
                               "path": path} for path in paths],
            "baseCount": sum(1 for path in paths if path.endswith("/base.apk")),
            "splitCount": sum(1 for path in paths if not path.endswith("/base.apk")),
            "primaryCpuAbi": next((row.strip().split("=", 1)[1]
                                    for row in dump.splitlines()
                                    if row.strip().startswith("primaryCpuAbi=")), ""),
            "secondaryCpuAbi": next((row.strip().split("=", 1)[1]
                                      for row in dump.splitlines()
                                      if row.strip().startswith("secondaryCpuAbi=")), ""),
            "discovery": "third-party packages -> dumpsys exact required version -> pm path",
        })
    if len(matches) != 1:
        raise RuntimeError(f"DINGTALK_DYNAMIC_DISCOVERY_EXPECTED_ONE:found={len(matches)}")
    write_json(output / "dingtalk-discovery.json", matches[0])
    return matches[0]


def fixture_target() -> dict[str, Any]:
    apk = ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"
    text = badging(apk)
    return {
        "target": "fixture",
        "label": value(r"^application-label:'([^']*)'", text),
        "package": GUEST_PACKAGE,
        "versionName": value(r"^package: .*versionName='([^']*)'", text),
        "versionCode": value(r"^package: .*versionCode='([^']*)'", text),
        "launchableActivity": value(r"^launchable-activity: name='([^']+)'", text),
        "baseCount": 1,
        "splitCount": 0,
        "apkNativeCode": re.findall(r"^native-code: (.*)$", text, re.MULTILINE),
        "discovery": "local fixture APK aapt2 badging",
    }


def screenshot_quality(path: Path) -> dict[str, Any]:
    try:
        from PIL import Image, ImageStat
        image = Image.open(path).convert("RGBA")
        pixels = list(image.getdata())
        visible = [pixel for pixel in pixels if pixel[3] > 0]
        non_black = sum(1 for red, green, blue, alpha in visible
                        if alpha > 0 and red + green + blue > 30)
        non_transparent = bool(visible)
        stat = ImageStat.Stat(image)
        return {
            "width": image.width,
            "height": image.height,
            "sha256": sha256(path),
            "nonTransparent": non_transparent,
            "nonBlackFraction": round(non_black / max(1, len(pixels)), 6),
            "nonBlack": non_black > 0,
            "uniform": max(stat.extrema[index][1] - stat.extrema[index][0]
                            for index in range(4)) == 0,
        }
    except Exception as error:
        return {"sha256": sha256(path), "error": f"{type(error).__name__}:{error}",
                "nonTransparent": False, "nonBlack": False, "uniform": True}


def classify_logcat_markers(logcat: str, operation_started_at_ms: Any) -> tuple[list[str], list[str]]:
    """Separate current-operation failures from historical device log residue.

    The RD emulator intentionally retains logcat across app generations.  A marker is current
    only when its threadtime timestamp is at or after the production operation's wall-clock
    start.  Unknown/unparseable timestamps remain fail-closed and are classified as current.
    """
    try:
        started = dt.datetime.fromtimestamp(float(operation_started_at_ms) / 1000.0)
    except (TypeError, ValueError, OSError, OverflowError):
        started = None
    current: set[str] = set()
    historical: set[str] = set()
    for line in (logcat or "").splitlines():
        markers = [marker for marker in FATAL_MARKERS if marker in line]
        if not markers:
            continue
        match = LOGCAT_THREADTIME.match(line)
        if started is None or match is None:
            current.update(markers)
            continue
        try:
            observed = dt.datetime(
                started.year,
                int(match.group("month")),
                int(match.group("day")),
                int(match.group("hour")),
                int(match.group("minute")),
                int(match.group("second")),
                int(match.group("millis")) * 1000,
            )
        except ValueError:
            current.update(markers)
            continue
        if observed.timestamp() * 1000 >= started.timestamp() * 1000:
            current.update(markers)
        else:
            historical.update(markers)
    return sorted(current), sorted(historical)


def light_snapshot(serial: str, case_dir: Path, package_name: str) -> dict[str, Any]:
    screenshot = adb_binary(serial, ["exec-out", "screencap", "-p"], timeout=30)
    screenshot_path = case_dir / "screenshot.png"
    screenshot_path.write_bytes(screenshot.stdout)
    activity = run_adb(serial, ["shell", "dumpsys", "activity", "activities"], check=False).stdout
    windows = run_adb(serial, ["shell", "dumpsys", "window", "windows"], check=False).stdout
    surfaces = run_adb(serial, ["shell", "dumpsys", "SurfaceFlinger", "--list"], check=False).stdout
    logcat = run_adb(
        serial, ["shell", "logcat", "-d", "-t", "20000", "-v", "threadtime"],
        check=False,
    ).stdout
    write_text(case_dir / "activity-activities.txt", activity)
    write_text(case_dir / "window-windows.txt", windows)
    write_text(case_dir / "surface-list.txt", surfaces)
    write_text(case_dir / "logcat.txt", logcat)
    return {
        "activity": activity,
        "windows": windows,
        "surfaces": surfaces,
        "logcat": logcat,
        "screenshot": screenshot_quality(screenshot_path),
        "guestWindowState": guest_window_state(activity),
        "surfaceNonEmpty": bool(surfaces.strip()),
        "package": package_name,
    }


def run_one(serial: str, root: Path, target: dict[str, Any], user: int,
            mode: str, iteration: int, *, attempt_number: int = 1,
            resume_metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    attempt_tag = f"-a{attempt_number}" if attempt_number != 1 else ""
    operation_id = f"{TASK_ID.lower()}-{target['target']}-u{user}-{mode}-{iteration}{attempt_tag}-{request_id[:10]}"
    case_dir = root / "attempts" / target["target"] / f"user-{user}" / f"{mode}-{iteration:03d}"
    case_dir.mkdir(parents=True, exist_ok=True)
    started_at = now_iso()
    start = time.monotonic()
    setup = None
    if mode == "cold":
        stop_request_id = uuid.uuid4().hex
        stop = debug_command(
            serial,
            ["--es", "command", "stop", "--es", "package", target["package"],
             "--ei", "user", str(user), "--es", "requestId", stop_request_id,
             "--ez", "trustNativeGuest", "true"],
            deadline_sec=30,
            force_stop_host=True,
        )
        setup = {"kind": "cold-stop", "requestId": stop_request_id, "result": stop,
                  "attempt": attempt_number, "retryBudget": 0,
                  "automaticRetryPerformed": False}
        write_json(case_dir / "cold-stop.json", setup)
        if stop.get("status") != "PASS":
            row = {
                "task": TASK_ID, "target": target["target"], "package": target["package"],
                "user": user, "mode": mode, "iteration": iteration,
                "requestId": request_id, "operationId": request_id + "-launch",
                "attempt": attempt_number, "retryBudget": 0,
                "automaticRetryPerformed": False,
                "retryable": False, "errorClassification": "COLD_STOP_SETUP_FAILED",
                "firstAttemptFailure": attempt_number == 1,
                "failureDetected": True, "setup": setup,
                "artifacts": str(case_dir.resolve()),
            }
            if resume_metadata:
                row["resume"] = resume_metadata
            write_json(case_dir / "case.json", row)
            full = capture_snapshot(serial, case_dir / "first-failure-full", target["package"])
            row["firstFailureFullSnapshot"] = full
            write_json(case_dir / "case.json", row)
            return row
    command = "launch"
    result = debug_command(
        serial,
        ["--es", "command", command, "--es", "package", target["package"],
         "--ei", "user", str(user), "--es", "requestId", request_id,
         "--ez", "trustNativeGuest", "true"],
        # This is only the collector's wait budget.  The production launch gate remains
        # 30s cold / 10s hot; allowing the command to return after that gate preserves the
        # actual first failure instead of converting it into a collector timeout.
        deadline_sec=90,
        force_stop_host=(mode == "cold"),
    )
    completed_at = now_iso()
    elapsed_ms = round((time.monotonic() - start) * 1000)
    result_json = result.get("result") or {}
    operation = result_json.get("operation") or {}
    readiness_ms = operation.get("launchReadinessElapsedMs")
    raw_precheck = json.dumps(result, ensure_ascii=False)
    failure_reason = ""
    if result.get("status") != "PASS" or operation.get("status") != "LAUNCH_PASS":
        failure_reason = "LAUNCH_RESULT_NOT_PASS"
    elif operation.get("requestId") != request_id or operation.get("operationId") != request_id + "-launch":
        failure_reason = "CORRELATION_MISMATCH"
    elif operation.get("firstFrameDrawn") is not True:
        failure_reason = "FIRST_FRAME_NOT_DRAWN"
    else:
        timeline_names = [stage.split("@", 1)[0]
                          for stage in str(operation.get("launchTimeline", "")).strip("[]").split(", ")
                          if stage]
        positions = []
        cursor = -1
        for required in REQUIRED_STAGES:
            try:
                cursor = timeline_names.index(required, cursor + 1)
            except ValueError:
                positions = []
                break
            positions.append(cursor)
        if positions != sorted(positions) or len(positions) != len(REQUIRED_STAGES):
            failure_reason = "READINESS_STATE_MACHINE_INCOMPLETE"
    if not failure_reason and (not isinstance(readiness_ms, int)
                               or readiness_ms > (30_000 if mode == "cold" else 10_000)):
        failure_reason = "READINESS_SLO_EXCEEDED"
    device = light_snapshot(serial, case_dir, target["package"])
    visual_attempts = [{"elapsedMs": round((time.monotonic() - start) * 1000),
                        "quality": device["screenshot"]}]
    first_screenshot = case_dir / "screenshot.png"
    if first_screenshot.is_file():
        shutil.copyfile(first_screenshot, case_dir / "screenshot-first-observation.png")
    visual_deadline = time.monotonic() + VISUAL_CONTENT_DEADLINE_SEC
    while device["screenshot"].get("uniform") and time.monotonic() < visual_deadline:
        time.sleep(0.5)
        device = light_snapshot(serial, case_dir, target["package"])
        visual_attempts.append({"elapsedMs": round((time.monotonic() - start) * 1000),
                                "quality": device["screenshot"]})
    guest = device["guestWindowState"]
    quality = device["screenshot"]
    if not guest["resumed_guest_stub_count"] or guest["windows_empty"] or not guest["drawn"]:
        failure_reason = failure_reason or "GUEST_WINDOW_NOT_READY"
    if not device["surfaceNonEmpty"]:
        failure_reason = failure_reason or "SURFACE_EMPTY"
    if (not quality.get("nonTransparent") or not quality.get("nonBlack")
            or quality.get("uniform") is True):
        failure_reason = failure_reason or "SCREENSHOT_BLACK_OR_TRANSPARENT"
    fatal, historical_fatal = classify_logcat_markers(
        device["logcat"], result_json.get("startedAt"))
    if fatal:
        failure_reason = failure_reason or "FATAL_OR_WINDOW_ERROR:" + ",".join(fatal)
    row = {
        "task": TASK_ID,
        "target": target["target"],
        "package": target["package"],
        "user": user,
        "mode": mode,
        "iteration": iteration,
        "requestId": request_id,
        "operationId": request_id + "-launch",
        "runnerOperationId": operation_id,
        "attempt": attempt_number,
        "retryBudget": 0,
        "automaticRetryPerformed": False,
        "retryable": False,
        "startedAt": started_at,
        "completedAt": completed_at,
        "elapsedMs": elapsed_ms,
        "readinessElapsedMs": readiness_ms,
        "commandResult": result,
        "setup": setup,
        "operation": operation,
        "device": {
            "guestWindowState": guest,
            "surfaceNonEmpty": device["surfaceNonEmpty"],
            "screenshot": quality,
            "fatalMarkers": fatal,
            "historicalFatalMarkers": historical_fatal,
            "visualContentAttempts": visual_attempts,
        },
        "errorClassification": failure_reason or "NONE",
        "firstAttemptFailure": bool(failure_reason) and attempt_number == 1,
        "failureDetected": bool(failure_reason),
        "artifacts": str(case_dir.resolve()),
    }
    if resume_metadata:
        row["resume"] = resume_metadata
    write_json(case_dir / "case.json", row)
    if failure_reason:
        # Preserve this lane's first failure's complete snapshot immediately; no in-lane retry
        # is allowed to overwrite the evidence or turn a failure into a later PASS.
        full = capture_snapshot(serial, case_dir / "first-failure-full", target["package"])
        row["firstFailureFullSnapshot"] = full
        write_json(case_dir / "case.json", row)
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--loops", type=int, default=50)
    parser.add_argument("--users", default="0,1")
    parser.add_argument("--targets", default="fixture,dingtalk,quark,hongguo,fanqie")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "artifacts/capability-audit/catch-up-c4-r03/matrix")
    parser.add_argument("--resume-target", default="",
                        help="resume at a specific target after a separately recorded failure")
    parser.add_argument("--resume-user", type=int, default=None)
    parser.add_argument("--resume-iteration", type=int, default=None)
    parser.add_argument("--resume-mode", choices=("cold", "hot"), default="")
    parser.add_argument("--resume-attempt", type=int, default=1,
                        help="manual post-restart attempt number; automatic retries remain disabled")
    parser.add_argument("--resume-of", default="",
                        help="prior lane/case path retained as the first-failure evidence")
    args = parser.parse_args()
    if not 1 <= args.loops <= 50:
        raise SystemExit("--loops must be between 1 and 50")
    users = [int(value.strip()) for value in args.users.split(",") if value.strip()]
    resume_requested = bool(args.resume_target or args.resume_user is not None
                            or args.resume_iteration is not None or args.resume_mode)
    if resume_requested:
        if not args.resume_target or args.resume_user is None or args.resume_iteration is None \
                or not args.resume_mode:
            raise SystemExit("resume requires --resume-target, --resume-user, --resume-iteration and --resume-mode")
        if args.resume_iteration < 1:
            raise SystemExit("--resume-iteration must be >= 1")
        if args.resume_attempt < 2:
            raise SystemExit("manual resume attempt must be >= 2")
    environment = resolve_rd_environment(args.instance_name)
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "environment.json", environment)
    # A resumed hot coordinate must retain the runtime state left by the preceding cold
    # coordinate.  Reinstalling the host APK can tear down that state before the hot launch
    # is even requested.  The initial lane already installed the exact build, and the R05
    # continuation is only allowed from that durable lane, so record the deliberate skip.
    preserve_hot_resume_state = resume_requested and args.resume_mode == "hot"
    if preserve_hot_resume_state:
        install = {
            "status": "SKIPPED",
            "reason": "HOT_RESUME_PRESERVES_PREVIOUS_LANE_RUNTIME_STATE",
            "previousLane": str(Path(args.resume_of).resolve()),
        }
    else:
        install = install_rd_apks(environment["adb_serial"])
    write_json(args.output / "install.json", install)
    targets = {"fixture": fixture_target()}
    targets.update(discover_commercial_targets(environment["adb_serial"], args.output))
    targets["dingtalk"] = discover_dingtalk(environment["adb_serial"], args.output)
    write_json(args.output / "targets.json", targets)
    selected = [targets[name] for name in args.targets.split(",") if name]
    selected_names = [target["target"] for target in selected]
    resume_metadata: dict[str, Any] | None = None
    resume_position: tuple[int, int, int, int] | None = None
    if resume_requested:
        if args.resume_target not in selected_names:
            raise SystemExit(f"resume target is not selected: {args.resume_target}")
        if args.resume_user not in users:
            raise SystemExit(f"resume user is not selected: {args.resume_user}")
        if not args.resume_of:
            raise SystemExit("manual resume requires --resume-of")
        resume_position = (
            selected_names.index(args.resume_target),
            users.index(args.resume_user),
            args.resume_iteration,
            0 if args.resume_mode == "cold" else 1,
        )
        resume_metadata = {
            "kind": "MANUAL_RESUME_AFTER_RESTART",
            "resumeTarget": args.resume_target,
            "resumeUser": args.resume_user,
            "resumeIteration": args.resume_iteration,
            "resumeMode": args.resume_mode,
            "attempt": args.resume_attempt,
            "retryBudget": 0,
            "automaticRetryPerformed": False,
            "previousLane": args.resume_of,
            "note": "The prior first-failure evidence remains authoritative; this lane is a separately recorded post-restart observation.",
        }
        write_json(args.output / "resume.json", resume_metadata)
    rows: list[dict[str, Any]] = []
    blocked_at = None
    expected_rows = 0
    for target_index, target in enumerate(selected):
        for user_index, user in enumerate(users):
            pair_position = (target_index, user_index)
            if resume_position is not None and pair_position < resume_position[:2]:
                continue
            if resume_position is not None and pair_position == resume_position[:2]:
                pair_expected = (args.loops * 2) - (resume_position[2] - 1) * 2 - resume_position[3]
            else:
                pair_expected = args.loops * 2
            expected_rows += pair_expected
            preserve_pair_runtime_state = (
                preserve_hot_resume_state
                and resume_position is not None
                and pair_position == resume_position[:2]
            )
            if preserve_pair_runtime_state:
                # import-only calls debug_command with force_stop_host=True.  That is valid
                # for a fresh lane, but invalid when the first missing coordinate is hot: it
                # destroys the recovered Guest process that makes the coordinate hot.
                setup_request_id = None
                setup = {
                    "status": "SKIPPED",
                    "returncode": 0,
                    "reason": "HOT_RESUME_PRESERVES_PREVIOUS_LANE_RUNTIME_STATE",
                }
            else:
                setup_request_id = uuid.uuid4().hex
                setup = debug_command(
                    environment["adb_serial"],
                    ["--es", "command", "import-only", "--es", "package", target["package"],
                     "--ei", "user", str(user), "--es", "requestId", setup_request_id,
                     "--ez", "trustNativeGuest", "true"],
                    deadline_sec=60,
                    force_stop_host=True,
                )
            setup_row = {"target": target["target"], "package": target["package"],
                         "user": user, "requestId": setup_request_id,
                         "attempt": args.resume_attempt if resume_metadata else 1,
                         "retryBudget": 0, "automaticRetryPerformed": False,
                         "command": "import-only", "result": setup}
            if preserve_pair_runtime_state:
                setup_row["status"] = "SKIPPED"
                setup_row["skipReason"] = "HOT_RESUME_PRESERVES_PREVIOUS_LANE_RUNTIME_STATE"
            if resume_metadata:
                setup_row["resume"] = resume_metadata
            write_json(args.output / "setup" / target["target"] / f"user-{user}.json", setup_row)
            if setup.get("status") not in ("PASS", "SKIPPED"):
                blocked_at = {"target": target["target"], "user": user,
                              "mode": "import-only", "iteration": 0,
                              "classification": "IMPORT_SETUP_FAILED"}
                break
            logcat_reset = run_adb(
                environment["adb_serial"], ["shell", "logcat", "-c"], check=False)
            write_json(args.output / "setup" / target["target"] /
                       f"user-{user}-logcat-boundary.json", {
                           "returncode": logcat_reset.returncode,
                           "stdout": logcat_reset.stdout,
                           "stderr": logcat_reset.stderr,
                           "scope": "BEFORE_FIRST_REQUEST",
                       })
            if logcat_reset.returncode != 0:
                blocked_at = {"target": target["target"], "user": user,
                              "mode": "logcat-boundary", "iteration": 0,
                              "classification": "LOGCAT_SCOPE_RESET_FAILED"}
                break
            for iteration in range(1, args.loops + 1):
                for mode in ("cold", "hot"):
                    position = (target_index, user_index, iteration, 0 if mode == "cold" else 1)
                    if resume_position is not None and position < resume_position:
                        continue
                    row = run_one(environment["adb_serial"], args.output, target, user,
                                  mode, iteration,
                                  attempt_number=args.resume_attempt if resume_metadata else 1,
                                  resume_metadata=resume_metadata)
                    rows.append(row)
                    logcat_reset = run_adb(
                        environment["adb_serial"], ["shell", "logcat", "-c"], check=False)
                    row["postEvidenceLogcatReset"] = {
                        "returncode": logcat_reset.returncode,
                        "stdout": logcat_reset.stdout,
                        "stderr": logcat_reset.stderr,
                        "scope": "AFTER_CASE_EVIDENCE",
                    }
                    if logcat_reset.returncode != 0 and not row["failureDetected"]:
                        row["failureDetected"] = True
                        row["firstAttemptFailure"] = row["attempt"] == 1
                        row["errorClassification"] = "LOGCAT_SCOPE_RESET_FAILED"
                    write_json(Path(row["artifacts"]) / "case.json", row)
                    if row["failureDetected"]:
                        blocked_at = {"target": target["target"], "user": user,
                                      "mode": mode, "iteration": iteration,
                                      "classification": row["errorClassification"]}
                        break
                if blocked_at:
                    break
            if blocked_at:
                break
        if blocked_at:
            break
    summary = {
        "schemaVersion": 1,
        "task": TASK_ID,
        "status": "PASS" if not blocked_at and len(rows) == expected_rows else "FAIL",
        "instanceName": environment["instance_name"],
        "resolvedSerial": environment["adb_serial"],
        "loops": args.loops,
        "users": users,
        "targetNames": [target["target"] for target in selected],
        "attemptPolicy": {
            "attempt": args.resume_attempt if resume_metadata else 1,
            "retryBudget": 0,
            "automaticRetries": 0,
            "manualResumeAfterRestart": bool(resume_metadata),
        },
        "expectedRows": expected_rows,
        "resume": resume_metadata,
        "blockedAt": blocked_at,
        "rows": rows,
        "rawDirectory": str(args.output.resolve()),
    }
    write_json(args.output / "c4-r03-summary.json", summary)
    print(json.dumps({"status": summary["status"], "rows": len(rows),
                      "blockedAt": blocked_at, "output": str(args.output)}, ensure_ascii=False,
                     indent=2))
    return 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
