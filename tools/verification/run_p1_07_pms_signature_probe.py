"""Run the P1-07 CAS-only PMS signature probe once on a specified AVD."""

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
COMPONENT = FIXTURE + ".P107PmsSignatureProbeActivity"
MARKER = "P1_07_PMS_SIGNATURE_PASS serviceAbsent=true providerAbsent=true"


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def adb_path() -> str:
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not root:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    return str(Path(root) / "platform-tools" / "adb.exe")


def run(command: list[str], timeout: float = 60.0) -> dict[str, Any]:
    result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    return {"command": command, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr}


def require(result: dict[str, Any], label: str) -> None:
    if result["returncode"] != 0:
        raise RuntimeError(f"{label}: {result['stderr'] or result['stdout']}")


def device_metadata(adb: str, serial: str) -> dict[str, str]:
    fields = {"serial": serial}
    for key, args in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        result = run([adb, "-s", serial, *args])
        require(result, f"metadata {key}")
        fields[key] = result["stdout"].strip()
    return fields


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wait_debug(adb: str, serial: str, request_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 45.0
    last = ""
    while time.monotonic() < deadline:
        result = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                      "files/debug-command-result.json"])
        if result["returncode"] == 0:
            last = result["stdout"].strip()
            try:
                value = json.loads(last)
            except json.JSONDecodeError:
                value = None
            if isinstance(value, dict) and value.get("requestId") == request_id:
                return value
        time.sleep(0.2)
    raise RuntimeError(f"DEBUG_RESULT_TIMEOUT:{request_id}:{last[:300]}")


def wait_logs(adb: str, serial: str, output: Path, expected_service: str,
              expected_provider: str) -> str:
    deadline = time.monotonic() + 20.0
    latest = ""
    while time.monotonic() < deadline:
        result = run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                      "CS_P1_07_FIXTURE:I", "CS_HOST_PMS:I", "CS_PROVIDER_ROUTE:I",
                      "CS_GUEST_RESOLVE_FAIL:E", "AndroidRuntime:E"])
        require(result, "logcat")
        latest = result["stdout"]
        output.write_text(latest, encoding="utf-8")
        if (MARKER in latest and expected_service in latest
                and expected_provider in latest):
            return latest
        time.sleep(0.25)
    raise RuntimeError("P1_07_MARKER_OR_SIGNATURE_TIMEOUT:"
                       f"{expected_service}|{expected_provider}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(OUT))
    args = parser.parse_args()
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-07-pms-%Y%m%dT%H%M%SZ")
    directory = Path(args.output_root).resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    adb = adb_path()
    host_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    fixture_apk = ROOT / "fixture-basic" / "build" / "outputs" / "apk" / "debug" / "fixture-basic-debug.apk"
    payload: dict[str, Any] = {"task_id": "P1-07", "run_id": run_id, "attempt": 1,
        "diagnostic_retry": False, "started_at": now(),
        "device_metadata": device_metadata(adb, args.serial),
        "apk_sha256": {"host": sha256(host_apk), "fixture": sha256(fixture_apk)}}
    try:
        for apk in (host_apk, fixture_apk):
            require(run([adb, "-s", args.serial, "install", "-r", str(apk)], 90.0),
                    f"install {apk.name}")
        api = int(payload["device_metadata"]["api_level"])
        flags_type = "int" if api == 32 else "long"
        expected_service = f"resolveService(Intent,String,{flags_type},int)"
        expected_provider = f"resolveContentProvider(String,{flags_type},int)"
        payload["expected_signatures"] = {"service": expected_service,
                                          "provider": expected_provider}
        require(run([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                "force-stop host")
        require(run([adb, "-s", args.serial, "shell", "run-as", HOST, "rm", "-f",
                     "files/debug-command-result.json"]), "clear result")
        import_id = run_id + "-import"
        payload["import_start"] = run([adb, "-s", args.serial, "shell", "am", "start", "-W",
            "-n", HOST_COMPONENT, "--es", "command", "import-only", "--es", "package", FIXTURE,
            "--ei", "user", "0", "--es", "requestId", import_id, "--ez", "trustNativeGuest", "true"])
        require(payload["import_start"], "import start")
        payload["import"] = wait_debug(adb, args.serial, import_id)
        if payload["import"].get("status") != "PASS":
            raise RuntimeError("IMPORT_NOT_PASS")
        require(run([adb, "-s", args.serial, "shell", "logcat", "-c"]), "clear logcat")
        request_id = run_id + "-probe"
        payload["launch_start"] = run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n",
            HOST_COMPONENT, "--es", "command", "launch-component", "--es", "package", FIXTURE,
            "--es", "component", COMPONENT, "--ei", "user", "0", "--es", "requestId", request_id,
            "--ez", "trustNativeGuest", "true"], 45.0)
        require(payload["launch_start"], "probe start")
        payload["launch"] = wait_debug(adb, args.serial, request_id)
        logs = wait_logs(adb, args.serial, directory / "logcat.txt", expected_service,
                         expected_provider)
        payload["markers"] = {"fixture_pass": MARKER in logs,
                              "selected_service_signature": expected_service in logs,
                              "selected_provider_signature": expected_provider in logs}
        payload["result"] = "PASS" if (payload["launch"].get("status") == "PASS"
                and payload["launch"].get("operation", {}).get("status") == "LAUNCH_PASS"
                and all(payload["markers"].values())) else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        payload["result"] = "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = now()
    (directory / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(directory), "result": payload["result"]}))
    return 0 if payload["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
