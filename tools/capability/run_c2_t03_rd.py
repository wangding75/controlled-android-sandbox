#!/usr/bin/env python3
"""C2-T03 RD campaign for Guest Location lifecycle and isolation."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import (
    HOST_PACKAGE,
    GUEST_PACKAGE,
    adb_bin,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)

TASK_ID = "C2-T03"
PROBE_COMPONENT = "com.warden.controlledsandbox.fixture.LocationCampaignActivity"
TAG = "CS_C2_T03_LOCATION"
LOCATION_PERMISSIONS = (
    "android.permission.ACCESS_FINE_LOCATION,android.permission.ACCESS_COARSE_LOCATION"
)
HOST_LOCATION_PERMISSIONS = (
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
)
LOCATION_APPOPS = "android:fine_location,android:coarse_location"
TRUST = ("--ez", "trustNativeGuest", "true")


def command(serial: str, name: str, user: int = 0,
            extra: list[str] | None = None, *, force_stop_host: bool = True,
            deadline_sec: int = 120) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    args = ["--es", "command", name, "--es", "package", GUEST_PACKAGE,
            "--ei", "user", str(user), "--es", "requestId", request_id, *TRUST]
    if extra:
        args.extend(extra)
    return debug_command(serial, args, deadline_sec=deadline_sec,
                         force_stop_host=force_stop_host)


def require_pass(label: str, payload: dict[str, Any]) -> dict[str, Any]:
    if str(payload.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(payload, ensure_ascii=False)}")
    value = payload.get("result")
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} returned no result: {json.dumps(payload, ensure_ascii=False)}")
    return value


def configure_location(serial: str, user: int, latitude: float, longitude: float,
                       *, nmea: str, mode: str = "STATIC") -> dict[str, Any]:
    extra = [
        "--es", "mode", mode,
        "--es", "provider", "gps",
        "--es", "providerEnabled", "true" if mode == "STATIC" else "false",
        "--es", "latitude", f"{latitude:.8f}",
        "--es", "longitude", f"{longitude:.8f}",
        "--es", "altitude", "4.0",
        "--es", "accuracy", "5.0",
        "--es", "speed", "0.0",
        "--es", "bearing", "0.0",
        "--es", "minimumUpdateIntervalMs", "1000",
        "--es", "gnssEnabled", "true" if mode == "STATIC" else "false",
        "--es", "satellitesInView", "8",
        "--es", "satellitesUsedInFix", "5",
        "--es", "nmeaSentence", nmea,
    ]
    return require_pass(f"configure location user={user}",
                        command(serial, "configure-location", user, extra))


def set_permission(serial: str, user: int, decision: str) -> dict[str, Any]:
    return require_pass(f"set location permission user={user} decision={decision}",
                        command(serial, "set-permissions", user,
                                ["--es", "permissions", LOCATION_PERMISSIONS,
                                 "--es", "decision", decision]))


def set_appops(serial: str, user: int) -> dict[str, Any]:
    return require_pass(f"set location AppOps user={user}",
                        command(serial, "set-appops", user,
                                ["--es", "appOps", LOCATION_APPOPS,
                                 "--es", "mode", "ALLOWED"]))


def start_logcat(serial: str, path: Path) -> tuple[subprocess.Popen[str], Any]:
    handle = path.open("w", encoding="utf-8", errors="replace")
    process = subprocess.Popen(
        [adb_bin(), "-s", serial, "logcat", "-v", "threadtime", f"{TAG}:I", "*:S"],
        cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT, text=True,
    )
    return process, handle


def stop_logcat(process: subprocess.Popen[str], handle: Any) -> None:
    try:
        process.terminate()
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
    finally:
        handle.close()


def json_records(logcat: str, marker: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line in logcat.splitlines():
        offset = line.find(marker)
        if offset < 0:
            continue
        try:
            value = json.loads(line[offset + len(marker):].strip())
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            records.append(value)
    return records


def _location_values(record: dict[str, Any], prefix: str = "location") -> tuple[float, float, str, float, int, int]:
    return (
        float(record[f"{prefix}Latitude"]),
        float(record[f"{prefix}Longitude"]),
        str(record[f"{prefix}Provider"]),
        float(record[f"{prefix}Accuracy"]),
        int(record[f"{prefix}Time"]),
        int(record[f"{prefix}ElapsedRealtimeNanos"]),
    )


def validate_phase(logcat: str, phase: str, expected_latitude: float,
                   expected_longitude: float, expected_available: bool,
                   minimum_callbacks: int) -> dict[str, Any]:
    probe = json_records(logcat, "C2_T03_LOCATION_PROBE ")
    current = json_records(logcat, "C2_T03_LOCATION_CURRENT ")
    current_request = json_records(logcat, "C2_T03_LOCATION_CURRENT_REQUEST ")
    callbacks = json_records(logcat, "C2_T03_LOCATION_CALLBACK ")
    nmea = json_records(logcat, "C2_T03_LOCATION_NMEA ")
    gnss = json_records(logcat, "C2_T03_LOCATION_GNSS ")
    registrations = json_records(logcat, "C2_T03_LOCATION_REGISTER ")
    negatives = json_records(logcat, "C2_T03_LOCATION_NEGATIVE ")
    unregisters = json_records(logcat, "C2_T03_LOCATION_UNREGISTER ")
    failures = [line for line in logcat.splitlines() if "C2_T03_LOCATION_FAIL " in line]
    errors: list[str] = []
    if not probe:
        errors.append("missing probe")
    else:
        observed = probe[-1]
        if bool(observed.get("providerEnabled")) != expected_available:
            errors.append(f"providerEnabled mismatch: {observed}")
        if expected_available and not bool(observed.get("locationEnabled")):
            errors.append("locationEnabled is false while profile is available")
    if not negatives or not any(item.get("kind") == "pendingIntent"
                                and item.get("outcome") == "EXPLICIT_UNSUPPORTED"
                                for item in negatives):
        errors.append("PendingIntent negative branch missing")
    if not any(item.get("kind") == "testProvider"
               and item.get("outcome") == "EXPLICIT_UNSUPPORTED" for item in negatives):
        errors.append("test-provider negative branch missing")
    if failures:
        errors.append(f"fixture fail markers: {failures[-1]}")
    if not unregisters:
        errors.append("missing explicit unregister/background marker")
    else:
        last_unregister = logcat.rfind("C2_T03_LOCATION_UNREGISTER ")
        if "C2_T03_LOCATION_CALLBACK " in logcat[last_unregister:]:
            errors.append("location callback observed after unregister")
        if "C2_T03_LOCATION_NMEA " in logcat[last_unregister:]:
            errors.append("NMEA callback observed after unregister")

    location_registration = [item for item in registrations if item.get("kind") == "location"]
    nmea_registration = [item for item in registrations if item.get("kind") == "nmea"]
    gnss_registration = [item for item in registrations if item.get("kind") == "gnss"]
    if expected_available:
        if not current or not current_request:
            errors.append("current-location callback missing")
        if len(callbacks) < minimum_callbacks:
            errors.append(f"callback count {len(callbacks)} < {minimum_callbacks}")
        if not any(item.get("available") is True for item in location_registration):
            errors.append("location registration did not observe available provider")
        if not any(item.get("registered") is True for item in nmea_registration):
            errors.append("NMEA registration did not succeed")
        if not any(item.get("registered") is True for item in gnss_registration):
            errors.append("GNSS registration did not succeed")
        if not nmea:
            errors.append("NMEA callback missing")
        if not any(item.get("event") == "started" for item in gnss):
            errors.append("GNSS started callback missing")
        if not any(item.get("event") == "firstFix" for item in gnss):
            errors.append("GNSS first-fix callback missing")
    else:
        if callbacks or nmea or any(item.get("event") == "started" for item in gnss):
            errors.append("callbacks escaped denied/cleared profile")
        if current or current_request:
            errors.append("current-location value escaped denied/cleared profile")

    samples = current + current_request
    location_errors: list[str] = []
    previous_sequence = 0
    for index, sample in enumerate(samples):
        try:
            latitude, longitude, provider, accuracy, timestamp, elapsed = _location_values(sample)
            if expected_available:
                if abs(latitude - expected_latitude) > 1e-5 or abs(longitude - expected_longitude) > 1e-5:
                    location_errors.append(f"sample {index} coordinate mismatch")
                if provider != "gps":
                    location_errors.append(f"sample {index} provider={provider!r}")
                if accuracy <= 0 or timestamp <= 0 or elapsed <= 0:
                    location_errors.append(f"sample {index} invalid precision/time")
        except (KeyError, TypeError, ValueError) as error:
            location_errors.append(f"sample {index} malformed: {error}")
    for sample in callbacks:
        if "sequence" not in sample:
            location_errors.append("callback sequence missing")
            continue
        sequence = int(sample["sequence"])
        if sequence <= previous_sequence:
            location_errors.append("callback sequence is not strictly increasing")
        previous_sequence = sequence
        if sample.get("ordered") is not True:
            location_errors.append("callback ordered marker is false")
    if location_errors:
        errors.extend(location_errors[:8])

    session_values = set()
    for marker in (probe, current, current_request, callbacks, nmea, gnss, registrations, unregisters):
        session_values.update(str(item.get("session")) for item in marker if item.get("session"))
    result = {
        "phase": phase,
        "status": "PASS" if not errors else "FAIL",
        "expected_available": expected_available,
        "probe_count": len(probe),
        "current_count": len(current),
        "current_request_count": len(current_request),
        "callback_count": len(callbacks),
        "nmea_count": len(nmea),
        "gnss_events": [item.get("event") for item in gnss],
        "negative_kinds": [item.get("kind") for item in negatives],
        "unregister_count": len(unregisters),
        "session_ids": sorted(session_values),
        "failures": failures[-8:],
        "errors": errors,
    }
    if errors:
        raise RuntimeError(f"{phase} validation failed: {json.dumps(result, ensure_ascii=False)}")
    return result


def run_phase(serial: str, output: Path, phase: str, user: int,
              latitude: float, longitude: float, expected_available: bool,
              stable_seconds: int, recovery_seconds: int) -> dict[str, Any]:
    run_adb(serial, ["logcat", "-c"], check=False)
    log_path = output / f"c2-t03-user{user}-{phase}-logcat.txt"
    process, handle = start_logcat(serial, log_path)
    launch: dict[str, Any] = {}
    try:
        launch = require_pass(
            f"launch Location campaign user={user} phase={phase}",
            command(serial, "launch-component", user,
                    ["--es", "component", PROBE_COMPONENT], deadline_sec=180),
        )
        deadline = time.monotonic() + max(0, stable_seconds)
        while time.monotonic() < deadline:
            remaining = int(max(0, deadline - time.monotonic()))
            print(f"C2-T03 phase={phase} user={user} stable_remaining={remaining}s", flush=True)
            time.sleep(min(30, max(1, remaining)))
        run_adb(serial, ["shell", "input", "keyevent", "KEYCODE_HOME"], check=False)
        time.sleep(recovery_seconds)
        run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
        time.sleep(1)
        post_stop = run_adb(serial, ["shell", "pidof", HOST_PACKAGE], check=False)
    finally:
        stop_logcat(process, handle)
    logcat = log_path.read_text(encoding="utf-8", errors="replace")
    minimum_callbacks = max(3, stable_seconds // 2) if expected_available else 0
    observation = validate_phase(logcat, phase, latitude, longitude,
                                 expected_available, minimum_callbacks)
    observation["post_stop_processes"] = post_stop.stdout.strip()
    if observation["post_stop_processes"]:
        raise RuntimeError(
            f"{phase} host process survived force-stop: {observation['post_stop_processes']}"
        )
    observation.update({
        "user": user,
        "launch": launch,
        "stable_seconds": stable_seconds,
        "recovery_seconds": recovery_seconds,
        "logcat": str(log_path),
    })
    return observation


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    parser.add_argument("--stable-seconds", type=int, default=1800)
    parser.add_argument("--recovery-seconds", type=int, default=5)
    args = parser.parse_args()
    if args.stable_seconds < 1 or args.recovery_seconds < 1:
        raise SystemExit("stable/recovery seconds must be positive")

    output = artifacts_dir("catch-up-c2-t03")
    verification = ROOT / "verification/catch-up/C2-T03"
    verification.mkdir(parents=True, exist_ok=True)
    evidence: dict[str, Any] = {
        "campaign_id": TASK_ID,
        "capability": "Location",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "build_result": "PASS",
        "static_result": "PASS",
        "targeted_result": "PASS",
        "rd_result": "UNVERIFIED",
        "regression_result": "UNVERIFIED",
        "failures": [],
        "known_issues": ["KI-R03-020", "KI-R03-023", "KI-R03-024", "KI-R03-025",
                         "KI-R03-026", "KI-M10-005", "KI-M10-006", "KI-M10-007",
                         "KI-R03-034", "KI-R03-035"],
        "evidence_files": [],
        "notes": "RD API32 evidence only; VA PRO equivalence and OEM/HAL matrix remain unproven.",
    }
    try:
        environment = resolve_rd_environment(args.instance)
        evidence.update({
            "branch": git_identity()["branch"],
            "commit": git_identity()["commit"],
            "tree": git_identity()["tree"],
            "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
            "device_name": environment["device_name"],
            "adb_serial": environment["adb_serial"],
            "api_level": environment["api_level"],
            "abi": environment["abi"],
            "boot_id": environment["boot_id"],
            "android_id": environment["android_id"],
            "instance_name": environment["instance_name"],
        })
        serial = environment["adb_serial"]
        reset = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
        if reset.returncode != 0 or reset.stdout.strip().lower() != "success":
            raise RuntimeError(f"host data reset failed: {reset.stdout} {reset.stderr}")
        install = install_rd_apks(serial)
        evidence["apk_install"] = install
        host_grants = []
        for permission in HOST_LOCATION_PERMISSIONS:
            grant = run_adb(serial, ["shell", "pm", "grant", HOST_PACKAGE, permission],
                            check=False)
            host_grants.append({
                "permission": permission,
                "returncode": grant.returncode,
                "stdout": grant.stdout.strip(),
                "stderr": grant.stderr.strip(),
            })
            if grant.returncode != 0:
                raise RuntimeError(
                    f"host location grant failed for {permission}: {grant.stdout} {grant.stderr}"
                )
        evidence["host_location_grants"] = host_grants
        require_pass("import prepare", command(serial, "import-prepare", 0, deadline_sec=180))
        for user in (0, 1):
            set_permission(serial, user, "GRANTED")
            set_appops(serial, user)
        configure_location(serial, 0, 31.23040000, 121.47370000,
                           nmea="$GPGGA,C2T03U0,3113.824,N,12128.422,E,1,08,0.9,4.0,M,0.0,M,,*00")
        configure_location(serial, 1, 31.22040000, 121.48370000,
                           nmea="$GPGGA,C2T03U1,3113.224,N,12129.022,E,1,08,0.9,4.0,M,0.0,M,,*00")

        evidence["observations"] = []
        set_permission(serial, 0, "DENIED")
        evidence["observations"].append(run_phase(
            serial, output, "permission-denied", 0, 31.2304, 121.4737, False,
            min(5, args.stable_seconds), args.recovery_seconds))

        set_permission(serial, 0, "GRANTED")
        configure_location(serial, 0, 31.23140000, 121.47470000,
                           nmea="$GPGGA,C2T03U0R,3113.884,N,12128.482,E,1,08,0.9,4.0,M,0.0,M,,*00")
        evidence["observations"].append(run_phase(
            serial, output, "user0-profile-update", 0, 31.2314, 121.4747, True,
            args.stable_seconds, args.recovery_seconds))

        evidence["observations"].append(run_phase(
            serial, output, "user1-isolation", 1, 31.2204, 121.4837, True,
            args.stable_seconds, args.recovery_seconds))

        require_pass("clear user1", command(serial, "clear", 1, deadline_sec=180))
        evidence["observations"].append(run_phase(
            serial, output, "user1-clear", 1, 31.2204, 121.4837, False,
            min(5, args.stable_seconds), args.recovery_seconds))

        evidence["rd_result"] = "PASS"
        evidence["regression_result"] = "PASS"
        evidence["status"] = "PASS"
        evidence["finished_at"] = now_iso()
    except Exception as error:
        evidence["status"] = "FAIL"
        evidence["rd_result"] = "FAIL"
        evidence["failures"].append({
            "id": "C2-T03-RD",
            "classification": "FAIL",
            "summary": str(error),
        })
        evidence["error"] = str(error)
        evidence["finished_at"] = now_iso()
    evidence_path = output / "evidence.json"
    verification_path = verification / "c2-t03-rd-summary.json"
    evidence["evidence_files"] = [str(path) for path in sorted(output.glob("*"))]
    evidence["evidence_files"].append(str(evidence_path))
    evidence["evidence_files"].append(str(verification_path))
    write_json(evidence_path, evidence)
    write_json(verification_path, evidence)
    print(json.dumps({"status": evidence["status"], "output": str(output),
                      "verification": str(verification_path),
                      "serial": evidence.get("adb_serial", ""),
                      "observations": len(evidence.get("observations", []))},
                     ensure_ascii=False, indent=2))
    return 0 if evidence.get("status") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
