"""Run P1-04's native and CAS Application/LoadedApk/Provider bootstrap checks.

The runner performs one native baseline plus three cold and three hot CAS launches.  It does not
retry a failed coordinate: every invocation has a unique request id and its first result/logcat
is retained under the requested run directory.
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
DEFAULT_OUTPUT_ROOT = ROOT / "out" / "verification"
HOST_PACKAGE = "com.warden.controlledsandbox.debug"
HOST_COMPONENT = HOST_PACKAGE + "/com.warden.controlledsandbox.DebugCommandActivity"
FIXTURE_PACKAGE = "com.warden.controlledsandbox.fixture.lifecycle"
FIXTURE_COMPONENT = FIXTURE_PACKAGE + "/.LifecycleActivity"
MARKER = "P1_04_BOOTSTRAP_PASS factoryApplication=1 applicationAttach=1 provider=1 applicationOnCreate=1 ordered=true"


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _adb() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    adb = Path(sdk) / "platform-tools" / "adb.exe"
    if not adb.is_file():
        raise FileNotFoundError(f"adb was not found: {adb}")
    return str(adb)


def _run(command: list[str], timeout: float = 60.0) -> dict[str, Any]:
    completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    return {"command": command, "returncode": completed.returncode,
            "stdout": completed.stdout, "stderr": completed.stderr}


def _require(result: dict[str, Any], label: str) -> None:
    if result["returncode"] != 0:
        raise RuntimeError(f"{label} failed: {result['stderr'] or result['stdout']}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _metadata(adb: str, serial: str) -> dict[str, str]:
    result: dict[str, str] = {"serial": serial}
    for key, command in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        response = _run([adb, "-s", serial, *command])
        _require(response, f"read {key}")
        result[key] = response["stdout"].strip()
    return result


def _capture_log(adb: str, serial: str, path: Path) -> str:
    response = _run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                     "CS_P1_04_FIXTURE:I", "CS_RUNTIME:I", "CS_EVENT:I", "AndroidRuntime:E"])
    _require(response, "read logcat")
    path.write_text(response["stdout"], encoding="utf-8")
    return response["stdout"]


def _debug(adb: str, serial: str, request_id: str, cold: bool) -> dict[str, Any]:
    if cold:
        _require(_run([adb, "-s", serial, "shell", "am", "force-stop", HOST_PACKAGE]),
                 "force-stop host")
    _require(_run([adb, "-s", serial, "shell", "run-as", HOST_PACKAGE, "rm", "-f",
                   "files/debug-command-result.json"]), "clear debug result")
    response = _run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                     "--es", "command", "launch-component", "--es", "package", FIXTURE_PACKAGE,
                     "--es", "component", FIXTURE_PACKAGE + ".LifecycleActivity", "--ei", "user", "0",
                     "--es", "requestId", request_id, "--ez", "trustNativeGuest", "true"], 45.0)
    _require(response, "launch fixture through debug host")
    return _wait_debug_result(adb, serial, request_id, response)


def _wait_debug_result(adb: str, serial: str, request_id: str,
                       command_start: dict[str, Any]) -> dict[str, Any]:
    deadline = time.monotonic() + 45.0
    last = ""
    while time.monotonic() < deadline:
        result = _run([adb, "-s", serial, "shell", "run-as", HOST_PACKAGE, "cat",
                       "files/debug-command-result.json"])
        if result["returncode"] == 0:
            last = result["stdout"].strip()
            try:
                value = json.loads(last)
            except json.JSONDecodeError:
                value = None
            if isinstance(value, dict) and value.get("requestId") == request_id:
                value["command_start"] = command_start
                return value
        time.sleep(0.2)
    raise RuntimeError(f"DEBUG_RESULT_TIMEOUT:{request_id}:{last[:300]}")


def _operation_pass(value: dict[str, Any]) -> bool:
    operation = value.get("operation")
    return value.get("status") == "PASS" and isinstance(operation, dict) and operation.get("status") == "LAUNCH_PASS"


def _bootstrap_suite(adb: str, serial: str, run_dir: Path, request_id: str) -> dict[str, Any]:
    _require(_run([adb, "-s", serial, "logcat", "-c"]), "clear logcat")
    _require(_run([adb, "-s", serial, "shell", "am", "force-stop", HOST_PACKAGE]),
             "force-stop host before bootstrap suite")
    _require(_run([adb, "-s", serial, "shell", "run-as", HOST_PACKAGE, "rm", "-f",
                   "files/debug-command-result.json"]), "clear suite result")
    start = _run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                  "--es", "command", "p1-04-bootstrap-suite", "--es", "package", FIXTURE_PACKAGE,
                  "--es", "component", FIXTURE_PACKAGE + ".LifecycleActivity", "--ei", "launches", "2",
                  "--ei", "user", "0", "--es", "requestId", request_id,
                  "--ez", "trustNativeGuest", "true"], 45.0)
    _require(start, "start bootstrap suite")
    result = _wait_debug_result(adb, serial, request_id, start)
    time.sleep(0.5)
    logcat = _capture_log(adb, serial, run_dir / f"{request_id}-logcat.txt")
    attempts = result.get("bootstrapAttempts", [])
    return {"request_id": request_id, "result": result, "marker_count": logcat.count(MARKER),
            "attempts": attempts, "cold_attempts": 1, "hot_attempts": 1}


def run(args: argparse.Namespace) -> tuple[int, Path, dict[str, Any]]:
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-04-bootstrap-%Y%m%dT%H%M%SZ")
    run_dir = Path(args.output_root).resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    adb = _adb()
    host_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    fixture_apk = (Path(args.fixture_apk).resolve() if args.fixture_apk else ROOT / "fixture-lifecycle"
                   / "build" / "outputs" / "apk" / "v1" / "debug"
                   / "fixture-lifecycle-v1-debug.apk")
    for apk in (host_apk, fixture_apk):
        if not apk.is_file():
            raise FileNotFoundError(f"missing required APK: {apk}")
    payload: dict[str, Any] = {
        "task_id": "P1-04", "run_id": run_id, "started_at": _now(),
        "device_metadata": _metadata(adb, args.serial),
        "apk_sha256": {"host": _sha256(host_apk), "fixture": _sha256(fixture_apk)},
        "expected_marker": MARKER, "attempt": 1, "diagnostic_retry": False,
        "limitations": ["Chrome has not been run by this AVD fixture.",
                        "Chrome ARM64/OEM bootstrap evidence remains P2-02 scope."],
    }
    try:
        installs = []
        for apk in (host_apk, fixture_apk):
            response = _run([adb, "-s", args.serial, "install", "-r", str(apk)], 90.0)
            _require(response, f"install {apk.name}")
            installs.append(response)
        payload["installs"] = installs
        _require(_run([adb, "-s", args.serial, "shell", "am", "force-stop", FIXTURE_PACKAGE]),
                 "force-stop native fixture")
        _require(_run([adb, "-s", args.serial, "logcat", "-c"]), "clear native logcat")
        native = _run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", FIXTURE_COMPONENT])
        _require(native, "native fixture launch")
        time.sleep(0.5)
        native_log = _capture_log(adb, args.serial, run_dir / "native-logcat.txt")
        payload["native_baseline"] = {"start": native, "marker": MARKER in native_log}
        _require(_run([adb, "-s", args.serial, "shell", "am", "force-stop", HOST_PACKAGE]),
                 "force-stop host before import")
        _require(_run([adb, "-s", args.serial, "shell", "run-as", HOST_PACKAGE, "rm", "-f",
                       "files/debug-command-result.json"]), "clear import result")
        import_result = _run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                              "--es", "command", "import-only", "--es", "package", FIXTURE_PACKAGE,
                              "--ei", "user", "0", "--es", "requestId", run_id + "-import",
                              "--ez", "trustNativeGuest", "true"], 45.0)
        _require(import_result, "import fixture")
        payload["import"] = _wait_debug_result(adb, args.serial, run_id + "-import",
                                                import_result)
        if payload["import"].get("status") != "PASS" or payload["import"].get("operation", {}).get("status") != "IMPORTED":
            raise RuntimeError("CAS_IMPORT_DID_NOT_PASS")
        payload["cas_suites"] = [_bootstrap_suite(adb, args.serial, run_dir,
                                                   f"{run_id}-suite-{index}")
                                 for index in range(1, 4)]
        payload["result"] = "PASS" if payload["native_baseline"]["marker"] and all(
            suite["result"].get("status") == "PASS"
            and suite["result"].get("operation", {}).get("status") == "P1_04_BOOTSTRAP_SUITE_PASS"
            and len(suite["attempts"]) == 2
            and suite["attempts"][0].get("mode") == "cold"
            and suite["attempts"][1].get("mode") == "hot"
            and all(item.get("status") == "LAUNCH_PASS" for item in suite["attempts"])
            and suite["marker_count"] >= 1 for suite in payload["cas_suites"]) else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        payload["result"] = "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = _now()
    (run_dir / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return (0 if payload["result"] == "PASS" else 2), run_dir, payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    parser.add_argument("--fixture-apk", default="",
                        help="normal lifecycle fixture APK; defaults to v1")
    args = parser.parse_args()
    code, run_dir, payload = run(args)
    print(json.dumps({"run_dir": str(run_dir), "result": payload["result"]}))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
