"""Run the P1-05 native/CAS Service owner and absence-return fixture once on one AVD.

Use one invocation per API level. The runner records its first outcome and does not retry a
failed coordinate; callers retain the run directory as the acceptance evidence.
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
FIXTURE_COMPONENT = FIXTURE + "/.P105ServiceSemanticsProbeActivity"
MARKER = "P1_05_SERVICE_SEMANTICS_PASS"
BASE_REQUIRED_MARKERS = (
    "P1_05_VIRTUAL_PM_PASS",
    "P1_05_MISSING_RETURNS_PASS bind=false start=null stop=false",
    "P1_05_ON_CONNECTED",
    "P1_05_ON_NULL_BINDING",
    MARKER,
)
CAS_REQUIRED_MARKERS = BASE_REQUIRED_MARKERS + (
    "P1_05_PEER_DENIED_PASS nonExported=security permission=security",
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
    for name, args in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        value = run([adb, "-s", serial, *args])
        require(value, f"metadata {name}")
        result[name] = value["stdout"].strip()
    return result


def logcat(adb: str, serial: str, output: Path) -> str:
    value = run([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                 "CS_P1_05_FIXTURE:I", "CS_GUEST_SERVICE_ABSENT:I", "CS_GUEST_RESOLVE_FAIL:E",
                 "CS_SERVICE_FRAMEWORK:I", "CS_HOST_PMS:I", "AndroidRuntime:E"])
    require(value, "read logcat")
    output.write_text(value["stdout"], encoding="utf-8")
    return value["stdout"]


def wait_marker(adb: str, serial: str, directory: Path, label: str) -> str:
    deadline = time.monotonic() + 16.0
    latest = ""
    while time.monotonic() < deadline:
        latest = logcat(adb, serial, directory / f"{label}-logcat.txt")
        if MARKER in latest:
            return latest
        time.sleep(0.25)
    raise RuntimeError(f"FIXTURE_MARKER_TIMEOUT:{label}")


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(OUT))
    args = parser.parse_args()
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-05-service-%Y%m%dT%H%M%SZ")
    directory = Path(args.output_root).resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    adb = adb_path()
    host_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    fixture_apk = ROOT / "fixture-basic" / "build" / "outputs" / "apk" / "debug" / "fixture-basic-debug.apk"
    peer_apk = ROOT / "fixture-compat32" / "build" / "outputs" / "apk" / "debug" / "fixture-compat32-debug.apk"
    payload: dict[str, Any] = {"task_id": "P1-05", "run_id": run_id,
        "started_at": now(), "attempt": 1, "diagnostic_retry": False,
        "device_metadata": metadata(adb, args.serial),
        "expected_markers": {"native": list(BASE_REQUIRED_MARKERS),
                             "cas": list(CAS_REQUIRED_MARKERS)},
        "apk_sha256": {"host": sha256(host_apk), "fixture": sha256(fixture_apk),
                       "peer_fixture32": sha256(peer_apk)}}
    try:
        for apk in (host_apk, peer_apk, fixture_apk):
            require(run([adb, "-s", args.serial, "install", "-r", str(apk)], 90.0),
                    f"install {apk.name}")
        require(run([adb, "-s", args.serial, "shell", "am", "force-stop", FIXTURE]),
                "force-stop native fixture")
        require(run([adb, "-s", args.serial, "shell", "logcat", "-c"]), "clear native logcat")
        payload["native_start"] = run([adb, "-s", args.serial, "shell", "am", "start", "-W",
                                        "-n", FIXTURE_COMPONENT])
        require(payload["native_start"], "native fixture start")
        native_log = wait_marker(adb, args.serial, directory, "native")
        payload["native_markers"] = {marker: marker in native_log for marker in BASE_REQUIRED_MARKERS}
        require(run([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                "force-stop host before import")
        require(run([adb, "-s", args.serial, "shell", "run-as", HOST, "rm", "-f",
                     "files/debug-command-result.json"]), "clear import result")
        peer_import_id = run_id + "-peer-import"
        require(run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                     "--es", "command", "import-only", "--es", "package",
                     "com.warden.controlledsandbox.fixture32", "--ei", "user", "0", "--es",
                     "requestId", peer_import_id, "--ez", "trustNativeGuest", "true"], 45.0),
                "CAS peer import")
        payload["peer_import"] = wait_debug(adb, args.serial, peer_import_id)
        if (payload["peer_import"].get("status") != "PASS"
                or payload["peer_import"].get("operation", {}).get("status") != "IMPORTED"):
            raise RuntimeError("CAS_PEER_IMPORT_DID_NOT_PASS")
        import_id = run_id + "-import"
        require(run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n", HOST_COMPONENT,
                     "--es", "command", "import-only", "--es", "package", FIXTURE,
                     "--ei", "user", "0", "--es", "requestId", import_id,
                     "--ez", "trustNativeGuest", "true"], 45.0), "CAS import")
        payload["import"] = wait_debug(adb, args.serial, import_id)
        if payload["import"].get("status") != "PASS" or payload["import"].get("operation", {}).get("status") != "IMPORTED":
            raise RuntimeError("CAS_IMPORT_DID_NOT_PASS")
        require(run([adb, "-s", args.serial, "shell", "am", "force-stop", HOST]),
                "force-stop host before CAS fixture")
        require(run([adb, "-s", args.serial, "shell", "run-as", HOST, "rm", "-f",
                     "files/debug-command-result.json"]), "clear CAS result")
        require(run([adb, "-s", args.serial, "shell", "logcat", "-c"]), "clear CAS logcat")
        cas_id = run_id + "-cas"
        payload["cas_start"] = run([adb, "-s", args.serial, "shell", "am", "start", "-W", "-n",
                                     HOST_COMPONENT, "--es", "command", "launch-component", "--es",
                                     "package", FIXTURE, "--es", "component",
                                     FIXTURE + ".P105ServiceSemanticsProbeActivity", "--ei", "user", "0",
                                     "--es", "requestId", cas_id, "--ez", "trustNativeGuest", "true",
                                     "--ez", "p105CasMode", "true"], 45.0)
        require(payload["cas_start"], "CAS fixture start")
        payload["cas"] = wait_debug(adb, args.serial, cas_id)
        cas_log = wait_marker(adb, args.serial, directory, "cas")
        payload["cas_markers"] = {marker: marker in cas_log for marker in CAS_REQUIRED_MARKERS}
        payload["result"] = "PASS" if (all(payload["native_markers"].values())
                and payload["cas"].get("status") == "PASS"
                and payload["cas"].get("operation", {}).get("status") == "LAUNCH_PASS"
                and all(payload["cas_markers"].values())) else "FAIL"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        payload["result"] = "FAIL"
        payload["error"] = f"{error.__class__.__name__}: {error}"
    payload["finished_at"] = now()
    (directory / "run.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(directory), "result": payload["result"]}))
    return 0 if payload["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
