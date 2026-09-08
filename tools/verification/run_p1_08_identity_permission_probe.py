"""Run P1-08's narrow two-virtual-user permission/AppOps/Attribution probe."""

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
COMPONENT = FIXTURE + ".PmsPermissionAttributionProbeActivity"
PASS_MARKER = "C2_T02_PROBE_PASS"
FAIL_MARKERS = (
    "C2_T02_PROBE_FAIL", "PMS_HOST_APPLICATION_VISIBLE", "APPOPS_HOST_PACKAGE_VISIBLE",
    "ATTRIBUTION_SOURCE_IDENTITY_MISMATCH", "CALLBACK_PACKAGE_IDENTITY_MISMATCH",
    "P1_08_CAMERA_PERMISSION_EXPECTED", "P1_08_CAMERA_APPOPS_EXPECTED",
    "P1_08_INTERNET_PERMISSION_EXPECTED",
    "MANDATORY_FRAMEWORK_HOOKS_FAILED", "CS_FRAMEWORK: HOOK_FAILED",
)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise RuntimeError("ANDROID_SDK_ROOT or ANDROID_HOME is required")
    return str(Path(sdk) / "platform-tools" / "adb.exe")


def command(arguments: list[str], timeout: float = 60.0) -> dict[str, Any]:
    result = subprocess.run(arguments, text=True, capture_output=True, timeout=timeout)
    return {"command": arguments, "returncode": result.returncode,
            "stdout": result.stdout, "stderr": result.stderr}


