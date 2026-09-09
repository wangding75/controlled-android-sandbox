"""Run P1-13's short, no-retry split/package lifecycle regression on one AVD."""
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
SPLIT = "com.warden.controlledsandbox.fixture.split"
FIXTURE = "com.warden.controlledsandbox.fixture"
COMPONENT = HOST + "/com.warden.controlledsandbox.DebugCommandActivity"
BASE_COMPONENT = SPLIT + "/.SplitBaseActivity"
FEATURE_COMPONENT = SPLIT + "/.feature.FeatureActivity"


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    return str(Path(sdk) / "platform-tools" / "adb.exe")


def run(args: list[str], timeout: float = 120.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, capture_output=True, timeout=timeout, check=False)


def require(result: subprocess.CompletedProcess[str], label: str) -> None:
    if result.returncode:
        raise RuntimeError(label + ":" + (result.stderr or result.stdout).strip())


def device(adb: str, serial: str) -> dict[str, str]:
    def shell(*parts: str) -> str:
        value = run([adb, "-s", serial, "shell", *parts])
        require(value, "device-" + "-".join(parts))
        return value.stdout.strip()
    return {"serial": serial, "fingerprint": shell("getprop", "ro.build.fingerprint"),
            "api": shell("getprop", "ro.build.version.sdk"),
            "abi": shell("getprop", "ro.product.cpu.abilist"),
            "page_size": shell("getconf", "PAGESIZE")}


def apk_metadata(base_split: Path, config_split: Path) -> dict[str, dict[str, str | int]]:
    files = {"host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
             "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
             "split_base": base_split,
             "split_config": config_split,
             "split_feature": ROOT / "fixture-split-feature/build/outputs/apk/debug/fixtureSplitFeature-debug.apk"}
    return {name: {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                   "bytes": path.stat().st_size} for name, path in files.items()}


def invoke(adb: str, serial: str, run_id: str, label: str, command: str, package: str,
           user: int, component: str = "", read_only: bool | None = None) -> dict:
    request_id = run_id + "-" + label
    # The debug endpoint is deliberately one-shot and shuts down its only worker after writing a
    # receipt.  Force-stop only that debug Host before the next independent attempt so Android
    # cannot deliver the new Intent to a retiring Activity whose executor is already shut down.
    # This is a harness lifecycle fence, not an operation retry; package/catalog state is
    # persistent and every command still has one attempt and retryBudget=0.
    require(run([adb, "-s", serial, "shell", "am", "force-stop", HOST]), "fence-" + label)
    time.sleep(.3)
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear-" + label)
    args = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", COMPONENT,
            "--es", "command", command, "--es", "package", package,
            "--ei", "user", str(user), "--es", "requestId", request_id,
            "--ez", "trustNativeGuest", "true"]
    if component:
        args.extend(["--es", "component", component])
    if read_only is not None:
        args.extend(["--ez", "readOnly", "true" if read_only else "false"])
    require(run(args), "start-" + label)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
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


def require_status(value: dict, expected: str, label: str) -> dict:
    actual = (value.get("operation") or {}).get("status")
    if value.get("status") != "PASS" or actual != expected:
        raise RuntimeError(label + ":" + json.dumps(value, ensure_ascii=False))
    return value


