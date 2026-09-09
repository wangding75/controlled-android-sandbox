"""Run P1-11's bounded process-owner and recovery checks on one AVD.

Every injected fault is a separate first attempt.  Recovery is recorded independently and is
never used to rewrite the fault result.  The runner intentionally excludes LMK/soak work.
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
BROKER_PROCESS = HOST + ":sandbox_server"
# GuestRecoveryPrewarmCoordinator schedules a recovery no sooner than 1_500 ms after the
# death recipient marks the session RECOVERING.  Keep the observation window explicit so an
# immediate client call cannot mistake a not-yet-delivered Binder death for a reused generation.
RECOVERY_COORDINATOR_WINDOW_SECONDS = 2.2


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
           extras: dict[str, str | int | bool] | None = None) -> dict:
    request_id = run_id + "-" + label
    require(run([adb, "-s", serial, "shell", "run-as", HOST, "rm", "-f",
                 "files/debug-command-result.json"]), "clear-result-" + label)
    args = [adb, "-s", serial, "shell", "am", "start", "-W", "-n", COMPONENT,
            "--es", "command", command, "--es", "package", FIXTURE,
            "--ei", "user", "0", "--es", "requestId", request_id,
            "--ez", "trustNativeGuest", "true"]
    for key, value in (extras or {}).items():
        if isinstance(value, bool):
            args.extend(["--ez", key, "true" if value else "false"])
        elif isinstance(value, int):
            args.extend(["--ei", key, str(value)])
        else:
            args.extend(["--es", key, value])
    require(run(args), "start-" + label)
    deadline = time.monotonic() + 90.0
    while time.monotonic() < deadline:
        output = run([adb, "-s", serial, "shell", "run-as", HOST, "cat",
                      "files/debug-command-result.json"])
        if output.returncode == 0:
            try:
                result = json.loads(output.stdout)
            except json.JSONDecodeError:
                result = None
            if isinstance(result, dict) and result.get("requestId") == request_id:
                return result
        time.sleep(0.2)
    raise RuntimeError("result-timeout:" + label)


def logcat(adb: str, serial: str) -> str:
    result = run([adb, "-s", serial, "logcat", "-d", "-v", "brief"])
    require(result, "logcat")
    # A freshly rooted API-36 emulator can transiently return no stdout while logd reconnects.
    # Treat that as an empty sample and let wait_log poll, rather than treating it as evidence.
    return result.stdout or ""


def wait_log(adb: str, serial: str, markers: tuple[str, ...], label: str) -> str:
    deadline = time.monotonic() + 25.0
    while time.monotonic() < deadline:
        value = logcat(adb, serial)
        if all(marker in value for marker in markers):
            return value
        time.sleep(0.25)
    raise RuntimeError("missing-log-" + label + ":" + repr(markers))


def operation(result: dict) -> dict:
    value = result.get("operation")
    return value if isinstance(value, dict) else {}


def pid_of(adb: str, serial: str, process: str) -> str:
    value = run([adb, "-s", serial, "shell", "pidof", process])
    return value.stdout.strip() if value.returncode == 0 else ""


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


def configure_unattended_java_crashes(adb: str, serial: str) -> dict:
    # A headless AVD otherwise holds a Java-crashed process in AppErrorDialog, preventing the
    # ServiceConnection death callback that this task is exercising.  This is test-harness
    # configuration only; it is captured in the receipt and never shipped in the APK.
    require(run([adb, "-s", serial, "shell", "settings", "put", "global",
                 "show_first_crash_dialog", "0"]), "disable-java-crash-dialog")
    value = run([adb, "-s", serial, "shell", "settings", "get", "global",
                 "show_first_crash_dialog"])
    require(value, "read-java-crash-dialog")
    if value.stdout.strip() != "0":
        raise RuntimeError("java-crash-dialog-still-enabled:" + value.stdout.strip())
    return {"show_first_crash_dialog": "0"}


def enable_exact_broker_death_injection(adb: str, serial: str) -> dict:
    # The Broker runs under the app UID, so a regular adb shell cannot signal its exact PID.
    # P1-11 uses only userdebug AVDs: restart adbd as root and prove that capability in the
    # receipt before the Broker-only SIGKILL case.  This is not used for the later Xiaomi stage.
    require(run([adb, "-s", serial, "root"]), "adb-root-for-broker-death")
    require(run([adb, "-s", serial, "wait-for-device"]), "wait-rooted-adbd")
    identity = run([adb, "-s", serial, "shell", "id"])
    require(identity, "read-rooted-adbd-identity")
    if not identity.stdout.startswith("uid=0(root)"):
        raise RuntimeError("broker-death-needs-rooted-adbd:" + identity.stdout.strip())
    return {"adbdIdentity": identity.stdout.strip()}


def apk_metadata() -> dict:
    values = {}
    for label, path in {
        "host": ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        "fixture": ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk",
    }.items():
        values[label] = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    return values


def require_pass(result: dict, label: str) -> dict:
    if result.get("status") != "PASS":
        raise RuntimeError(label + ":" + json.dumps(result, ensure_ascii=False))
    return operation(result)


def run_guest_fault(adb: str, serial: str, run_id: str, kind: str, mode: str,
                    process: str, marker: str, iteration: int, output: Path) -> dict:
    label = f"{kind}-{iteration}"
    require(run([adb, "-s", serial, "shell", "logcat", "-c"]), "clear-log-" + label)
    first = invoke(adb, serial, run_id, label + "-fault", "fault-probe", {"mode": mode})
    require_pass(first, label + "-fault-command")
    log = wait_log(adb, serial, (marker, "GUEST_PROCESS_DISCONNECTED"), label)
    (output / (label + ".logcat.txt")).write_text(log, encoding="utf-8")
    # launchComponent returns an Activity-route Bundle that can retain the initiating process;
    # fault-probe publishes the target owner separately before dispatching the component.
    before = {
        "sessionId": None,
        "generation": first.get("generationBefore"),
        "processSlot": first.get("slotBefore"),
        "processName": first.get("faultProcess", process),
    }
    time.sleep(RECOVERY_COORDINATOR_WINDOW_SECONDS)
    recovery = invoke(adb, serial, run_id, label + "-recover", "p1-11-explicit-recover",
                      {"processName": process})
    after = require_pass(recovery, label + "-recover")
    after_values = {key: after.get(key) for key in ("sessionId", "generation", "processSlot", "processName")}
    if before["generation"] is not None and after_values["generation"] is not None:
        if int(after_values["generation"]) <= int(before["generation"]):
            raise RuntimeError(label + ":generation-not-advanced:" + repr((before, after_values)))
    return {"kind": kind, "iteration": iteration, "first_failure": first,
            "before": before, "recoveryCoordinatorWindowSeconds": RECOVERY_COORDINATOR_WINDOW_SECONDS,
            "explicit_recovery": recovery, "after": after_values,
            "pass": True}


def run_broker_death(adb: str, serial: str, run_id: str, iteration: int) -> dict:
    label = f"broker-death-{iteration}"
    ready = invoke(adb, serial, run_id, label + "-ready", "runtime-package-ready")
    require_pass(ready, label + "-ready")
    before_pid = pid_of(adb, serial, BROKER_PROCESS)
    if not before_pid:
        raise RuntimeError(label + ":broker-pid-missing")
    require(run([adb, "-s", serial, "shell", "kill", "-9", before_pid]), label + "-kill")
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline and pid_of(adb, serial, BROKER_PROCESS) == before_pid:
        time.sleep(.1)
    if pid_of(adb, serial, BROKER_PROCESS) == before_pid:
        raise RuntimeError(label + ":broker-pid-still-live")
    recovery = invoke(adb, serial, run_id, label + "-recover", "runtime-package-ready")
    require_pass(recovery, label + "-recover")
    after_pid = pid_of(adb, serial, BROKER_PROCESS)
    if not after_pid or after_pid == before_pid:
        raise RuntimeError(label + ":broker-not-recreated:" + after_pid)
    return {"kind": "broker-death", "iteration": iteration, "first_failure": {
            "brokerPid": before_pid, "signal": "SIGKILL"}, "explicit_recovery": recovery,
            "after": {"brokerPid": after_pid}, "pass": True}


def reset_broker_for_high_slot(adb: str, serial: str, run_id: str, slot: int) -> dict:
    # SLOT_TARGET intentionally occupies every other ordinary slot.  It must run against a
    # clean SessionRegistry, not the regular fault matrix's surviving normal-process lease.
    before_pid = pid_of(adb, serial, BROKER_PROCESS)
    if before_pid:
        require(run([adb, "-s", serial, "shell", "kill", "-9", before_pid]),
                "high-slot-reset-kill-" + str(slot))
    recovery = invoke(adb, serial, run_id, "high-slot-reset-" + str(slot),
                      "runtime-package-ready")
    require_pass(recovery, "high-slot-reset-" + str(slot))
    after_pid = pid_of(adb, serial, BROKER_PROCESS)
    if not after_pid or (before_pid and after_pid == before_pid):
        raise RuntimeError("high-slot-reset-not-recreated-" + str(slot) + ":" + after_pid)
    return {"beforeBrokerPid": before_pid, "afterBrokerPid": after_pid,
            "explicitRecovery": recovery}


def run_prepare_failure(adb: str, serial: str, run_id: str, iteration: int) -> dict:
    label = f"prepare-failure-{iteration}"
    first = invoke(adb, serial, run_id, label, "p1-11-prepare-failure")
    failed = operation(first)
    if first.get("status") != "PASS" or failed.get("status") != "FAILED":
        raise RuntimeError(label + ":expected-structured-failure:" + json.dumps(first))
    # A separate normal process must remain usable; this proves the failed PREPARING lease did
    # not leave a global busy state.  The dedicated fixture intentionally cannot itself recover.
    recovery = invoke(adb, serial, run_id, label + "-normal-recover", "p1-11-explicit-recover",
                      {"processName": FIXTURE})
    require_pass(recovery, label + "-normal-recover")
    return {"kind": "prepare-failure", "iteration": iteration, "first_failure": first,
            "explicit_recovery": recovery, "pass": True}


def run_late_connection(adb: str, serial: str, run_id: str, iteration: int) -> dict:
    label = f"late-connection-{iteration}"
    result = invoke(adb, serial, run_id, label, "p1-11-late-connection")
    op = require_pass(result, label)
    if op.get("status") != "P1_11_LATE_CONNECTION_PASS":
        raise RuntimeError(label + ":unexpected-status:" + json.dumps(op))
    return {"kind": "late-connection", "iteration": iteration,
            "first_failure": {"reason": "BIND_TIMEOUT"}, "explicit_recovery": result,
            "pass": True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    adb = adb_path()
    out = ROOT / "out" / "verification" / args.run_id
    out.mkdir(parents=True, exist_ok=False)
    broker_death_injection = enable_exact_broker_death_injection(adb, args.serial)
    receipt: dict = {"task_id": "P1-11", "run_id": args.run_id, "attempt": 1,
                     "automatic_retry_performed": False, "device": device_metadata(adb, args.serial),
                     "apks": apk_metadata(), "cases": [],
                     "brokerDeathInjection": broker_death_injection}
    try:
        receipt["harnessConfiguration"] = configure_unattended_java_crashes(adb, args.serial)
        for apk in (ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                    ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"):
            require(run([adb, "-s", args.serial, "install", "-r", str(apk)], 180.0),
                    "install-" + apk.name)
        require_pass(invoke(adb, args.serial, args.run_id, "ready", "runtime-package-ready"), "ready")
        require_pass(invoke(adb, args.serial, args.run_id, "import", "import-only"), "import")
        for iteration in range(1, 4):
            receipt["cases"].append(run_guest_fault(adb, args.serial, args.run_id, "java-crash",
                "java", FIXTURE + ":p111_java_crash", "P1_11_JAVA_CRASH_THREAD_BEGIN", iteration, out))
            receipt["cases"].append(run_guest_fault(adb, args.serial, args.run_id, "native-abort",
                "native-abort", FIXTURE + ":fault_abort_svc", "P1_11_NATIVE_ABORT_SERVICE_BEGIN", iteration, out))
            receipt["cases"].append(run_broker_death(adb, args.serial, args.run_id, iteration))
            receipt["cases"].append(run_prepare_failure(adb, args.serial, args.run_id, iteration))
            receipt["cases"].append(run_late_connection(adb, args.serial, args.run_id, iteration))
        for slot in (62, 63):
            reset = reset_broker_for_high_slot(adb, args.serial, args.run_id, slot)
            high = invoke(adb, args.serial, args.run_id, "high-slot-" + str(slot), "slot-campaign",
                          {"slotTarget": slot, "processName": FIXTURE + ":p111_slot_" + str(slot)})
            receipt["cases"].append({"kind": "high-slot", "slot": slot, "result": high,
                                      "cleanBrokerReset": reset,
                                      "pass": require_pass(high, "high-slot-" + str(slot)).get("status")
                                      in {"PREPARED", "ALREADY_PREPARED", "PREPARED_DEGRADED",
                                          "ALREADY_PREPARED_DEGRADED"}})
        receipt["result"] = "PASS"
    except Exception as error:
        receipt["result"] = "FAIL"
        receipt["error"] = type(error).__name__ + ":" + str(error)
    receipt["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    (out / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(out), "result": receipt["result"]}, ensure_ascii=False))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
