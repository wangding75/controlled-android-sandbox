#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/android-gradle-build.json"
TASKS = [
    ":sandbox-domain:check",
    ":sandbox-contract:assembleDebug",
    ":sandbox-framework:assembleDebug",
    ":sandbox-native:assembleDebug",
    ":sandbox-runtime:assembleDebug",
    ":app:assembleDebug",
    ":sandbox-companion32:assembleDebug",
    ":fixture-basic:assembleDebug",
    ":fixture-compat32:assembleDebug",
    ":fixture-activity-scale:assembleDebug",
    ":fixture-lifecycle:assembleV1Debug",
    ":fixture-lifecycle:assembleV2Debug",
]


def sdk_root() -> Path | None:
    value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    return Path(value).expanduser().resolve() if value else None


def write_report(payload: dict) -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_value(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=ROOT, text=True, encoding="utf-8",
        errors="replace", capture_output=True, check=True,
    ).stdout.strip()


def verified_external_evidence(path: Path) -> list[str]:
    """Validate an opt-in native build proof before a POSIX-only aggregate run."""
    errors: list[str] = []
    try:
        evidence = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"cannot read external Android build evidence: {error}"]
    if evidence.get("status") != "PASS" or evidence.get("exitCode") != 0:
        errors.append("external Android build evidence is not a successful build")
    try:
        if evidence.get("commit") != git_value("rev-parse", "HEAD"):
            errors.append("external Android build evidence commit does not match HEAD")
        if evidence.get("tree") != git_value("rev-parse", "HEAD^{tree}"):
            errors.append("external Android build evidence tree does not match HEAD")
    except subprocess.CalledProcessError as error:
        errors.append(f"cannot read current Git identity: {error}")
    command = evidence.get("command")
    if not isinstance(command, list) or not command:
        errors.append("external Android build evidence has no build command")
    artifacts = evidence.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        errors.append("external Android build evidence has no artifact hashes")
        return errors
    for item in artifacts:
        if not isinstance(item, dict):
            errors.append("external Android build evidence has an invalid artifact row")
            continue
        relative = item.get("path")
        expected = item.get("sha256")
        if not isinstance(relative, str) or not isinstance(expected, str):
            errors.append("external Android build evidence has an incomplete artifact row")
            continue
        artifact = ROOT / relative
        if not artifact.is_file():
            errors.append(f"evidence artifact is missing: {relative}")
        elif sha256_file(artifact).lower() != expected.lower():
            errors.append(f"evidence artifact hash mismatch: {relative}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("verify",))
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    args = parser.parse_args()

    errors: list[str] = []
    sdk = sdk_root()
    if sdk is None:
        errors.append("ANDROID_SDK_ROOT or ANDROID_HOME is not set")
    else:
        if not (sdk / "platforms" / "android-36" / "android.jar").is_file():
            errors.append("compileSdk platform android-36 is unavailable")
        if not (sdk / "build-tools" / "35.0.0").is_dir():
            errors.append("Android build-tools 35.0.0 is unavailable")
        if not (sdk / "ndk" / "27.2.12479018").is_dir():
            errors.append("Android NDK 27.2.12479018 is unavailable")
        if not (sdk / "cmake" / "3.22.1").is_dir():
            errors.append("Android CMake 3.22.1 is unavailable")
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        errors.append("Gradle wrapper launcher is unavailable")

    payload = {
        "status": "FAIL" if errors else "PENDING",
        "androidBuildEvidence": False,
        "sdkRoot": str(sdk) if sdk else "",
        "tasks": TASKS,
        "errors": errors,
    }
    if errors:
        write_report(payload)
        print("FAIL Android Gradle build gate", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1

    evidence_override = os.environ.get("CONTROLLED_ANDROID_GRADLE_EVIDENCE")
    if evidence_override:
        evidence_path = Path(evidence_override).expanduser().resolve()
        evidence_errors = verified_external_evidence(evidence_path)
        if evidence_errors:
            payload.update({"status": "FAIL", "errors": evidence_errors})
            write_report(payload)
            print("FAIL external Android Gradle build evidence", file=sys.stderr)
            for error in evidence_errors:
                print(" - " + error, file=sys.stderr)
            return 1
        payload.update({
            "status": "PASS",
            "androidBuildEvidence": True,
            "exitCode": 0,
            "externalEvidence": str(evidence_path),
            "errors": [],
        })
        write_report(payload)
        print("PASS verified matching external Android Gradle/APK/AAR build evidence")
        return 0

    command = [str(wrapper), "--no-daemon", "--stacktrace",
               "--dependency-verification=strict", *TASKS]
    started = time.monotonic()
    try:
        result = subprocess.run(command, cwd=ROOT, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=args.timeout_seconds)
        output = result.stdout
        exit_code = result.returncode
    except subprocess.TimeoutExpired as error:
        output = (error.stdout or "") + "\nANDROID_GRADLE_BUILD_TIMEOUT\n"
        exit_code = 124
    log = ROOT / "build/verification/android-gradle-build.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(output, encoding="utf-8")
    payload.update({
        "status": "PASS" if exit_code == 0 else "FAIL",
        "androidBuildEvidence": exit_code == 0,
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "exitCode": exit_code,
        "log": str(log.relative_to(ROOT)).replace("\\", "/"),
        "errors": [] if exit_code == 0 else ["Gradle task execution failed"],
    })
    write_report(payload)
    if exit_code != 0:
        print("FAIL Android Gradle build gate; see " + str(log), file=sys.stderr)
        return exit_code
    print("PASS Android Gradle/AIDL/CMake/APK build gate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
