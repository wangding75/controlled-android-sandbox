"""Run P1-09's bounded Activity first-frame, task, result and rotation acceptance matrix.

Each invocation is an attempt=1 observation.  The runner deliberately has no recovery relaunch:
an observed launch/terminal/fixture failure is retained as the first failure in run.json.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "out" / "verification"
HOST = "com.warden.controlledsandbox.debug"
HOST_COMPONENT = HOST + "/com.warden.controlledsandbox.DebugCommandActivity"
FIXTURE = "com.warden.controlledsandbox.fixture"
VISUAL = FIXTURE + ".P109VisualProbeActivity"
ROTATION = FIXTURE + ".P109RotationProbeActivity"
SINGLE_TASK = FIXTURE + ".TaskSemanticsProbeActivity"
RESULT_PARENT = FIXTURE + ".FrameworkActivityResultParentActivity"
FORBIDDEN = ("LAUNCH_OBSERVATION_NOT_FOUND", "LAUNCH_GATE_FAILED",
             "FRAMEWORK_PROBE_TASK_REUSE_FAIL", "FRAMEWORK_PROBE_ACTIVITY_RESULT_FAIL")


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def adb_path() -> str:
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not root:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    path = Path(root) / "platform-tools" / "adb.exe"
    if not path.is_file():
        raise FileNotFoundError(path)
    return str(path)


def command(arguments: list[str], timeout: float = 60.0) -> dict[str, Any]:
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=timeout)
    return {"command": arguments, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr}


def require(result: dict[str, Any], label: str) -> None:
    if result["returncode"] != 0:
        raise RuntimeError(label + ":" + (result["stderr"] or result["stdout"]).strip())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def metadata(adb: str, serial: str) -> dict[str, str]:
    values = {"serial": serial}
    for name, args in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        result = command([adb, "-s", serial, *args])
        require(result, "metadata-" + name)
        values[name] = result["stdout"].strip()
    return values


def wait_debug(adb: str, serial: str, command_name: str, request_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 90.0
    last = ""
    while time.monotonic() < deadline:
        result = command([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                          "files/debug-command-result.json"])
        if result["returncode"] == 0:
            last = result["stdout"].strip()
            try:
                value = json.loads(last)
            except json.JSONDecodeError:
                value = None
            if (isinstance(value, dict) and value.get("command") == command_name
                    and value.get("requestId") == request_id):
                return value
        time.sleep(0.2)
    raise RuntimeError("DEBUG_RESULT_TIMEOUT:" + request_id + ":" + last[:300])


def clear_logcat(adb: str, serial: str) -> None:
    require(command([adb, "-s", serial, "shell", "logcat", "-c"]), "clear-logcat")


def capture_log(adb: str, serial: str, path: Path) -> str:
    result = command([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                      "CS_FIXTURE:I", "CS_EVENT:I", "CS_BROKER_LAUNCH:I",
                      "CS_RUNTIME:I", "AndroidRuntime:E"])
    require(result, "capture-logcat")
    path.write_text(result["stdout"], encoding="utf-8")
    return result["stdout"]


def wait_log(adb: str, serial: str, path: Path, marker: str) -> str:
    deadline = time.monotonic() + 30.0
    latest = ""
    while time.monotonic() < deadline:
        latest = capture_log(adb, serial, path)
        if any(value in latest for value in FORBIDDEN):
            raise RuntimeError("P1_09_FORBIDDEN_MARKER:" + marker)
        if marker in latest:
            return latest
        time.sleep(0.2)
    raise RuntimeError("P1_09_MARKER_TIMEOUT:" + marker)


def opposite_rotation_value(ready_log: str) -> str:
    """Select the physical opposite of the probe's observed ready orientation.

    The window manager may apply a requested user rotation asynchronously after a
    previous Activity finishes.  Drive the next configuration change from what
    the guest actually reported, rather than assuming a fixed starting posture.
    """
    ready_lines = [line for line in ready_log.splitlines()
                   if "P1_09_ROTATION_READY orientation=" in line]
    if not ready_lines:
        raise RuntimeError("P1_09_ROTATION_READY_NOT_OBSERVED")
    if "orientation=portrait" in ready_lines[-1]:
        return "1"
    if "orientation=landscape" in ready_lines[-1]:
        return "0"
    raise RuntimeError("P1_09_UNKNOWN_READY_ORIENTATION:" + ready_lines[-1])


def start_debug(adb: str, serial: str, run_id: str, label: str, command_name: str,
                component: str, force_stop: bool = False,
                expected_operation_status: str = "LAUNCH_PASS") -> dict[str, Any]:
    if force_stop:
        require(command([adb, "-s", serial, "shell", "am", "force-stop", HOST]),
                "force-stop-host-" + label)
    require(command([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                     "files/debug-command-result.json"]), "clear-debug-" + label)
    request_id = run_id + "-" + label
    started = command([adb, "-s", serial, "shell", "am", "start", "-W", "-n",
                       HOST_COMPONENT, "--es", "command", command_name,
                       "--es", "package", FIXTURE, "--es", "component", component,
                       "--ei", "user", "0", "--es", "requestId", request_id,
                       "--ez", "trustNativeGuest", "true"], 60.0)
    require(started, "start-" + label)
    result = wait_debug(adb, serial, command_name, request_id)
    operation = result.get("operation") or {}
    if result.get("status") != "PASS" or operation.get("status") != expected_operation_status:
        raise RuntimeError("P1_09_LAUNCH_NOT_PASS:" + label + ":" + json.dumps(result))
    return {"debug_request_id": request_id, "start": started, "result": result,
            "operation": operation}


def require_visual_terminal(case: dict[str, Any], label: str) -> None:
    operation = case["operation"]
    if not operation.get("firstFrameDrawn", False):
        raise RuntimeError("P1_09_FIRST_FRAME_MISSING:" + label)
    if not operation.get("requestId") or not operation.get("sessionId"):
        raise RuntimeError("P1_09_CORRELATION_FIELDS_MISSING:" + label)
    if int(operation.get("generation", 0)) < 1:
        raise RuntimeError("P1_09_GENERATION_MISSING:" + label)
    if operation.get("launchReadinessPending", False):
        raise RuntimeError("P1_09_TERMINAL_PENDING:" + label)


def runtime_package_preflight(adb: str, serial: str, run_id: str) -> dict[str, Any]:
    """Establish only the read-only Runtime/Package-service boundary before import.

    These probes do not import, ensure an instance, mutate policy, or retry the later import.
    A cold Host update can publish the two services on different scheduler turns.
    """
    attempts: list[dict[str, Any]] = []
    for index in range(1, 6):
        label = "runtime-package-ready-" + str(index)
        try:
            candidate = start_debug(adb, serial, run_id, label, "runtime-package-ready", VISUAL,
                                    expected_operation_status="RUNTIME_PACKAGE_READY")
        except RuntimeError as error:
            attempts.append({"label": label, "error": str(error)})
            continue
        attempts.append(candidate)
        return {"attempts": attempts, "ready": candidate}
    raise RuntimeError("P1_09_RUNTIME_PACKAGE_PREFLIGHT_EXHAUSTED:" + json.dumps(attempts))


def get_setting(adb: str, serial: str, name: str) -> str:
    result = command([adb, "-s", serial, "shell", "settings", "get", "system", name])
    require(result, "get-setting-" + name)
    return result["stdout"].strip()


def put_setting(adb: str, serial: str, name: str, value: str) -> None:
    require(command([adb, "-s", serial, "shell", "settings", "put", "system", name, value]),
            "put-setting-" + name)


def restore_setting(adb: str, serial: str, name: str, value: str) -> None:
    # `null` is what Android settings returns for an unset value; delete restores that state.
    if value in ("", "null"):
        require(command([adb, "-s", serial, "shell", "settings", "delete", "system", name]),
                "restore-setting-" + name)
    else:
        put_setting(adb, serial, name, value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(OUT))
    args = parser.parse_args()
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-09-%Y%m%dT%H%M%SZ")
    directory = Path(args.output_root).resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    adb = adb_path()
    host_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    fixture_apk = ROOT / "fixture-basic" / "build" / "outputs" / "apk" / "debug" / "fixture-basic-debug.apk"
    receipt: dict[str, Any] = {
        "task_id": "P1-09", "run_id": run_id, "attempt": 1, "diagnostic_retry": False,
        "started_at": now(), "device_metadata": metadata(adb, args.serial),
        "apk_sha256": {"host": sha256(host_apk), "fixture": sha256(fixture_apk)},
        "cases": [], "rotation_settings": {},
    }
    original_rotation: dict[str, str] = {}
    try:
        for apk in (host_apk, fixture_apk):
            require(command([adb, "-s", args.serial, "install", "-r", str(apk)], 90.0),
                    "install-" + apk.name)
        receipt["runtime_package_preflight"] = runtime_package_preflight(adb, args.serial, run_id)
        imported = start_debug(adb, args.serial, run_id, "import", "import-only", VISUAL,
                               expected_operation_status="IMPORTED")
        if (imported["result"].get("operation") or {}).get("status") != "IMPORTED":
            raise RuntimeError("P1_09_IMPORT_NOT_PASS")

        # Three cold and three warm visual launches.  Every result must be a new terminal
        # receipt, not a stale token or merely a resumed process.
        visual_request_ids: set[str] = set()
        for index in range(1, 7):
            label = ("cold" if index <= 3 else "hot") + "-frame-" + str(index if index <= 3 else index - 3)
            clear_logcat(adb, args.serial)
            case = start_debug(adb, args.serial, run_id, label, "p1-09-visual-launch", VISUAL,
                               force_stop=index <= 3)
            require_visual_terminal(case, label)
            terminal_request = str(case["operation"].get("requestId"))
            if terminal_request in visual_request_ids:
                raise RuntimeError("P1_09_DUPLICATE_TERMINAL_REQUEST:" + terminal_request)
            visual_request_ids.add(terminal_request)
            log = wait_log(adb, args.serial, directory / (label + "-logcat.txt"),
                           "P1_09_VISUAL_FINISHED")
            case["fixture_marker"] = "P1_09_VISUAL_FINISHED" in log
            receipt["cases"].append({"name": label, "kind": "first_frame", **case})

        # Existing package-neutral fixture performs A(singleTask) -> B -> A, records its real
        # onNewIntent handoff, then requests Back and waits for that physical boundary.
        for index in range(1, 4):
            label = "single-task-back-" + str(index)
            # The fixture's lifecycle counters intentionally model one Android process.  Make
            # each required three-run acceptance coordinate independently attempt=1 instead of
            # letting the previous fixture's static state manufacture a second-onCreate failure.
            require(command([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                    "force-stop-before-" + label)
            clear_logcat(adb, args.serial)
            case = start_debug(adb, args.serial, run_id, label, "launch-component", SINGLE_TASK)
            log = wait_log(adb, args.serial, directory / (label + "-logcat.txt"),
                           'FRAMEWORK_TASK_EVENT {"case":"single_task","event":"BACK_COMPLETE"')
            if '"onNewIntent":1' not in log:
                raise RuntimeError("P1_09_SINGLE_TASK_OR_BACK_EVIDENCE_MISSING:" + label)
            receipt["cases"].append({"name": label, "kind": "nested_single_task_back", **case})

        for index in range(1, 4):
            label = "activity-result-" + str(index)
            require(command([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                    "force-stop-before-" + label)
            clear_logcat(adb, args.serial)
            case = start_debug(adb, args.serial, run_id, label, "launch-component", RESULT_PARENT)
            log = wait_log(adb, args.serial, directory / (label + "-logcat.txt"),
                           "FRAMEWORK_PROBE_ACTIVITY_RESULT_PASS requestCode=701 resultCode=-1")
            receipt["cases"].append({"name": label, "kind": "activity_result", **case})

        original_rotation = {name: get_setting(adb, args.serial, name)
                             for name in ("accelerometer_rotation", "user_rotation")}
        receipt["rotation_settings"]["before"] = original_rotation
        for index in range(1, 4):
            label = "rotation-" + str(index)
            require(command([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                    "force-stop-before-rotation-" + str(index))
            put_setting(adb, args.serial, "accelerometer_rotation", "0")
            clear_logcat(adb, args.serial)
            case = start_debug(adb, args.serial, run_id, label, "p1-09-visual-launch", ROTATION)
            require_visual_terminal(case, label)
            ready_log = wait_log(adb, args.serial, directory / (label + "-ready-logcat.txt"),
                                 "P1_09_ROTATION_READY")
            put_setting(adb, args.serial, "user_rotation", opposite_rotation_value(ready_log))
            log = wait_log(adb, args.serial, directory / (label + "-logcat.txt"),
                           "P1_09_ROTATION_PASS")
            receipt["cases"].append({"name": label, "kind": "rotation", **case,
                                       "rotation_pass": "P1_09_ROTATION_PASS" in log})
        receipt["result"] = "PASS"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        receipt["result"] = "FAIL"
        receipt["error"] = error.__class__.__name__ + ": " + str(error)
    finally:
        if original_rotation:
            try:
                for name, value in original_rotation.items():
                    restore_setting(adb, args.serial, name, value)
                receipt["rotation_settings"]["restored"] = True
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                receipt["rotation_settings"]["restored"] = False
                receipt["rotation_settings"]["restore_error"] = str(error)
                receipt["result"] = "FAIL"
        receipt["finished_at"] = now()
        (directory / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n",
                                               encoding="utf-8")
    print(json.dumps({"run_dir": str(directory), "result": receipt.get("result")}))
    return 0 if receipt.get("result") == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