def logs(adb: str, serial: str, expected: str, label: str, output: Path) -> None:
    value = run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                 "CS_SPLIT_FIXTURE:I", "CS_COMMAND:I", "AndroidRuntime:E"])
    require(value, "logcat-" + label)
    (output / (label + ".logcat.txt")).write_text(value.stdout, encoding="utf-8")
    if expected not in value.stdout:
        raise RuntimeError("missing-marker-" + label + ":" + expected)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--base-apk", required=True,
                        help="bundletool-selected base master APK paired with --config-split")
    parser.add_argument("--config-split", required=True,
                        help="bundletool-selected configuration APK for this device")
    args = parser.parse_args()
    adb = adb_path()
    base_split = Path(args.base_apk).resolve()
    config_split = Path(args.config_split).resolve()
    for label, path in (("base APK", base_split), ("configuration split", config_split)):
        if not path.is_file():
            raise RuntimeError(label + " does not exist: " + str(path))
    output = ROOT / "out" / "verification" / args.run_id
    output.mkdir(parents=True, exist_ok=False)
    receipt: dict = {"task_id": "P1-13", "run_id": args.run_id, "attempt": 1,
                     "automatic_retry_performed": False, "device": device(adb, args.serial),
                     "apks": apk_metadata(base_split, config_split), "cases": [],
                     "scope": {"configuration_split": "BUNDLETOOL_SELECTED_LANGUAGE_SPLIT",
                               "native_abi": "NO_NATIVE_CODE_IN_SPLIT_FIXTURE"}}
    try:
        for path in (ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                     ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"):
            require(run([adb, "-s", args.serial, "install", "-r", str(path)]), "install-" + path.name)
        require(run([adb, "-s", args.serial, "install-multiple", "-r", str(base_split),
                     str(config_split),
                     str(ROOT / "fixture-split-feature/build/outputs/apk/debug/fixtureSplitFeature-debug.apk")]),
                "install-split-set")
        installed_paths = run([adb, "-s", args.serial, "shell", "pm", "path", SPLIT])
        require(installed_paths, "inspect-split-set")
        required_splits = ("base.apk", "split_config.", "split_fixtureSplitFeature.apk")
        if not all(marker in installed_paths.stdout for marker in required_splits):
            raise RuntimeError("installed-split-set-incomplete:" + installed_paths.stdout.strip())
        receipt["cases"].append({"label": "installed-split-set", "paths": installed_paths.stdout.splitlines(),
                                 "pass": True})

        for read_only, expected in ((True, "P1_13_DCL_READONLY_PASS"),
                                    (False, "P1_13_DCL_WRITABLE_REJECTED")):
            label = "dcl-" + ("readonly" if read_only else "writable")
            receipt["cases"].append({"label": label, "result": require_status(
                invoke(adb, args.serial, args.run_id, label, "p1-13-dcl-probe", FIXTURE, 0,
                       read_only=read_only), expected, label), "pass": True})

        for iteration in range(1, 4):
            require(run([adb, "-s", args.serial, "shell", "logcat", "-c"]), "clear-native-log")
            require(run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", BASE_COMPONENT]),
                    "native-base-" + str(iteration))
            require(run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", FEATURE_COMPONENT]),
                    "native-feature-" + str(iteration))
            logs(adb, args.serial, "baseConfigMarker=base-en-config", "native-" + str(iteration), output)
            for user in (0, 1):
                prefix = f"round-{iteration}-user-{user}"
                imported = require_status(invoke(adb, args.serial, args.run_id, prefix + "-import",
                    "import-only", SPLIT, user), "IMPORTED", prefix + "-import")
                base = require_status(invoke(adb, args.serial, args.run_id, prefix + "-base",
                    "launch-component", SPLIT, user,
                    "com.warden.controlledsandbox.fixture.split.SplitBaseActivity"), "LAUNCH_PASS", prefix + "-base")
                feature = require_status(invoke(adb, args.serial, args.run_id, prefix + "-feature",
                    "launch-component", SPLIT, user,
                    "com.warden.controlledsandbox.fixture.split.feature.FeatureActivity"), "LAUNCH_PASS", prefix + "-feature")
                logs(adb, args.serial, "baseConfigMarker=base-en-config", prefix, output)
                cleared = require_status(invoke(adb, args.serial, args.run_id, prefix + "-clear",
                    "clear", SPLIT, user), "CLEARED", prefix + "-clear")
                deleted = require_status(invoke(adb, args.serial, args.run_id, prefix + "-delete",
                    "delete", SPLIT, user), "DELETED", prefix + "-delete")
                receipt["cases"].append({"label": prefix, "import": imported, "base": base,
                    "feature": feature, "clear": cleared, "delete": deleted, "pass": True})
        receipt["result"] = "PASS"
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        receipt["result"] = "FAIL"
        receipt["error"] = type(error).__name__ + ":" + str(error)
    receipt["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    (output / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(output), "result": receipt["result"]}))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
