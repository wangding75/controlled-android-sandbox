"""Run P1-10's bounded large-Intent Activity transport checks on one AVD.

Each case is a fresh attempt=1 launch.  Positive payload bytes are generated inside the Host;
only the byte count crosses the ADB command boundary.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import time


ROOT = Path(__file__).resolve().parents[2]
HOST = "com.warden.controlledsandbox.debug"
FIXTURE = "com.warden.controlledsandbox.fixture"
COMPONENT = HOST + "/com.warden.controlledsandbox.DebugCommandActivity"


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    return str(Path(sdk) / "platform-tools" / "adb.exe")


def run(args: list[str], timeout: float = 90.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, capture_output=True, timeout=timeout, check=False)


def require(result: subprocess.CompletedProcess[str], label: str) -> None:
    if result.returncode:
        raise RuntimeError(label + ":" + (result.stderr or result.stdout).strip())


def invoke(adb: str, serial: str, run_id: str, label: str, command: str,
           payload_bytes: int | None = None) -> dict:
    request_id = run_id + "-" + label
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear-" + label)
    args = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", COMPONENT,
            "--es", "command", command, "--es", "package", FIXTURE,
            "--ei", "user", "0", "--es", "requestId", request_id,
            "--ez", "trustNativeGuest", "true"]
    if payload_bytes is not None:
        args.extend(["--ei", "payloadBytes", str(payload_bytes)])
    require(run(args), "start-" + label)
    end = time.monotonic() + 90
    while time.monotonic() < end:
        result = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                      "files/debug-command-result.json"])
        if result.returncode == 0:
            try:
                value = json.loads(result.stdout)
            except json.JSONDecodeError:
                value = None
            if isinstance(value, dict) and value.get("requestId") == request_id:
                return value
        time.sleep(.2)
    raise RuntimeError("result-timeout:" + label)


def log(adb: str, serial: str, marker: str) -> str:
    end = time.monotonic() + 30
    while time.monotonic() < end:
        result = run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                      "CS_FIXTURE:I", "AndroidRuntime:E"])
        require(result, "logcat")
        if "TransactionTooLargeException" in result.stdout:
            raise RuntimeError("transaction-too-large")
        if marker in result.stdout:
            return result.stdout
        time.sleep(.2)
    raise RuntimeError("marker-timeout:" + marker)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    adb = adb_path()
    out = ROOT / "out" / "verification" / args.run_id
    out.mkdir(parents=True, exist_ok=False)
    receipt: dict = {"task_id": "P1-10", "run_id": args.run_id, "attempt": 1,
                     "diagnostic_retry": False, "cases": []}
    try:
        for apk in (ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                    ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"):
            require(run([adb, "-s", args.serial, "install", "-r", str(apk)]), "install")
        ready = invoke(adb, args.serial, args.run_id, "ready", "runtime-package-ready")
        if ready.get("status") != "PASS":
            raise RuntimeError("runtime-not-ready")
        imported = invoke(adb, args.serial, args.run_id, "import", "import-only")
        if imported.get("status") != "PASS":
            raise RuntimeError("import-failed")
        for size in (270596, 283304, 308616, 1_000_000):
            label = "bytes-" + str(size)
            require(run([adb, "-s", args.serial, "shell", "logcat", "-c"]), "clear-log")
            result = invoke(adb, args.serial, args.run_id, label, "p1-10-payload-launch", size)
            if result.get("status") != "PASS" or (result.get("operation") or {}).get("status") != "LAUNCH_PASS":
                raise RuntimeError("positive-failed:" + label + ":" + json.dumps(result))
            logs = log(adb, args.serial, "P1_10_PAYLOAD_PASS bytes=" + str(size))
            (out / (label + ".logcat.txt")).write_text(logs, encoding="utf-8")
            receipt["cases"].append({"label": label, "result": result, "pass": True})
        over = invoke(adb, args.serial, args.run_id, "over-limit", "p1-10-payload-launch", 1_048_577)
        error = str(over.get("error") or over.get("errorMessage") or json.dumps(over))
        if over.get("status") != "FAIL" or "INTENT_PAYLOAD_TOO_LARGE" not in error:
            raise RuntimeError("over-limit-not-explicit:" + json.dumps(over))
        receipt["cases"].append({"label": "over-limit", "result": over, "pass": True})
        receipt["result"] = "PASS"
    except Exception as error:
        receipt["result"] = "FAIL"
        receipt["error"] = type(error).__name__ + ":" + str(error)
    receipt["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    (out / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(out), "result": receipt["result"]}))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
