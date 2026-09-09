"""Run P1-14's bounded offline WebView/GMS boundary checks on one API35/36 AVD.

Every command is one attempt.  Renderer recovery is a separately observed event and never
rewrites a missing initial WebView result.  The runner does not log in, access a network page,
select an external file, or claim a GMS API result.
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
    # Android logcat is UTF-8 regardless of this Windows host's legacy console code page.
    # Decode it explicitly so a third-party package log cannot terminate evidence collection.
    return subprocess.run(args, text=True, encoding="utf-8", errors="replace",
                          capture_output=True, timeout=timeout, check=False)


def require(result: subprocess.CompletedProcess[str], label: str) -> None:
    if result.returncode:
        raise RuntimeError(label + ":" + (result.stderr or result.stdout).strip())


def device_metadata(adb: str, serial: str) -> dict:
    def prop(name: str) -> str:
        value = run([adb, "-s", serial, "shell", "getprop", name])
        require(value, "getprop-" + name)
        return value.stdout.strip()
    return {
        "serial": serial,
        "fingerprint": prop("ro.build.fingerprint"),
        "api": prop("ro.build.version.sdk"),
        "abi": prop("ro.product.cpu.abi"),
        "page_size": run([adb, "-s", serial, "shell", "getconf", "PAGESIZE"]).stdout.strip(),
    }


def apk_metadata() -> dict:
    result = {}
    for label, path in {
        "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
    }.items():
        result[label] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    return result


def invoke(adb: str, serial: str, run_id: str, label: str, user: int, token: str,
           write_token: bool, crash_renderer: bool) -> dict:
    request_id = run_id + "-" + label
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear-result-" + label)
    args = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", COMPONENT,
            "--es", "command", "p1-14-webview-probe", "--es", "package", FIXTURE,
            "--ei", "user", str(user), "--es", "requestId", request_id,
            "--es", "token", token, "--ez", "writeToken", "true" if write_token else "false",
            "--ez", "crashRenderer", "true" if crash_renderer else "false",
            "--ez", "trustNativeGuest", "true"]
    require(run(args), "start-" + label)
    deadline = time.monotonic() + 90.0
    while time.monotonic() < deadline:
        value = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                     "files/debug-command-result.json"])
        if value.returncode == 0:
            try:
                result = json.loads(value.stdout)
            except json.JSONDecodeError:
                result = None
            if isinstance(result, dict) and result.get("requestId") == request_id:
                if result.get("status") != "PASS":
                    raise RuntimeError(label + ":" + json.dumps(result, ensure_ascii=False))
                return result
        time.sleep(.2)
    raise RuntimeError("result-timeout:" + label)


def collect_log(adb: str, serial: str, request_id: str, require_renderer: bool) -> str:
    required = ["P114_JS_INITIAL request=" + request_id,
                "P114_FILE_CHOOSER_REQUEST request=" + request_id,
                "P114_JS_NAVIGATION request=" + request_id]
    if require_renderer:
        required += ["P114_RENDERER_GONE request=" + request_id,
                     "P114_JS_RECOVERED request=" + request_id]
    deadline = time.monotonic() + 35.0
    latest = ""
    while time.monotonic() < deadline:
        result = run([adb, "-s", serial, "logcat", "-d", "-v", "brief"])
        require(result, "logcat")
        latest = result.stdout or ""
        if all(marker in latest for marker in required):
            if "P114_PROVIDER_ASSET_FAILED request=" + request_id in latest:
                raise RuntimeError("provider-asset-failed:" + request_id)
            if "P114_FILE_CHOOSER_NOT_OBSERVED request=" + request_id in latest:
                raise RuntimeError("file-chooser-not-observed:" + request_id)
            if "P114_RENDERER_TIMEOUT request=" + request_id in latest:
                raise RuntimeError("renderer-timeout:" + request_id)
            return latest
        time.sleep(.25)
    raise RuntimeError("missing-p114-markers:" + request_id + ":" + repr(required))


def wait_initial_then_click_file(adb: str, serial: str, request_id: str) -> None:
    deadline = time.monotonic() + 25.0
    marker = "P114_JS_INITIAL request=" + request_id
    while time.monotonic() < deadline:
        result = run([adb, "-s", serial, "logcat", "-d", "-v", "brief"])
        require(result, "logcat-initial")
        if marker in (result.stdout or ""):
            # The fixture deliberately places its only file input at this stable visible point.
            # Use one real input event; JavaScript-triggered chooser requests have no user
            # activation and are correctly rejected by Chromium.
            require(run([adb, "-s", serial, "shell", "input", "tap", "180", "640"]),
                    "tap-file-input")
            return
        time.sleep(.2)
    raise RuntimeError("missing-p114-initial-before-file-tap:" + request_id)


def stop(adb: str, serial: str, run_id: str, user: int) -> None:
    request_id = run_id + "-stop-u" + str(user)
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear-stop-result-u" + str(user))
    args = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", COMPONENT,
            "--es", "command", "stop", "--es", "package", FIXTURE, "--ei", "user", str(user),
            "--es", "requestId", request_id, "--ez", "trustNativeGuest", "true"]
    require(run(args), "start-stop-u" + str(user))
    deadline = time.monotonic() + 45.0
    while time.monotonic() < deadline:
        value = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                     "files/debug-command-result.json"])
        try:
            result = json.loads(value.stdout) if value.returncode == 0 else None
        except json.JSONDecodeError:
            result = None
        if isinstance(result, dict) and result.get("requestId") == request_id:
            if result.get("status") != "PASS":
                raise RuntimeError("stop-u" + str(user) + ":" + json.dumps(result))
            return
        time.sleep(.2)
    raise RuntimeError("stop-timeout-u" + str(user))


def run_case(adb: str, serial: str, run_id: str, label: str, user: int, token: str,
             write_token: bool, crash_renderer: bool, output: Path, api: int) -> dict:
    stop(adb, serial, run_id, user)
    require(run([adb, "-s", serial, "shell", "logcat", "-c"]), "clear-log-" + label)
    result = invoke(adb, serial, run_id, label, user, token, write_token, crash_renderer)
    request_id = run_id + "-" + label
    wait_initial_then_click_file(adb, serial, request_id)
    log = collect_log(adb, serial, request_id, crash_renderer)
    if api >= 36 and "P114_PROVIDER_ASSET_READ request=" + request_id not in log:
        raise RuntimeError("api36-provider-asset-not-readable:" + request_id)
    expected = "input-" + token
    if expected not in log or token not in log:
        raise RuntimeError("token-or-input-not-observed:" + request_id)
    if crash_renderer:
        gone = log.index("P114_RENDERER_GONE request=" + request_id)
        if "APPLICATION_CREATE" in log[gone:]:
            raise RuntimeError("application-recreated-after-renderer-loss:" + request_id)
    (output / (label + ".logcat.txt")).write_text(log, encoding="utf-8")
    return {"label": label, "user": user, "token": token, "writeToken": write_token,
            "rendererCrash": crash_renderer, "debugResult": result, "pass": True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    adb = adb_path()
    output = ROOT / "out" / "verification" / args.run_id
    output.mkdir(parents=True, exist_ok=False)
    receipt: dict = {"task_id": "P1-14", "run_id": args.run_id, "attempt": 1,
                     "automatic_retry_performed": False, "device": device_metadata(adb, args.serial),
                     "apks": apk_metadata(), "cases": [],
                     "gms": {"status": "DEFERRED", "reason": "P1-14 does not exercise GMS login/API; P2-10 owns that conditional scope"}}
    try:
        api = int(receipt["device"]["api"])
        if api not in (35, 36):
            raise RuntimeError("P1-14 requires API35 or API36, got " + str(api))
        for apk in (ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                    ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"):
            require(run([adb, "-s", args.serial, "install", "-r", str(apk)], 180.0),
                    "install-" + apk.name)
        receipt["cases"].append(run_case(adb, args.serial, args.run_id, "u0-write", 0,
                "u0_p114", True, False, output, api))
        receipt["cases"].append(run_case(adb, args.serial, args.run_id, "u1-write", 1,
                "u1_p114", True, False, output, api))
        receipt["cases"].append(run_case(adb, args.serial, args.run_id, "u0-read-renderer", 0,
                "u0_p114", False, True, output, api))
        receipt["storageIsolation"] = {"user0": "u0_p114", "user1": "u1_p114",
                                        "user0ReadBackAfterUser1": "u0_p114"}
        receipt["result"] = "PASS"
    except Exception as error:
        receipt["result"] = "FAIL"
        receipt["error"] = type(error).__name__ + ":" + str(error)
    receipt["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    (output / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(output), "result": receipt["result"]}, ensure_ascii=False))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
