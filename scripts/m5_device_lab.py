#!/usr/bin/env python3
"""Deterministic M5 Android Emulator device-lab runner and evidence validator."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

from mumu_instance import ResolutionError, resolve_instance

SCHEMA_VERSION = 1
COMMANDS = ("import-prepare", "component-suite", "launch")
FIXTURE_IDS = ("fixture64", "fixture32")
VIRTUAL_USERS = (0, 1)
ARTIFACT_IDS = ("host", "fixture64", "fixture32", "companion32")
FATAL_PATTERNS = (
    r"FATAL EXCEPTION",
    r"ANR in com\.warden\.controlledsandbox",
    r"Fatal signal.*controlledsandbox",
    r"Process: com\.warden\.controlledsandbox.*has died",
)
ENVIRONMENT_NOISE_PATTERNS = (
    r"Watchdog: upload event:.*app_package.*com\.warden\.controlledsandbox",
)
COMMAND_ATTEMPTS = 3
COMMAND_RESTART_DELAY_SECONDS = 5.0
PACKAGE_AUTHORITY_RETRY_DELAY_SECONDS = 15.0
POST_INSTALL_STARTUP_DELAY_SECONDS = 15.0
LAUNCH_LIFECYCLE_TIMEOUT_SECONDS = 20.0
LAUNCH_STABILITY_DELAY_SECONDS = 4.0


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_adb_devices(text: str) -> list[str]:
    devices: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def fatal_findings(log_text: str) -> list[str]:
    findings: list[str] = []
    for pattern in FATAL_PATTERNS:
        if re.search(pattern, log_text, re.IGNORECASE):
            findings.append(pattern)
    return findings


def environment_noise_findings(log_text: str) -> list[str]:
    findings: list[str] = []
    for pattern in ENVIRONMENT_NOISE_PATTERNS:
        if re.search(pattern, log_text, re.IGNORECASE | re.DOTALL):
            findings.append("MUMU_WATCHDOG_UPLOAD_TELEMETRY")
    return findings


def activity_lifecycle_counts_from_log(log_text: str) -> tuple[int, int]:
    """Return completed Guest Activity create/resume markers from filtered logcat text."""
    created = len(re.findall(r"GUEST_ACTIVITY_CREATE status=ACTIVITY_CREATED", log_text))
    resumed = len(re.findall(r"CS_FIXTURE.*ACTIVITY_RESUME", log_text))
    return created, resumed


def requires_explicit_native_trust(fixture_id: str, command: str) -> bool:
    """The debug runner must carry the explicit trust decision into official Fixture imports."""
    return fixture_id in FIXTURE_IDS and command == "import-prepare"


def is_package_authority_startup_error(message: str) -> bool:
    return "Package management service is unavailable" in (message or "")


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value


def validate_build_manifest(
    manifest: dict[str, Any], artifact_dir: Path, lock: dict[str, Any], expected_commit: str
) -> list[str]:
    errors: list[str] = []
    if manifest.get("schemaVersion") != 1:
        errors.append("build manifest schemaVersion must be 1")
    if manifest.get("profile") != "device-lab":
        errors.append("build manifest profile must be device-lab")
    if expected_commit and manifest.get("commit") != expected_commit:
        errors.append(
            f"build manifest commit mismatch: expected {expected_commit}, found {manifest.get('commit')}"
        )
    configured = lock.get("deviceLabBuild", {}).get("artifacts", [])
    expected_by_id = {str(item.get("id")): item for item in configured}
    observed = manifest.get("artifacts", [])
    if not isinstance(observed, list):
        return errors + ["build manifest artifacts must be a list"]
    observed_ids = [str(item.get("id")) for item in observed if isinstance(item, dict)]
    if observed_ids != list(ARTIFACT_IDS):
        errors.append(f"artifact order must be {list(ARTIFACT_IDS)}, found {observed_ids}")
    for item in observed:
        if not isinstance(item, dict):
            errors.append("artifact entry must be an object")
            continue
        artifact_id = str(item.get("id"))
        expected = expected_by_id.get(artifact_id)
        if expected is None:
            errors.append(f"unexpected artifact id: {artifact_id}")
            continue
        if item.get("applicationId") != expected.get("applicationId"):
            errors.append(f"{artifact_id} applicationId mismatch")
        if item.get("abis") != sorted(expected.get("allowedAbis", [])):
            errors.append(f"{artifact_id} ABI set mismatch")
        name = str(item.get("collectedName", ""))
        path = artifact_dir / name
        if not path.is_file():
            errors.append(f"missing collected APK: {path}")
            continue
        actual_hash = sha256(path)
        if actual_hash != item.get("sha256"):
            errors.append(f"{artifact_id} SHA-256 mismatch")
        if path.stat().st_size != item.get("size"):
            errors.append(f"{artifact_id} size mismatch")
    return errors


def validate_formal_evidence(
    evidence: dict[str, Any], lock: dict[str, Any], expected_commit: str | None = None
) -> list[str]:
    errors: list[str] = []
    if evidence.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("evidence schemaVersion must be 1")
    if evidence.get("task") != "M5-T5":
        errors.append("evidence task must be M5-T5")
    if evidence.get("status") != "PASS":
        errors.append("formal evidence status must be PASS")
    if evidence.get("formal") is not True:
        errors.append("formal evidence flag must be true")
    if expected_commit and evidence.get("commit") != expected_commit:
        errors.append("evidence commit does not match expected commit")

    required_seconds = int(lock["deviceLab"]["formalStabilitySeconds"])
    observed_seconds = int(evidence.get("observedStabilitySeconds", 0) or 0)
    if observed_seconds < required_seconds:
        errors.append(f"stability duration {observed_seconds}s is below {required_seconds}s")

    device = evidence.get("device", {})
    if not isinstance(device, dict):
        errors.append("device evidence is missing")
    else:
        abi_list = device.get("abilist", [])
        if isinstance(abi_list, str):
            abi_list = [part.strip() for part in abi_list.split(",") if part.strip()]
        for abi in lock["deviceLab"]["requiredDeviceAbis"]:
            if abi not in abi_list:
                errors.append(f"device does not advertise required ABI: {abi}")

    artifacts = evidence.get("artifacts", [])
    if not isinstance(artifacts, list):
        errors.append("artifact evidence is missing")
    else:
        ids = [str(item.get("id")) for item in artifacts if isinstance(item, dict)]
        if ids != list(ARTIFACT_IDS):
            errors.append(f"formal evidence requires four artifacts in order: {list(ARTIFACT_IDS)}")
        for item in artifacts:
            if not isinstance(item, dict) or not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256", ""))):
                errors.append("artifact SHA-256 evidence is invalid")

    commands = evidence.get("commandResults", [])
    if not isinstance(commands, list):
        commands = []
        errors.append("commandResults must be a list")
    keys: dict[tuple[str, int, str], dict[str, Any]] = {}
    for result in commands:
        if not isinstance(result, dict):
            continue
        key = (str(result.get("fixtureId")), int(result.get("virtualUserId", -1)), str(result.get("command")))
        keys[key] = result
    for fixture_id in FIXTURE_IDS:
        for user in VIRTUAL_USERS:
            for command in COMMANDS:
                result = keys.get((fixture_id, user, command))
                if result is None:
                    errors.append(f"missing command result: {fixture_id}/u{user}/{command}")
                    continue
                if result.get("status") != "PASS":
                    errors.append(f"command did not pass: {fixture_id}/u{user}/{command}")
                if fixture_id == "fixture32":
                    companion = result.get("companion", {})
                    if not isinstance(companion, dict) or companion.get("successful") is not True:
                        errors.append(f"missing successful Companion32 probe: u{user}/{command}")
                    else:
                        if companion.get("processBitness") != 32:
                            errors.append(f"Companion32 bitness is not 32: u{user}/{command}")
                        if companion.get("requestedAbi") not in ("x86", "armeabi-v7a"):
                            errors.append(f"Companion32 requested ABI invalid: u{user}/{command}")

    companion = evidence.get("companionEvidence", {})
    if not isinstance(companion, dict):
        errors.append("companionEvidence is missing")
    else:
        if not str(companion.get("pid", "")).strip():
            errors.append("Companion32 process PID is missing")
        if companion.get("processBitness") != 32:
            errors.append("Companion32 process bitness evidence is not 32")
        if int(companion.get("successfulProbeCount", 0) or 0) < 6:
            errors.append("insufficient successful Companion32 probes")

    diagnostics = evidence.get("runtimeDiagnostics", [])
    if not isinstance(diagnostics, list) or not diagnostics:
        errors.append("runtime diagnostics evidence is missing")
    findings = evidence.get("fatalFindings", [])
    if findings:
        errors.append(f"fatal findings are present: {findings}")
    if evidence.get("deviceRunCount") != 1:
        errors.append("formal evidence must represent exactly one device run")
    simultaneous = evidence.get("simultaneousGuestSlots", {})
    if not isinstance(simultaneous, dict) or simultaneous.get("observed") is not True:
        errors.append("simultaneous user0/user1 Guest slot evidence is missing")
    else:
        slots = simultaneous.get("slots", [])
        if not isinstance(slots, list) or len(slots) < 2:
            errors.append("simultaneous Guest evidence must contain at least two slots")
        users = {int(item.get("virtualUserId", -1)) for item in slots if isinstance(item, dict)}
        if not {0, 1}.issubset(users):
            errors.append("simultaneous Guest evidence must cover virtual users 0 and 1")
    teardown = evidence.get("teardown", {})
    if not isinstance(teardown, dict) or teardown.get("status") != "PASS":
        errors.append("formal Guest stop/teardown evidence is missing")
    return errors


@dataclass(frozen=True)
class ToolPaths:
    adb: Path
    emulator: Path
    avdmanager: Path


class CommandError(RuntimeError):
    pass


def executable_command(path: Path, *args: str) -> list[str]:
    """Execute Windows batch tools through cmd.exe; native tools run directly."""
    values = [str(path), *args]
    if os.name == "nt" and path.suffix.lower() in (".bat", ".cmd"):
        return ["cmd.exe", "/d", "/s", "/c", *values]
    return values


class CommandRunner:
    def __init__(self, log_dir: Path):
        self.log_dir = log_dir
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.counter = 0

    def run(
        self,
        command: Sequence[str | os.PathLike[str]],
        *,
        check: bool = True,
        timeout: float | None = None,
        cwd: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        args = [str(item) for item in command]
        self.counter += 1
        result = subprocess.run(
            args,
            text=True,
            capture_output=True,
            timeout=timeout,
            cwd=cwd,
            errors="replace",
        )
        log = self.log_dir / f"command-{self.counter:04d}.txt"
        log.write_text(
            "$ " + " ".join(args) + "\n"
            + f"exit={result.returncode}\n--- stdout ---\n{result.stdout}\n--- stderr ---\n{result.stderr}\n",
            encoding="utf-8",
        )
        if check and result.returncode != 0:
            raise CommandError(f"command failed ({result.returncode}): {' '.join(args)}")
        return result


class DeviceLab:
    def __init__(
        self,
        root: Path,
        sdk_root: Path,
        evidence_dir: Path,
        serial: str,
        artifact_dir: Path | None,
        stability_seconds: int,
        diagnostic: bool,
        skip_build: bool,
        online_build: bool,
        keep_emulator: bool,
        headless: bool,
        mumu_resolution: dict[str, Any] | None,
        mumu_instance_name: str,
        mumu_root: Path | None,
    ):
        self.root = root
        self.sdk_root = sdk_root
        self.evidence_dir = evidence_dir
        self.serial = serial
        self.artifact_dir = artifact_dir
        self.stability_seconds = stability_seconds
        self.diagnostic = diagnostic
        self.skip_build = skip_build
        self.online_build = online_build
        self.keep_emulator = keep_emulator
        self.headless = headless
        self.mumu_resolution = mumu_resolution
        self.mumu_instance_name = mumu_instance_name
        self.mumu_root = mumu_root
        self.lock = load_json(root / "build-environment.lock.json")
        self.lab = self.lock["deviceLab"]
        self.packages = self.lab["packages"]
        self.command_activity = (
            self.packages["host"] + "/com.warden.controlledsandbox.DebugCommandActivity"
        )
        self.runner = CommandRunner(evidence_dir / "commands")
        self.tools = self._resolve_tools()
        self.started_emulator: subprocess.Popen[str] | None = None
        self.command_results: list[dict[str, Any]] = []
        self.guest_smoke_active = False
        self.active_guest_sessions: dict[tuple[str, int], dict[str, Any]] = {}
        self.simultaneous_guest_slots: dict[str, Any] = {}
        self.logcat_history_path = self.evidence_dir / "logcat-history.txt"
        self.lifecycle_observation_path = self.evidence_dir / "lifecycle-observation.txt"
        self.lifecycle_capture_process: subprocess.Popen[str] | None = None
        self.lifecycle_capture_handle: Any | None = None
        self.lifecycle_capture_path: Path | None = None
        self.started_monotonic = time.monotonic()
        self.evidence: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "task": "M5-T5",
            "status": "IN_PROGRESS",
            "formal": not diagnostic,
            "startedAtUtc": utc_now(),
            "requestedStabilitySeconds": stability_seconds,
            "observedStabilitySeconds": 0,
            "deviceRunCount": 0,
            "commandResults": self.command_results,
            "fatalFindings": [],
            "environmentNoise": [],
        }
        if mumu_resolution is not None:
            self.evidence["mumuResolution"] = mumu_resolution

    def _resolve_tools(self) -> ToolPaths:
        exe = ".exe" if os.name == "nt" else ""
        bat = ".bat" if os.name == "nt" else ""
        paths = ToolPaths(
            adb=self.sdk_root / "platform-tools" / f"adb{exe}",
            emulator=self.sdk_root / "emulator" / f"emulator{exe}",
            avdmanager=self.sdk_root / "cmdline-tools" / "latest" / "bin" / f"avdmanager{bat}",
        )
        for path in (paths.adb, paths.emulator, paths.avdmanager):
            if not path.is_file():
                raise CommandError(f"missing Android device-lab tool: {path}")
        return paths

    def adb(self, *args: str, check: bool = True, timeout: float | None = 120) -> subprocess.CompletedProcess[str]:
        command: list[str | os.PathLike[str]] = [self.tools.adb]
        if self.serial:
            command.extend(("-s", self.serial))
        command.extend(args)
        return self.runner.run(command, check=check, timeout=timeout)

    def refresh_mumu_resolution(self) -> None:
        if not self.mumu_instance_name:
            return
        resolution = resolve_instance(
            self.mumu_instance_name,
            mumu_root=self.mumu_root,
            adb=self.tools.adb,
        )
        serial = str(resolution.get("resolvedSerial", ""))
        if resolution.get("runtimeStatus") != "device" or not serial:
            raise CommandError("MuMu instance is not an online adb device")
        self.serial = serial
        self.mumu_resolution = resolution
        self.evidence["mumuResolution"] = resolution
        (self.evidence_dir / "mumu-instance-resolution.json").write_text(
            json.dumps(resolution, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def prepare_artifacts(self) -> tuple[dict[str, Any], Path]:
        commit = self.runner.run(["git", "rev-parse", "HEAD"], cwd=self.root).stdout.strip()
        self.evidence["commit"] = commit
        if not self.skip_build:
            if os.name == "nt":
                command = [
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", str(self.root / "scripts" / "build-device-lab-apks.ps1"),
                ]
                if self.online_build:
                    command.append("-Online")
            else:
                command = [str(self.root / "scripts" / "build-device-lab-apks.sh")]
                if self.online_build:
                    command.append("--online")
            self.runner.run(command, cwd=self.root, timeout=3600)
        if self.artifact_dir is None:
            short = self.runner.run(["git", "rev-parse", "--short=12", "HEAD"], cwd=self.root).stdout.strip()
            self.artifact_dir = self.root / self.lock["deviceLabBuild"]["outputDirectory"] / short
        manifest_path = self.artifact_dir / "build-manifest.json"
        if not manifest_path.is_file():
            raise CommandError(f"missing device-lab build manifest: {manifest_path}")
        manifest = load_json(manifest_path)
        errors = validate_build_manifest(manifest, self.artifact_dir, self.lock, commit)
        if errors:
            raise CommandError("invalid device-lab build manifest: " + "; ".join(errors))
        self.evidence["buildManifestSha256"] = sha256(manifest_path)
        self.evidence["artifacts"] = manifest["artifacts"]
        return manifest, self.artifact_dir

    def ensure_device(self) -> None:
        devices = parse_adb_devices(self.runner.run([self.tools.adb, "devices"], timeout=30).stdout)
        if self.serial:
            if self.serial not in devices:
                raise CommandError(f"requested Android device is not online: {self.serial}")
            return
        if len(devices) > 1:
            raise CommandError("multiple Android devices are online; pass --serial explicitly")
        if devices:
            self.serial = devices[0]
            return
        avd_name = self.lab["avdName"]
        existing = self.runner.run([self.tools.emulator, "-list-avds"], timeout=30).stdout.splitlines()
        if avd_name not in [item.strip() for item in existing]:
            create = executable_command(
                self.tools.avdmanager, "create", "avd", "--force", "--name", avd_name,
                "--package", self.lab["systemImage"], "--device", self.lab["deviceProfile"],
            )
            result = subprocess.run(
                create, input="no\n", text=True, capture_output=True, timeout=120, errors="replace"
            )
            (self.evidence_dir / "avd-create.txt").write_text(
                result.stdout + "\n" + result.stderr, encoding="utf-8"
            )
            if result.returncode != 0:
                raise CommandError(f"avdmanager failed: {result.returncode}")
        before = set(devices)
        arguments = [
            str(self.tools.emulator), f"@{avd_name}", "-no-snapshot", "-no-audio",
            "-no-boot-anim", "-gpu", "swiftshader_indirect", "-wipe-data",
        ]
        if self.headless:
            arguments.append("-no-window")
        emulator_stdout = (self.evidence_dir / "emulator-stdout.txt").open("w", encoding="utf-8")
        emulator_stderr = (self.evidence_dir / "emulator-stderr.txt").open("w", encoding="utf-8")
        try:
            self.started_emulator = subprocess.Popen(
                arguments, stdout=emulator_stdout, stderr=emulator_stderr, text=True,
            )
        finally:
            emulator_stdout.close()
            emulator_stderr.close()
        deadline = time.monotonic() + int(self.lab["bootTimeoutSeconds"])
        while time.monotonic() < deadline:
            current = parse_adb_devices(self.runner.run([self.tools.adb, "devices"], check=False, timeout=30).stdout)
            new_devices = [item for item in current if item not in before]
            if new_devices:
                self.serial = new_devices[0]
                break
            if current:
                self.serial = current[0]
                break
            time.sleep(2)
        if not self.serial:
            raise CommandError("Android Emulator did not register with adb")
        self.adb("wait-for-device", timeout=int(self.lab["bootTimeoutSeconds"]))
        while time.monotonic() < deadline:
            boot = self.adb("shell", "getprop", "sys.boot_completed", check=False, timeout=30).stdout.strip()
            if boot == "1":
                return
            time.sleep(3)
        raise CommandError("Android Emulator boot timeout")

    def device_info(self) -> dict[str, Any]:
        properties = {
            "sdk": "ro.build.version.sdk",
            "release": "ro.build.version.release",
            "fingerprint": "ro.build.fingerprint",
            "abi": "ro.product.cpu.abi",
            "abilistRaw": "ro.product.cpu.abilist",
            "model": "ro.product.model",
            "manufacturer": "ro.product.manufacturer",
        }
        value: dict[str, Any] = {"serial": self.serial}
        for key, prop in properties.items():
            value[key] = self.adb("shell", "getprop", prop).stdout.strip()
        value["abilist"] = [part.strip() for part in value.pop("abilistRaw").split(",") if part.strip()]
        value["androidId"] = self.adb(
            "shell", "settings", "get", "secure", "android_id"
        ).stdout.strip()
        missing = [abi for abi in self.lab["requiredDeviceAbis"] if abi not in value["abilist"]]
        if missing:
            raise CommandError(f"device does not support required 64/32-bit ABI pair: {missing}")
        return value

    def install_artifacts(self, manifest: dict[str, Any], artifact_dir: Path) -> None:
        by_id = {item["id"]: item for item in manifest["artifacts"]}
        order = ("companion32", "host", "fixture64", "fixture32")
        for package in self.packages.values():
            self.adb("uninstall", package, check=False, timeout=60)
        for artifact_id in order:
            item = by_id[artifact_id]
            apk = artifact_dir / item["collectedName"]
            result = self.adb("install", "-r", "-t", "-g", str(apk), timeout=300)
            if "Success" not in result.stdout:
                raise CommandError(f"adb install did not report Success for {artifact_id}")
        for key, package in self.packages.items():
            path = self.adb("shell", "pm", "path", package).stdout.strip()
            if not path.startswith("package:"):
                raise CommandError(f"installed package not visible: {key}={package}")
        self.adb("logcat", "-c")
        # MuMu can report the APK immediately while its newly installed Binder
        # service processes are still being registered.  Let the first binding
        # attempt start from a settled package-authority state.
        time.sleep(POST_INSTALL_STARTUP_DELAY_SECONDS)

    def recover_stale_runtime(self) -> None:
        """Stop the guest side before the host side when a command truly needs recovery.

        A successful launch leaves a live guest activity backed by a Binder session owned
        by the host.  Force-stopping the host first invalidates that session while the
        guest is still starting and creates a synthetic DeadObjectException/FATAL in the
        very lifecycle we are trying to verify.  Recovery is therefore ordered guest
        first, host second, and is only used after a failed command attempt.
        """
        self.adb("shell", "am", "force-stop", self.packages["companion32"], check=False, timeout=60)
        time.sleep(COMMAND_RESTART_DELAY_SECONDS)
        self.adb("shell", "am", "force-stop", self.packages["host"], check=False, timeout=60)
        time.sleep(COMMAND_RESTART_DELAY_SECONDS)
        self.guest_smoke_active = False

    def activity_lifecycle_counts(self) -> tuple[int, int]:
        output = self.adb(
            "logcat", "-d", "-v", "threadtime",
            check=False, timeout=60,
        ).stdout
        filtered = "\n".join(
            line for line in output.splitlines()
            if "CS_RUNTIME" in line or "CS_FIXTURE" in line
        )
        if filtered:
            with self.lifecycle_observation_path.open("a", encoding="utf-8") as observation:
                observation.write(filtered + "\n")
        observed = self.lifecycle_observation_path.read_text(encoding="utf-8") \
            if self.lifecycle_observation_path.is_file() else ""
        if self.lifecycle_capture_path is not None and self.lifecycle_capture_path.is_file():
            observed += "\n" + self.lifecycle_capture_path.read_text(encoding="utf-8", errors="replace")
        return activity_lifecycle_counts_from_log(observed)

    def start_lifecycle_capture(self, fixture_id: str, user: int, attempt: int) -> None:
        if self.lifecycle_capture_process is not None:
            raise CommandError("lifecycle capture is already running")
        path = self.evidence_dir / f"lifecycle-capture-{fixture_id}-u{user}-attempt{attempt}.txt"
        handle = path.open("w", encoding="utf-8", buffering=1)
        command = [str(self.tools.adb)]
        if self.serial:
            command.extend(("-s", self.serial))
        command.extend(("logcat", "-v", "threadtime"))
        self.lifecycle_capture_handle = handle
        self.lifecycle_capture_path = path
        self.lifecycle_capture_process = subprocess.Popen(
            command, stdout=handle, stderr=subprocess.DEVNULL,
            text=True, creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )

    def stop_lifecycle_capture(self) -> None:
        process = self.lifecycle_capture_process
        handle = self.lifecycle_capture_handle
        self.lifecycle_capture_process = None
        self.lifecycle_capture_handle = None
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if handle is not None:
            handle.flush()
            handle.close()

    def reset_lifecycle_log(self, fixture_id: str, user: int, attempt: int) -> None:
        """Start a bounded logcat window for one launch lifecycle assertion.

        MuMu's logcat ring can evict older Guest markers during a long run.  A
        cumulative counter therefore cannot prove that the current launch
        created/resumed an Activity.  Preserve the old ring for the final crash
        scan, then clear it so the next window has an unambiguous baseline.
        """
        snapshot = self.adb(
            "logcat", "-d", "-v", "threadtime", check=False, timeout=180,
        ).stdout
        with self.logcat_history_path.open("a", encoding="utf-8") as history:
            history.write(
                f"=== before {fixture_id}/u{user}/launch attempt {attempt} ===\n"
            )
            history.write(snapshot)
            history.write("\n")
        self.lifecycle_observation_path.write_text("", encoding="utf-8")
        self.adb("logcat", "-c", check=False, timeout=60)

    def wait_for_activity_lifecycle(self, before: tuple[int, int], fixture_id: str, user: int) -> None:
        deadline = time.monotonic() + LAUNCH_LIFECYCLE_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            created, resumed = self.activity_lifecycle_counts()
            # component-suite may have already created the Guest Activity in this live slot;
            # launch/relaunch then legitimately emits resume without a second create callback.
            # Require the lifecycle transition under test while retaining both counters in the
            # evidence stream for the create-vs-reuse distinction.
            if resumed > before[1]:
                # The Guest callback is emitted inside Activity.onResume.  Android
                # still has a small amount of window/task bookkeeping to complete
                # after that callback returns; do not switch virtual tasks in that
                # interval on API32 MuMu.
                time.sleep(LAUNCH_STABILITY_DELAY_SECONDS)
                return
            time.sleep(0.5)
        raise CommandError(
            f"Guest Activity lifecycle timeout: {fixture_id}/u{user}/launch "
            f"before={before} after={self.activity_lifecycle_counts()}"
        )

    def guest_process_snapshot(self) -> list[dict[str, Any]]:
        output = self.adb("shell", "ps", "-A", check=False, timeout=60).stdout
        processes: list[dict[str, Any]] = []
        active_slots_by_fixture: dict[str, list[int]] = {}
        for (fixture_id, _user), operation in self.active_guest_sessions.items():
            slot = operation.get("processSlot")
            if isinstance(slot, int) and slot >= 0:
                active_slots_by_fixture.setdefault(fixture_id, []).append(slot)
        for line in output.splitlines():
            match = re.search(r"com\.warden\.controlledsandbox\.debug:guest(\d+)", line)
            fields = line.split()
            if match:
                processes.append({
                    "slot": int(match.group(1)),
                    "pid": fields[1] if len(fields) > 1 else "",
                    "state": fields[7] if len(fields) > 7 else "",
                    "line": line,
                })
                continue

            # T57 runs Guest code in the real fixture package process rather than
            # the retired host:guestN process naming convention.  Tie each live
            # fixture process to an already recorded operation slot; never infer
            # a slot from ADB ordering or select an arbitrary process.
            process_name = fields[-1] if fields else ""
            fixture_id = next(
                (candidate for candidate in FIXTURE_IDS
                 if process_name == self.packages[candidate]),
                None,
            )
            slots = active_slots_by_fixture.get(fixture_id or "", [])
            if not slots:
                continue
            processes.append({
                "slot": slots.pop(0),
                "pid": fields[1] if len(fields) > 1 else "",
                "state": fields[7] if len(fields) > 7 else "",
                "package": process_name,
                "line": line,
            })
        return processes

    def capture_simultaneous_snapshot(self) -> None:
        slots = []
        live_slots = {item["slot"] for item in self.guest_process_snapshot()}
        for (fixture_id, user), operation in self.active_guest_sessions.items():
            slot = operation.get("processSlot")
            if not isinstance(slot, int) or slot not in live_slots:
                continue
            slots.append(
                {
                    "fixtureId": fixture_id,
                    "virtualUserId": user,
                    "processSlot": slot,
                    "sessionId": operation.get("sessionId", ""),
                    "generation": operation.get("generation", 0),
                    "processName": f"{self.packages['host']}:guest{slot}",
                }
            )
        users = {item["virtualUserId"] for item in slots}
        self.simultaneous_guest_slots = {
            "observed": len(slots) >= 2 and {0, 1}.issubset(users),
            "observedAtUtc": utc_now(),
            "slots": slots,
            "liveProcesses": self.guest_process_snapshot(),
        }
        self.evidence["simultaneousGuestSlots"] = self.simultaneous_guest_slots
        (self.evidence_dir / "simultaneous-processes.txt").write_text(
            self.adb("shell", "ps", "-A", check=False, timeout=60).stdout,
            encoding="utf-8",
        )
        (self.evidence_dir / "simultaneous-activities.txt").write_text(
            self.adb("shell", "dumpsys", "activity", "activities", check=False, timeout=120).stdout,
            encoding="utf-8",
        )

    def establish_simultaneous_guest_slots(self) -> None:
        """Create user0/user1 Guest slots without host/Companion force-stop."""
        self.adb("shell", "input", "keyevent", "3", check=False, timeout=30)
        self.invoke_guest("fixture64", "launch", 0)
        self.invoke_guest("fixture64", "launch", 1)
        self.capture_simultaneous_snapshot()
        if not self.simultaneous_guest_slots.get("observed"):
            raise CommandError(
                "formal simultaneous Guest slot check failed: "
                + json.dumps(self.simultaneous_guest_slots, sort_keys=True)
            )

    def invoke_guest(self, fixture_id: str, command: str, user: int) -> dict[str, Any]:
        self.refresh_mumu_resolution()
        package = self.packages[fixture_id]
        host = self.packages["host"]
        start_args = [
            "shell", "am", "start", "-W", "--activity-clear-top", "-n", self.command_activity,
            "--es", "command", command, "--es", "package", package,
            "--ei", "user", str(user),
        ]
        if requires_explicit_native_trust(fixture_id, command):
            start_args.extend(("--ez", "trustNativeGuest", "true"))
        last_error = ""
        for attempt in range(1, COMMAND_ATTEMPTS + 1):
            # A successful command finishes its no-history command activity, so the
            # next explicit start can be issued without disturbing a live guest
            # session.  Only recover after an actual failed attempt; the guest must
            # be stopped before its host Binder owner to avoid a synthetic
            # DeadObjectException during asynchronous Activity startup.
            if attempt > 1:
                if is_package_authority_startup_error(last_error):
                    # A MuMu low-memory recovery can kill Companion32 while the
                    # Host Package Service still holds its old capability.  The
                    # failed command has not started a Guest operation, so it is
                    # safe to rebuild the fixed Companion -> Host boundary.
                    self.recover_stale_runtime()
                    time.sleep(PACKAGE_AUTHORITY_RETRY_DELAY_SECONDS)
                else:
                    self.recover_stale_runtime()
            if command == "launch":
                self.reset_lifecycle_log(fixture_id, user, attempt)
                lifecycle_before = (0, 0)
                self.start_lifecycle_capture(fixture_id, user, attempt)
            else:
                lifecycle_before = None
            self.adb("shell", "run-as", host, "rm", "-f", "files/debug-command-result.json")
            start = self.adb(*start_args, timeout=120)
            start_path = self.evidence_dir / f"start-{fixture_id}-u{user}-{command}-attempt{attempt}.txt"
            start_path.write_text(start.stdout + "\n" + start.stderr, encoding="utf-8")
            deadline = time.monotonic() + int(self.lab["commandTimeoutSeconds"])
            payload = ""
            while time.monotonic() < deadline:
                read = self.adb(
                    "shell", "run-as", host, "cat", "files/debug-command-result.json",
                    check=False, timeout=30,
                )
                payload = read.stdout.strip()
                if payload.startswith("{"):
                    break
                time.sleep(0.5)
            if not payload.startswith("{"):
                last_error = f"Guest command result timeout: {fixture_id}/u{user}/{command}"
                if lifecycle_before is not None:
                    self.stop_lifecycle_capture()
                continue
            try:
                raw = json.loads(payload)
            except json.JSONDecodeError as exc:
                last_error = f"Guest command result is not JSON: {fixture_id}/u{user}/{command}: {exc}"
                if lifecycle_before is not None:
                    self.stop_lifecycle_capture()
                continue
            result = {
                "fixtureId": fixture_id,
                "package": package,
                "virtualUserId": user,
                "command": command,
                "status": raw.get("status"),
                "operation": raw.get("operation"),
                "components": raw.get("components"),
                "companion": raw.get("companion"),
                "errorType": raw.get("errorType", ""),
                "errorMessage": raw.get("errorMessage", ""),
            }
            attempt_path = self.evidence_dir / f"result-{fixture_id}-u{user}-{command}-attempt{attempt}.json"
            attempt_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            if result["status"] != "PASS":
                last_error = (
                    f"Guest command failed: {fixture_id}/u{user}/{command}: "
                    f"{result['errorType']}:{result['errorMessage']}"
                )
                if lifecycle_before is not None:
                    self.stop_lifecycle_capture()
                continue
            if lifecycle_before is not None:
                try:
                    self.wait_for_activity_lifecycle(lifecycle_before, fixture_id, user)
                finally:
                    self.stop_lifecycle_capture()
                self.guest_smoke_active = True
                operation = result.get("operation")
                if isinstance(operation, dict):
                    self.active_guest_sessions[(fixture_id, user)] = operation
            elif command == "stop":
                self.active_guest_sessions.pop((fixture_id, user), None)
            path = self.evidence_dir / f"result-{fixture_id}-u{user}-{command}.json"
            path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            self.command_results.append(result)
            if fixture_id == "fixture32":
                companion = result.get("companion")
                if not isinstance(companion, dict) or companion.get("successful") is not True:
                    raise CommandError(f"missing successful Companion32 probe: u{user}/{command}")
                if companion.get("processBitness") != 32:
                    raise CommandError(f"Companion process bitness is not 32: {companion}")
            return result
        raise CommandError(last_error or f"Guest command failed: {fixture_id}/u{user}/{command}")

    def initial_suite(self) -> None:
        for fixture_id in FIXTURE_IDS:
            for user in VIRTUAL_USERS:
                for command in COMMANDS:
                    self.invoke_guest(fixture_id, command, user)

    def teardown(self) -> None:
        results: list[dict[str, Any]] = []
        for fixture_id in FIXTURE_IDS:
            for user in VIRTUAL_USERS:
                result = self.invoke_guest(fixture_id, "stop", user)
                results.append(result)
        self.evidence["teardown"] = {
            "status": "PASS",
            "results": results,
            "guestProcessesAfterStop": self.guest_process_snapshot(),
        }

    def stability_loop(self) -> int:
        started = time.monotonic()
        iteration = 0
        samples: list[dict[str, Any]] = []
        stability_log = self.evidence_dir / "stability-logcat.txt"
        while time.monotonic() - started < self.stability_seconds:
            logcat = self.adb("logcat", "-d", "-v", "threadtime", check=False, timeout=180).stdout
            with stability_log.open("a", encoding="utf-8") as stream:
                stream.write(f"=== sample {iteration} {utc_now()} ===\n{logcat}\n")
            findings = fatal_findings(logcat)
            if findings:
                raise CommandError(f"target FATAL/ANR during formal stability: {findings}")
            processes = self.guest_process_snapshot()
            samples.append(
                {
                    "timestampUtc": utc_now(),
                    "iteration": iteration,
                    "guestProcesses": processes,
                    "trackedSessions": list(self.active_guest_sessions.values()),
                }
            )
            self.evidence["stabilitySamples"] = samples
            iteration += 1
            remaining = self.stability_seconds - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(min(60, remaining))
        return int(time.monotonic() - started)

    def collect(self) -> None:
        collections: list[tuple[str, tuple[str, ...]]] = [
            ("processes.txt", ("shell", "ps", "-A")),
            ("activities.txt", ("shell", "dumpsys", "activity", "activities")),
            ("services-host.txt", ("shell", "dumpsys", "activity", "services", self.packages["host"])),
            ("meminfo-host.txt", ("shell", "dumpsys", "meminfo", self.packages["host"])),
            ("meminfo-companion32.txt", ("shell", "dumpsys", "meminfo", self.packages["companion32"])),
        ]
        for key, package in self.packages.items():
            collections.append((f"package-{key}.txt", ("shell", "dumpsys", "package", package)))
        for name, args in collections:
            result = self.adb(*args, check=False, timeout=120)
            (self.evidence_dir / name).write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")

        host = self.packages["host"]
        diagnostics = self.adb(
            "shell", "run-as", host, "find", "files/runtime-diagnostics", "-maxdepth", "1",
            "-type", "f", "-print", check=False, timeout=60,
        ).stdout.splitlines()
        diagnostic_dir = self.evidence_dir / "runtime-diagnostics"
        diagnostic_dir.mkdir(exist_ok=True)
        copied: list[dict[str, Any]] = []
        for index, raw in enumerate(diagnostics):
            remote = raw.strip()
            if not remote:
                continue
            content = self.adb("shell", "run-as", host, "cat", remote, check=False, timeout=60).stdout
            local = diagnostic_dir / f"{index:03d}-{Path(remote).name}"
            local.write_text(content, encoding="utf-8")
            copied.append({"remote": remote, "file": local.name, "sha256": sha256(local), "size": local.stat().st_size})
        self.evidence["runtimeDiagnostics"] = copied

        logcat = self.adb("logcat", "-d", "-v", "threadtime", check=False, timeout=180).stdout
        log_path = self.evidence_dir / "logcat.txt"
        log_path.write_text(logcat, encoding="utf-8")
        self.evidence["logcatSha256"] = sha256(log_path)
        history = ""
        if self.logcat_history_path.is_file():
            history = self.logcat_history_path.read_text(encoding="utf-8", errors="replace")
        combined_logcat = history + "\n=== final logcat window ===\n" + logcat
        self.evidence["logcatHistorySha256"] = sha256(self.logcat_history_path) if self.logcat_history_path.is_file() else ""
        self.evidence["fatalFindings"] = fatal_findings(combined_logcat)
        self.evidence["environmentNoise"] = environment_noise_findings(combined_logcat)

        process_text = (self.evidence_dir / "processes.txt").read_text(encoding="utf-8", errors="replace")
        process_name = self.packages["companion32"] + ":sandbox_server32"
        pid = ""
        for line in process_text.splitlines():
            if process_name in line:
                fields = line.split()
                if len(fields) >= 2 and fields[1].isdigit():
                    pid = fields[1]
                    break
        if not pid:
            pid = self.adb("shell", "pidof", process_name, check=False, timeout=30).stdout.strip().split(" ")[0]
        probes = [
            item.get("companion") for item in self.command_results
            if item.get("fixtureId") == "fixture32" and isinstance(item.get("companion"), dict)
            and item["companion"].get("successful") is True
        ]
        self.evidence["companionEvidence"] = {
            "processName": process_name,
            "pid": pid,
            "processBitness": 32 if probes and all(item.get("processBitness") == 32 for item in probes) else 0,
            "successfulProbeCount": len(probes),
            "requestedAbis": sorted({str(item.get("requestedAbi")) for item in probes}),
            "nativeStatuses": sorted({str(item.get("nativeStatus")) for item in probes}),
        }

    def finish(self) -> dict[str, Any]:
        self.evidence["endedAtUtc"] = utc_now()
        self.evidence["elapsedSeconds"] = int(time.monotonic() - self.started_monotonic)
        if self.diagnostic:
            candidate_status = "DIAGNOSTIC_PASS" if not self.evidence["fatalFindings"] else "FAIL"
            self.evidence["status"] = candidate_status
            formal_candidate = dict(self.evidence)
            formal_candidate["status"] = "PASS"
            formal_candidate["formal"] = True
            errors = validate_formal_evidence(
                formal_candidate, self.lock, str(self.evidence.get("commit", ""))
            )
            self.evidence["formalGateErrors"] = errors
        else:
            self.evidence["status"] = "PASS"
            errors = validate_formal_evidence(
                self.evidence, self.lock, str(self.evidence.get("commit", ""))
            )
            if errors:
                self.evidence["status"] = "FAIL"
            self.evidence["formalGateErrors"] = errors
        path = self.evidence_dir / "device-lab-result.json"
        path.write_text(json.dumps(self.evidence, indent=2) + "\n", encoding="utf-8")
        if self.evidence["status"] == "FAIL":
            raise CommandError("M5 device-lab evidence failed: " + "; ".join(errors or self.evidence["fatalFindings"]))
        return self.evidence

    def run(self) -> dict[str, Any]:
        manifest, artifact_dir = self.prepare_artifacts()
        self.refresh_mumu_resolution()
        self.ensure_device()
        self.evidence["deviceRunCount"] = 1
        self.evidence["device"] = self.device_info()
        self.install_artifacts(manifest, artifact_dir)
        self.initial_suite()
        self.establish_simultaneous_guest_slots()
        observed = self.stability_loop()
        self.evidence["observedStabilitySeconds"] = observed
        self.teardown()
        self.collect()
        return self.finish()

    def run_short_gate(self, loops: int) -> dict[str, Any]:
        """Run the bounded post-fix lifecycle gate without entering the formal stability gate."""
        if loops < 1:
            raise CommandError("short gate loop count must be positive")
        manifest, artifact_dir = self.prepare_artifacts()
        self.refresh_mumu_resolution()
        self.ensure_device()
        self.evidence["deviceRunCount"] = 1
        self.evidence["device"] = self.device_info()
        self.install_artifacts(manifest, artifact_dir)
        rounds: list[dict[str, Any]] = []
        for round_number in range(1, loops + 1):
            round_results: list[dict[str, Any]] = []
            for fixture_id in FIXTURE_IDS:
                for user in VIRTUAL_USERS:
                    if round_number == 1:
                        round_results.append(self.invoke_guest(fixture_id, "import-prepare", user))
                    round_results.append(self.invoke_guest(fixture_id, "component-suite", user))
                    round_results.append(self.invoke_guest(fixture_id, "launch", user))
                    # Launching the other virtual user and returning through the command
                    # Activity is an actual host task switch, not a branch that disables it.
                    self.adb("shell", "input", "keyevent", "3", check=False, timeout=30)
                    round_results.append(self.invoke_guest(fixture_id, "launch", user))
            snapshot = self.guest_process_snapshot()
            logcat = self.adb("logcat", "-d", "-v", "threadtime", check=False, timeout=180).stdout
            findings = fatal_findings(logcat)
            if findings:
                raise CommandError(f"target FATAL/ANR during short gate round {round_number}: {findings}")
            rounds.append({
                "round": round_number,
                "commandCount": len(round_results),
                "guestProcesses": snapshot,
                "resolvedSerial": self.serial,
                "mumuResolution": self.mumu_resolution,
            })
            self.evidence["shortGate"] = {
                "requestedLoops": loops,
                "completedLoops": round_number,
                "fatalFindings": [],
                "rounds": rounds,
            }
        self.capture_simultaneous_snapshot()
        self.teardown()
        self.collect()
        return self.finish()

    def close(self) -> None:
        if self.started_emulator is not None and not self.keep_emulator and self.serial:
            self.adb("emu", "kill", check=False, timeout=30)
            try:
                self.started_emulator.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.started_emulator.terminate()


def current_commit(root: Path) -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--android-sdk", type=Path, default=None)
    parser.add_argument("--serial", default="")
    parser.add_argument("--mumu-instance-name", default="")
    parser.add_argument("--mumu-root", type=Path)
    parser.add_argument("--artifact-dir", type=Path)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--stability-seconds", type=int)
    parser.add_argument("--diagnostic", action="store_true", help="allow a run shorter than the formal 20-minute gate")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--online-build", action="store_true")
    parser.add_argument("--keep-emulator", action="store_true")
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--short-loops", type=int, default=0,
                        help="run the bounded lifecycle gate instead of formal stability")
    parser.add_argument("--verify-evidence", type=Path, help="validate an existing formal evidence JSON and exit")
    args = parser.parse_args()

    root = args.root.resolve()
    lock = load_json(root / "build-environment.lock.json")
    if args.verify_evidence:
        evidence = load_json(args.verify_evidence.resolve())
        errors = validate_formal_evidence(evidence, lock, current_commit(root))
        if errors:
            for error in errors:
                print(" - " + error, file=sys.stderr)
            return 1
        print("PASS M5-T5 formal device evidence")
        return 0

    sdk_text = str(args.android_sdk) if args.android_sdk else (
        os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or ""
    )
    if not sdk_text.strip():
        parser.error("--android-sdk or ANDROID_SDK_ROOT/ANDROID_HOME is required")
    sdk_value = Path(sdk_text)
    if args.mumu_instance_name and args.serial:
        parser.error("--mumu-instance-name and --serial are mutually exclusive")
    mumu_resolution: dict[str, Any] | None = None
    if args.mumu_instance_name:
        try:
            mumu_resolution = resolve_instance(
                args.mumu_instance_name,
                mumu_root=args.mumu_root,
                adb=sdk_value / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"),
            )
        except ResolutionError as error:
            parser.error(str(error))
        resolved_serial = str(mumu_resolution.get("resolvedSerial", ""))
        if not resolved_serial or mumu_resolution.get("runtimeStatus") != "device":
            parser.error("resolved MuMu instance is not an online adb device")
        args.serial = resolved_serial
    stability = args.stability_seconds
    if stability is None:
        stability = int(lock["deviceLab"]["formalStabilitySeconds"])
    if stability < 0:
        parser.error("--stability-seconds must be non-negative")
    formal_minimum = int(lock["deviceLab"]["formalStabilitySeconds"])
    if stability < formal_minimum and not args.diagnostic:
        parser.error(f"runs shorter than {formal_minimum}s require --diagnostic")
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    evidence_dir = (args.evidence_dir or root / "artifacts" / f"m5-device-lab-{stamp}").resolve()
    evidence_dir.mkdir(parents=True, exist_ok=False)

    lab = DeviceLab(
        root=root,
        sdk_root=sdk_value.resolve(),
        evidence_dir=evidence_dir,
        serial=args.serial,
        artifact_dir=args.artifact_dir.resolve() if args.artifact_dir else None,
        stability_seconds=stability,
        diagnostic=args.diagnostic,
        skip_build=args.skip_build,
        online_build=args.online_build,
        keep_emulator=args.keep_emulator,
        headless=args.headless,
        mumu_resolution=mumu_resolution,
        mumu_instance_name=args.mumu_instance_name,
        mumu_root=args.mumu_root,
    )
    try:
        result = lab.run_short_gate(args.short_loops) if args.short_loops else lab.run()
        print(f"{result['status']} M5 device lab: {evidence_dir}")
        return 0
    except Exception as error:
        lab.evidence["status"] = "FAIL"
        lab.evidence["endedAtUtc"] = utc_now()
        lab.evidence["failureType"] = error.__class__.__name__
        lab.evidence["failureMessage"] = str(error)
        (evidence_dir / "device-lab-result.json").write_text(
            json.dumps(lab.evidence, indent=2) + "\n", encoding="utf-8"
        )
        print(f"FAIL M5 device lab: {error}; evidence: {evidence_dir}", file=sys.stderr)
        return 1
    finally:
        lab.close()


if __name__ == "__main__":
    raise SystemExit(main())
