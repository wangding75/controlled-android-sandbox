"""Run the P1-02 fixture contract as directly installed native Android apps.

This is intentionally outside CAS: it establishes the platform outcome that later
virtual-runtime tests must compare against.  It records absence outcomes rather
than converting them into success values.
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
PACKAGE_PROVIDER = "com.warden.controlledsandbox.fixture.libraryprovider"
PACKAGE_CONSUMER = "com.warden.controlledsandbox.fixture.libraryconsumer"
PACKAGE_PEER = "com.warden.controlledsandbox.fixture32"
MARKERS = (
    "PROVIDER_PACKAGE_LAUNCH_PASS class=P1_PROVIDER_CLASS_OK",
    "REENTRANT_PROVIDER_CREATE pid=",
    "REENTRANT_PROVIDER_PASS pid=",
    "NATIVE_PROJECTION_PASS class=OK resource=OK asset=OK missingService=NULL",
    "REMOTE_SERVICE_CREATE pid=",
    "REMOTE_SERVICE_START id=",
)


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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_success(result: dict[str, Any], label: str) -> None:
    if result["returncode"] != 0:
        raise RuntimeError(f"{label} failed: {result['stderr'] or result['stdout']}")


def _device_metadata(adb: str, serial: str) -> dict[str, str]:
    commands = {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }
    metadata: dict[str, str] = {"serial": serial}
    for field, suffix in commands.items():
        result = _run([adb, "-s", serial, *suffix])
        _require_success(result, f"device metadata {field}")
        metadata[field] = result["stdout"].strip()
    return metadata


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Current dynamically verified ADB serial")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    return parser


def run(args: argparse.Namespace) -> tuple[int, Path, dict[str, Any]]:
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-02-native-%Y%m%dT%H%M%SZ")
    run_dir = Path(args.output_root).resolve() / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    adb = _adb_path()
    apks = {
        "provider": ROOT / "fixture-library-provider" / "build" / "outputs" / "apk" / "debug"
        / "fixture-library-provider-debug.apk",
        "consumer": ROOT / "fixture-library-consumer" / "build" / "outputs" / "apk" / "debug"
        / "fixture-library-consumer-debug.apk",
        "peer": ROOT / "fixture-compat32" / "build" / "outputs" / "apk" / "debug"
        / "fixture-compat32-debug.apk",
    }
    missing = [str(path) for path in apks.values() if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"fixture APKs are missing: {missing}")

    payload: dict[str, Any] = {
        "task_id": "P1-02",
        "run_id": run_id,
        "started_at": _now(),
        "device_metadata": _device_metadata(adb, args.serial),
        "mode": "NATIVE_DIRECT_INSTALL",
        "apk_sha256": {name: _sha256(path) for name, path in apks.items()},
        "commands": [],
        "expected_markers": list(MARKERS),
    }
    try:
        clear = _run([adb, "-s", args.serial, "logcat", "-c"])
        _require_success(clear, "logcat clear")
        payload["commands"].append(clear)
        for name in ("provider", "peer", "consumer"):
            install = _run([adb, "-s", args.serial, "install", "-r", str(apks[name])])
            _require_success(install, f"install {name}")
            payload["commands"].append(install)
        for package, component in (
            (PACKAGE_PROVIDER, ".ProviderProbeActivity"),
            (PACKAGE_CONSUMER, ".ProjectionConsumerActivity"),
        ):
            launch = _run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n",
                           f"{package}/{component}"])
            _require_success(launch, f"launch {package}")
            payload["commands"].append(launch)
        time.sleep(2)
        logcat = _run([adb, "-s", args.serial, "logcat", "-d", "-v", "brief", "-s",
                       "CS_P1_02_FIXTURE:I"])
        _require_success(logcat, "fixture logcat")
        (run_dir / "logcat.txt").write_text(logcat["stdout"], encoding="utf-8")
        observed = [marker for marker in MARKERS if marker in logcat["stdout"]]
        missing_markers = [marker for marker in MARKERS if marker not in observed]
        payload["observed_markers"] = observed
        payload["missing_markers"] = missing_markers
        payload["result"] = "PASS" if not missing_markers else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        payload["result"] = "BLOCKED" if isinstance(error, OSError) else "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = _now()
    (run_dir / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return (0 if payload["result"] == "PASS" else 2), run_dir, payload


def main() -> int:
    exit_code, run_dir, payload = run(_parser().parse_args())
    print(json.dumps({"run_dir": str(run_dir), "result": payload["result"],
                      "missing_markers": payload.get("missing_markers", [])}))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
