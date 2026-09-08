"""Run the P1-03 native/CAS shared-library projection comparison on one API AVD.

The provider/consumer declarations model CAS's virtual Java-library graph.  They do
not claim to be Android PMS static-library packages; Chrome/Trichrome is deferred to
P2-02.  The runner fails closed if an import, Activity creation, or the fixture's
class/resource/asset/sharedLibraryFiles marker is absent.
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
DEBUG_COMPONENT = HOST_PACKAGE + "/com.warden.controlledsandbox.DebugCommandActivity"
PROVIDER_PACKAGE = "com.warden.controlledsandbox.fixture.libraryprovider"
CONSUMER_PACKAGE = "com.warden.controlledsandbox.fixture.libraryconsumer"
CONSUMER_COMPONENT = CONSUMER_PACKAGE + ".LibraryProjectionActivity"
PASS_MARKER = "VIRTUAL_LIBRARY_PROJECTION_PASS class=OK resource=OK asset=OK sharedLibraryFiles=OK"


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    adb = Path(sdk) / "platform-tools" / "adb.exe"
    if not adb.is_file():
        raise FileNotFoundError(f"adb was not found: {adb}")
    return str(adb)


def _run(command: list[str], *, timeout: float = 60.0) -> dict[str, Any]:
    completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    return {
        "command": command,
        "returncode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


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
    commands = {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }
    result: dict[str, str] = {"serial": serial}
    for name, suffix in commands.items():
        response = _run([adb, "-s", serial, *suffix])
        _require(response, f"metadata {name}")
        result[name] = response["stdout"].strip()
    return result


def _debug(adb: str, serial: str, command: str, package: str, request_id: str,
           component: str = "") -> dict[str, Any]:
    _require(_run([adb, "-s", serial, "shell", "am", "force-stop", HOST_PACKAGE]),
             "force-stop debug host")
    _require(_run([adb, "-s", serial, "shell", "run-as", HOST_PACKAGE, "rm", "-f",
                   "files/debug-command-result.json"]), "clear debug result")
    arguments = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", DEBUG_COMPONENT,
                 "--es", "command", command, "--es", "package", package,
                 "--ei", "user", "0", "--es", "requestId", request_id,
                 "--ez", "trustNativeGuest", "true"]
    if component:
        arguments += ["--es", "component", component]
    started = _run(arguments, timeout=30.0)
    _require(started, f"debug start {command}")
    deadline = time.monotonic() + 30.0
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
                value["command_start"] = started
                return value
        time.sleep(0.2)
    raise RuntimeError(f"DEBUG_RESULT_TIMEOUT:{request_id}: {last[:300]}")


def _debug_pass(result: dict[str, Any], expected_operation: str) -> bool:
    operation = result.get("operation")
    return (result.get("status") == "PASS" and isinstance(operation, dict)
            and operation.get("status") == expected_operation)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    return parser


def run(args: argparse.Namespace) -> tuple[int, Path, dict[str, Any]]:
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-03-library-%Y%m%dT%H%M%SZ")
    run_dir = Path(args.output_root).resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    adb = _adb_path()
    apks = {
        "provider": ROOT / "fixture-library-provider" / "build" / "outputs" / "apk" / "debug"
        / "fixture-library-provider-debug.apk",
        "consumer": ROOT / "fixture-library-consumer" / "build" / "outputs" / "apk" / "debug"
        / "fixture-library-consumer-debug.apk",
    }
    missing = [str(path) for path in apks.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"fixture APKs are missing: {missing}")
    payload: dict[str, Any] = {
        "task_id": "P1-03",
        "run_id": run_id,
        "started_at": _now(),
        "mode": "NATIVE_INSTALL_THEN_CAS_VIRTUAL_PROJECTION",
        "device_metadata": _metadata(adb, args.serial),
        "apk_sha256": {name: _sha256(path) for name, path in apks.items()},
        "commands": [],
        "expected_marker": PASS_MARKER,
        "limitations": [
            "This fixture is a CAS virtual Java-library graph, not a claim of Android PMS static-library publication.",
            "Chrome/Trichrome ARM64 validation remains deferred to P2-02.",
        ],
    }
    try:
        clear = _run([adb, "-s", args.serial, "logcat", "-c"])
        _require(clear, "logcat clear")
        payload["commands"].append(clear)
        for name in ("provider", "consumer"):
            install = _run([adb, "-s", args.serial, "install", "-r", str(apks[name])])
            _require(install, f"install {name}")
            payload["commands"].append(install)
        for package in (PROVIDER_PACKAGE, CONSUMER_PACKAGE):
            inspection = _run([adb, "-s", args.serial, "shell", "dumpsys", "package", package])
            _require(inspection, f"package inspection {package}")
            filename = "provider" if package == PROVIDER_PACKAGE else "consumer"
            (run_dir / f"{filename}-package.txt").write_text(inspection["stdout"], encoding="utf-8")
        provider = _debug(adb, args.serial, "import-only", PROVIDER_PACKAGE,
                          run_id + "-provider-import")
        consumer = _debug(adb, args.serial, "import-only", CONSUMER_PACKAGE,
                          run_id + "-consumer-import")
        launch = _debug(adb, args.serial, "launch-component", CONSUMER_PACKAGE,
                        run_id + "-consumer-launch", CONSUMER_COMPONENT)
        payload["cas"] = {"provider_import": provider, "consumer_import": consumer,
                          "projection_launch": launch}
        if not _debug_pass(provider, "IMPORTED") or not _debug_pass(consumer, "IMPORTED"):
            raise RuntimeError("CAS_IMPORT_DID_NOT_PASS")
        if not _debug_pass(launch, "LAUNCH_PASS"):
            raise RuntimeError("CAS_PROJECTION_LAUNCH_DID_NOT_PASS")
        time.sleep(1.0)
        logcat = _run([adb, "-s", args.serial, "logcat", "-d", "-v", "brief", "-s",
                       "CS_P1_03_FIXTURE:I", "CS_COMMAND:I", "AndroidRuntime:E"])
        _require(logcat, "projection logcat")
        (run_dir / "logcat.txt").write_text(logcat["stdout"], encoding="utf-8")
        payload["observed_marker"] = PASS_MARKER in logcat["stdout"]
        payload["result"] = "PASS" if payload["observed_marker"] else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        payload["result"] = "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = _now()
    (run_dir / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return (0 if payload["result"] == "PASS" else 2), run_dir, payload


def main() -> int:
    exit_code, run_dir, payload = run(_parser().parse_args())
    print(json.dumps({"run_dir": str(run_dir), "result": payload["result"],
                      "observed_marker": payload.get("observed_marker", False)}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