def require(value: dict[str, Any], label: str) -> None:
    if value["returncode"] != 0:
        raise RuntimeError(f"{label}:{value['stderr'] or value['stdout']}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def metadata(adb: str, serial: str) -> dict[str, str]:
    values = {"serial": serial}
    for key, arguments in {
        "api_level": ["shell", "getprop", "ro.build.version.sdk"],
        "abi": ["shell", "getprop", "ro.product.cpu.abi"],
        "fingerprint": ["shell", "getprop", "ro.build.fingerprint"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
    }.items():
        output = command([adb, "-s", serial, *arguments])
        require(output, "metadata-" + key)
        values[key] = output["stdout"].strip()
    return values


def wait_debug(adb: str, serial: str, command_name: str, request_id: str,
               timeout_seconds: float = 90.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    last = ""
    while time.monotonic() < deadline:
        output = command([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                          "files/debug-command-result.json"])
        if output["returncode"] == 0:
            last = output["stdout"].strip()
            try:
                result = json.loads(last)
            except json.JSONDecodeError:
                result = None
            if (isinstance(result, dict)
                    and result.get("command") == command_name
                    and result.get("package") == FIXTURE
                    and result.get("requestId") == request_id):
                return result
        time.sleep(0.2)
    raise RuntimeError("DEBUG_RESULT_TIMEOUT:" + request_id + ":" + last[:300])


def dispatch(adb: str, serial: str, run_id: str, name: str, command_name: str,
             user: int, extras: dict[str, str | int | bool] | None = None,
             require_pass: bool = True, result_timeout_seconds: float = 90.0) -> dict[str, Any]:
    request_id = f"{run_id}-{name}-u{user}"
    # Every Debug Activity writes this one result file. The prior command's matched terminal
    # receipt proves its worker has completed; clear only that file before the next command.
    require(command([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                     "files/debug-command-result.json"]),
            "debug-clear-result-" + name)
    # The command writes its receipt asynchronously.  Do not use `am start -W`: that waits for
    # Activity window teardown, while the durable, request-bound receipt below is the command's
    # actual completion contract.
    arguments = [adb, "-s", serial, "shell", "am", "start", "-n", HOST_COMPONENT,
                 "--es", "command", command_name, "--es", "package", FIXTURE,
                 "--ei", "user", str(user), "--es", "requestId", request_id,
                 "--ez", "trustNativeGuest", "true"]
    for key, value in (extras or {}).items():
        if isinstance(value, bool): arguments.extend(["--ez", key, str(value).lower()])
        elif isinstance(value, int): arguments.extend(["--ei", key, str(value)])
        else: arguments.extend(["--es", key, value])
    started = command(arguments, 60.0)
    require(started, "debug-start-" + name)
    result = wait_debug(adb, serial, command_name, request_id, result_timeout_seconds)
    if require_pass and result.get("status") != "PASS":
        raise RuntimeError("DEBUG_COMMAND_NOT_PASS:" + name + ":" + json.dumps(result))
    return result


def wait_probe_log(adb: str, serial: str, output: Path, expected_permission: int,
                   expected_app_op: int, expected_internet: int) -> tuple[str, dict[str, Any]]:
    # The probe returns activity-created as soon as its worker begins.  Keep the observation
    # window local to this cold remote-Provider fixture; it is not a production timeout change.
    deadline = time.monotonic() + 90.0
    latest = ""
    while time.monotonic() < deadline:
        log = command([adb, "-s", serial, "logcat", "-d", "-v", "brief", "-s",
                       "CS_FIXTURE:I", "CS_FRAMEWORK:W", "AndroidRuntime:E"])
        require(log, "logcat")
        latest = log["stdout"]
        output.write_text(latest, encoding="utf-8")
        failures = [line for line in latest.splitlines()
                    if any(marker in line for marker in FAIL_MARKERS)]
        if failures:
            raise RuntimeError("P1_08_FIXTURE_FAIL_MARKER:" + failures[-1][-800:])
        for line in latest.splitlines():
            if PASS_MARKER not in line:
                continue
            payload_start = line.find("{")
            if payload_start < 0:
                continue
            try:
                probe = json.loads(line[payload_start:])
            except json.JSONDecodeError:
                continue
            values = [probe.get(key) for key in
                      ("appOpsCheck", "appOpsNote", "appOpsStart", "appOpsProxy")]
            callback = probe.get("callback") or {}
            attribution = probe.get("attribution") or {}
            if (probe.get("contextCamera") == expected_permission
                    and probe.get("packageCamera") == expected_permission
                    and probe.get("contextInternet") == expected_internet
                    and values == [expected_app_op] * 4
                    and probe.get("hostPackageCamera") == -1
                    and probe.get("packageName") == FIXTURE
                    and callback.get("callingPackage") == FIXTURE
                    and callback.get("callingAttributionPackage") == FIXTURE
                    and attribution.get("packageName") == FIXTURE
                    and callback.get("callingAttributionUid") == attribution.get("uid")):
                return latest, probe
        time.sleep(0.25)
    raise RuntimeError("P1_08_PROBE_MARKER_OR_IDENTITY_TIMEOUT:"
                       + str(expected_permission) + ":" + str(expected_app_op)
                       + ":" + str(expected_internet))


def ensure_hook_evidence(prepared: dict[str, Any]) -> dict[str, Any]:
    operation = prepared.get("operation") or {}
    readiness = operation.get("frameworkReadiness", "")
    ready = operation.get("frameworkPmsAppOpsPermissionHooksReady", False)
    mandatory_failed = operation.get("frameworkMandatoryHooksFailed", "")
    if not ready or mandatory_failed or readiness == "BLOCKED":
        raise RuntimeError("P1_08_HOOK_EVIDENCE_FAILED:ready=" + str(ready)
                           + ":mandatoryFailed=" + mandatory_failed
                           + ":readiness=" + readiness)
    return {"readiness": readiness, "pms_appops_permission_ready": ready,
            "mandatory_failed": mandatory_failed}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(OUT))
    arguments = parser.parse_args()
    run_id = arguments.run_id or dt.datetime.now(dt.timezone.utc).strftime("p1-08-%Y%m%dT%H%M%SZ")
    directory = Path(arguments.output_root).resolve() / run_id
    directory.mkdir(parents=True, exist_ok=False)
    adb = adb_path()
    host_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    fixture_apk = ROOT / "fixture-basic" / "build" / "outputs" / "apk" / "debug" / "fixture-basic-debug.apk"
    receipt: dict[str, Any] = {"task_id": "P1-08", "run_id": run_id, "attempt": 1,
        "diagnostic_retry": False, "started_at": now(),
        "device_metadata": metadata(adb, arguments.serial),
        "apk_sha256": {"host": sha256(host_apk), "fixture": sha256(fixture_apk)},
        "cases": []}
    try:
        for apk in (host_apk, fixture_apk):
            installed = command([adb, "-s", arguments.serial, "install", "-r", str(apk)], 90.0)
            require(installed, "install-" + apk.name)
        # Do not force-stop the Host between installation and the first mutating import.  That
        # import intentionally acquires the Package Service in one generation so it cannot replay
        # a mutation; a forced Host restart turns the Binder establishment race into a false test
        # failure.  Each case instead establishes its evidenced boundary below with import plus
        # explicit per-user policy writes and policy-state readback.
        # Host CAMERA is deliberately not granted in this test environment.  A virtual
        # GRANTED/ALLOWED request therefore must remain effective DENIED/IGNORED instead of
        # escalating Host capability.  INTERNET is Host-granted and supplies the positive
        # per-user permission projection, while the real Provider callback supplies positive
        # data/callback evidence without touching a global Host permission table.
        cases = ((0, "GRANTED", "ALLOWED", "DEFAULT", -1, 1, 0, "host-gated"),
                 (1, "DENIED", "IGNORED", "DENIED", -1, 1, -1, "denied"))
        # Updating the Host APK can publish Runtime Broker and Package Management Service on
        # distinct scheduler turns.  Establish both by a read-only lookup before the first
        # import-only mutation.  Retry only this completed, non-mutating bootstrap probe; the
        # actual import still owns one PackageServiceClient bind generation and is never replayed.
        bootstrap_attempts: list[dict[str, Any]] = []
        bootstrap = None
        for attempt in range(1, 6):
            candidate = dispatch(adb, arguments.serial, run_id,
                                 "runtime-package-ready-" + str(attempt),
                                 "runtime-package-ready", 0, require_pass=False)
            bootstrap_attempts.append(candidate)
            operation = candidate.get("operation") or {}
            if (candidate.get("status") == "PASS"
                    and operation.get("status") == "RUNTIME_PACKAGE_READY"):
                bootstrap = candidate
                break
            message = str(candidate.get("errorMessage") or "")
            if candidate.get("status") != "FAIL" or not any(token in message for token in (
                    "Runtime broker is unavailable", "Package management service is unavailable")):
                raise RuntimeError("P1_08_RUNTIME_PACKAGE_PREFLIGHT_FAILED:"
                                   + json.dumps(candidate))
        if bootstrap is None:
            raise RuntimeError("P1_08_RUNTIME_PACKAGE_PREFLIGHT_EXHAUSTED:"
                               + json.dumps(bootstrap_attempts))
        receipt["runtime_package_preflight"] = {
            "attempts": bootstrap_attempts,
            "operation": bootstrap.get("operation"),
        }
        for user, permission, app_op, internet, expected_permission, expected_app_op, expected_internet, label in cases:
            dispatch(adb, arguments.serial, run_id, "import", "import-only", user,
                     result_timeout_seconds=180.0)
            policy_permission = dispatch(adb, arguments.serial, run_id, "permission-" + label,
                                         "set-permissions", user,
                                         {"permissions": "android.permission.CAMERA",
                                          "decision": permission})
            policy_app_op = dispatch(adb, arguments.serial, run_id, "appops-" + label,
                                     "set-appops", user,
                                     {"appOps": "android:camera", "mode": app_op})
            policy_internet = dispatch(adb, arguments.serial, run_id, "internet-" + label,
                                       "set-permissions", user,
                                       {"permissions": "android.permission.INTERNET",
                                        "decision": internet})
            policy = dispatch(adb, arguments.serial, run_id, "policy-" + label,
                              "policy-state", user)
            operation = policy.get("operation") or {}
            if (operation.get("cameraPermission") != permission
                    or operation.get("internetPermission") != internet
                    or operation.get("cameraAppOp") != "IGNORED"):
                raise RuntimeError("P1_08_POLICY_STATE_MISMATCH:" + label + ":"
                                   + json.dumps(operation))
            prepared = dispatch(adb, arguments.serial, run_id, "prepare-" + label,
                                "prepare", user)
            hooks = ensure_hook_evidence(prepared)
            require(command([adb, "-s", arguments.serial, "shell", "logcat", "-c"]),
                    "clear-logcat-" + label)
            launched = dispatch(adb, arguments.serial, run_id, "probe-" + label,
                                "launch-component", user,
                                {"component": COMPONENT,
                                 "prewarmProviderComponent": FIXTURE + ".FixtureProvider",
                                 "prewarmProviderProcess": FIXTURE + ":provider",
                                 "prewarmProviderAuthority": FIXTURE + ".provider",
                                 "p108ExpectedCameraPermission": expected_permission,
                                 "p108ExpectedCameraAppOp": expected_app_op,
                                 "p108ExpectedInternetPermission": expected_internet},
                                result_timeout_seconds=180.0)
            if (launched.get("operation") or {}).get("status") != "LAUNCH_PASS":
                raise RuntimeError("P1_08_LAUNCH_NOT_PASS:" + label + ":"
                                   + json.dumps(launched.get("operation") or {}))
            log, probe = wait_probe_log(adb, arguments.serial,
                                        directory / f"{label}-logcat.txt",
                                        expected_permission, expected_app_op, expected_internet)
            failures = [marker for marker in FAIL_MARKERS if marker in log]
            if failures:
                raise RuntimeError("P1_08_FORBIDDEN_MARKER:" + ",".join(failures))
            receipt["cases"].append({"virtual_user": user, "label": label,
                "expected": {"cameraPermission": expected_permission,
                             "cameraAppOp": expected_app_op},
                "policy_permission": policy_permission.get("operation"),
                "policy_app_op": policy_app_op.get("operation"),
                "policy_internet": policy_internet.get("operation"),
                "policy_state": operation, "hook_evidence": hooks,
                "provider_prewarm": launched.get("providerPrewarm"),
                "launch": launched.get("operation"), "probe": probe})
        virtual_uids = [case["probe"].get("attribution", {}).get("uid")
                        for case in receipt["cases"]]
        if len(set(virtual_uids)) != 2:
            raise RuntimeError("P1_08_VIRTUAL_USER_UID_NOT_ISOLATED:" + json.dumps(virtual_uids))
        receipt["result"] = "PASS"
    except (OSError, RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        receipt["result"] = "FAIL"
        receipt["error"] = error.__class__.__name__ + ": " + str(error)
    receipt["finished_at"] = now()
    (directory / "run.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n",
                                           encoding="utf-8")
    print(json.dumps({"run_dir": str(directory), "result": receipt["result"]}))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
