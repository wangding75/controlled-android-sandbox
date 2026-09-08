"""Run P1-06's bounded Provider route and lifecycle acceptance on one API35/36 AVD.

The campaign is a real Guest Context/ContentResolver workload.  It deliberately records the
first result for each user/iteration and never retries a failed coordinate.
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
PEER = "com.warden.controlledsandbox.fixture32"
NATIVE_COMPONENT = FIXTURE + "/.ProviderCampaignActivity"
GUEST_COMPONENT = FIXTURE + ".ProviderCampaignActivity"
MARKERS = (
    "C1_T04_PROVIDER_CRUD_PASS",
    "C1_T04_PROVIDER_CURSOR_PASS",
    "C1_T04_PROVIDER_BATCH_PASS",
    "C1_T04_PROVIDER_FD_PASS",
    "C1_T04_PROVIDER_GRANT_PASS",
    "C1_T04_PROVIDER_CANCEL_PASS",
    "C1_T04_PROVIDER_OBSERVER_PASS",
    "C1_T04_PROVIDER_CROSS_PACKAGE_PASS",
    "C1_T04_PROVIDER_PASS",
)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    adb = Path(sdk) / "platform-tools" / "adb.exe"
    if not adb.is_file():
        raise FileNotFoundError(adb)
    return str(adb)


def run(command: list[str], timeout: float = 60.0) -> dict[str, Any]:
    completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    return {"command": command, "returncode": completed.returncode,
            "stdout": completed.stdout, "stderr": completed.stderr}


def require(value: dict[str, Any], label: str) -> None:
    if value["returncode"] != 0:
        raise RuntimeError(f"{label}: {value['stderr'] or value['stdout']}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def metadata(adb: str, serial: str) -> dict[str, str]:
    result = {"serial": serial}
    for name, arguments in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        value = run([adb, "-s", serial, *arguments])
        require(value, f"read {name}")
        result[name] = value["stdout"].strip()
    return result


def logcat(adb: str, serial: str, output: Path) -> str:
    value = run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                 "CS_PROVIDER_CAMPAIGN:I", "CS_PROVIDER_ROUTE:I", "CS_HOST_PMS:I",
                 "AndroidRuntime:E"])
    require(value, "read logcat")
    output.write_text(value["stdout"], encoding="utf-8")
    return value["stdout"]


def wait_markers(adb: str, serial: str, directory: Path, label: str) -> str:
    deadline = time.monotonic() + 30.0
    latest = ""
    while time.monotonic() < deadline:
        latest = logcat(adb, serial, directory / f"{label}-logcat.txt")
        if all(marker in latest for marker in MARKERS):
            return latest
        time.sleep(0.25)
    missing = [marker for marker in MARKERS if marker not in latest]
    raise RuntimeError(f"P1_06_MARKER_TIMEOUT:{label}:{','.join(missing)}")


def wait_debug(adb: str, serial: str, request_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 45.0
    latest = ""
    while time.monotonic() < deadline:
        value = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                     "files/debug-command-result.json"])
        if value["returncode"] == 0:
            latest = value["stdout"].strip()
            try:
                result = json.loads(latest)
            except json.JSONDecodeError:
                result = None
            if isinstance(result, dict) and result.get("requestId") == request_id:
                return result
        time.sleep(0.2)
    raise RuntimeError(f"DEBUG_RESULT_TIMEOUT:{request_id}:{latest[:300]}")


def debug(adb: str, serial: str, command: str, package: str, user: int,
          request_id: str) -> dict[str, Any]:
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear debug result")
    start = run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                 "--es", "command", command, "--es", "package", package, "--ei", "user",
                 str(user), "--es", "requestId", request_id, "--ez", "trustNativeGuest", "true",
                 "--es", "component", GUEST_COMPONENT], 45.0)
    require(start, f"start {command}")
    result = wait_debug(adb, serial, request_id)
    return {"start": start, "result": result}


def debug_pass(row: dict[str, Any], expected: str) -> bool:
    result = row["result"]
    return result.get("status") == "PASS" and result.get("operation", {}).get("status") == expected


def run_campaign(adb: str, serial: str, directory: Path, user: int, iteration: int,
                 run_id: str) -> dict[str, Any]:
    label = f"u{user}-i{iteration}"
    require(run([adb, "-s", serial, "shell", "am", "force-stop", HOST]),
            f"force-stop Host {label}")
    require(run([adb, "-s", serial, "shell", "logcat", "-c"]), f"clear logcat {label}")
    invocation = debug(adb, serial, "launch-component", FIXTURE, user,
                       f"{run_id}-{label}")
    text = wait_markers(adb, serial, directory, label)
    return {"user": user, "iteration": iteration, **invocation,
            "markers": {marker: marker in text for marker in MARKERS}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(OUT))
    args = parser.parse_args()
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-06-provider-%Y%m%dT%H%M%SZ")
    directory = Path(args.output_root).resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    adb = adb_path()
    apks = {
        "host": ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
        "fixture": ROOT / "fixture-basic" / "build" / "outputs" / "apk" / "debug" / "fixture-basic-debug.apk",
        "peer": ROOT / "fixture-compat32" / "build" / "outputs" / "apk" / "debug" / "fixture-compat32-debug.apk",
    }
    payload: dict[str, Any] = {
        "task_id": "P1-06", "run_id": run_id, "started_at": now(), "attempt": 1,
        "diagnostic_retry": False, "device_metadata": metadata(adb, args.serial),
        "apk_sha256": {name: sha256(path) for name, path in apks.items()},
        "required_markers": list(MARKERS), "iterations_per_user": 5,
    }
    try:
        for path in apks.values():
            require(run([adb, "-s", args.serial, "install", "-r", str(path)], 90.0),
                    f"install {path.name}")
        # ProviderCampaignActivity is deliberately non-exported. Its contract is exercised
        # through the CAS Guest launch path below; do not weaken the fixture by shell-launching
        # it as a native external component.
        payload["native_baseline"] = "NOT_APPLICABLE_NON_EXPORTED_FIXTURE_ACTIVITY"
        imports = []
        for user in (0, 1):
            for package in (PEER, FIXTURE):
                row = debug(adb, args.serial, "import-only", package, user,
                            f"{run_id}-import-{package.rsplit('.', 1)[-1]}-u{user}")
                imports.append({"package": package, "user": user, **row})
                if not debug_pass(row, "IMPORTED"):
                    raise RuntimeError(f"IMPORT_NOT_PASSED:{package}:u{user}")
        payload["imports"] = imports
        payload["campaigns"] = [run_campaign(adb, args.serial, directory, user, iteration, run_id)
                                for user in (0, 1) for iteration in range(1, 6)]
        payload["result"] = "PASS" if (all(debug_pass(row, "LAUNCH_PASS") and all(row["markers"].values())
                        for row in payload["campaigns"])) else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        payload["result"] = "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = now()
    (directory / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(directory), "result": payload["result"]}))
    return 0 if payload["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
