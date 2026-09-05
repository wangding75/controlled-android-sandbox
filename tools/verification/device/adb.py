"""Small, reusable ADB abstraction.

The serial is always supplied by the caller after instance-name resolution.  No
MuMu endpoint is embedded here.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


class AdbError(RuntimeError):
    """A device command returned a non-zero exit code."""


class AdbTimeoutError(TimeoutError):
    """ADB did not complete within its bounded command timeout."""


@dataclass(frozen=True)
class AdbCommandResult:
    command: list[str]
    returncode: int
    stdout: str | bytes
    stderr: str | bytes
    duration_ms: int
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out

    def text(self, stream: str = "stdout") -> str:
        value = getattr(self, stream)
        if isinstance(value, bytes):
            return value.decode("utf-8", errors="replace")
        return value


class AdbDevice:
    def __init__(self, serial: str, *, adb: str | None = None, root: Path | None = None) -> None:
        if not serial or not serial.strip():
            raise ValueError("ADB serial is required")
        self.serial = serial.strip()
        self.adb = adb or os.environ.get("ADB", "adb")
        self.root = (root or Path(__file__).resolve().parents[3]).resolve()
        self._api_level_cache: int | None = None

    def run(
        self,
        args: Sequence[str],
        *,
        timeout_sec: float = 60.0,
        binary: bool = False,
        check: bool = False,
    ) -> AdbCommandResult:
        command = [self.adb, "-s", self.serial, *[str(item) for item in args]]
        started = time.monotonic()
        try:
            # Keep one repository ADB implementation: the new device façade
            # delegates process execution to scripts/mumu_instance.py while
            # adding typed operations and evidence-friendly results.
            scripts = self.root / "scripts"
            if str(scripts) not in sys.path:
                sys.path.insert(0, str(scripts))
            import mumu_instance

            completed = mumu_instance.run_adb(
                Path(self.adb),
                ["-s", self.serial, *[str(item) for item in args]],
                check=False,
                timeout=timeout_sec,
                text=not binary,
            )
            result = AdbCommandResult(
                command=command,
                returncode=completed.returncode,
                stdout=completed.stdout,
                stderr=completed.stderr,
                duration_ms=max(0, int(round((time.monotonic() - started) * 1000))),
            )
        except subprocess.TimeoutExpired as exc:
            result = AdbCommandResult(
                command=command,
                returncode=124,
                stdout=exc.stdout or (b"" if binary else ""),
                stderr=exc.stderr or (b"" if binary else ""),
                duration_ms=max(0, int(round((time.monotonic() - started) * 1000))),
                timed_out=True,
            )
        if check and not result.ok:
            detail = result.text("stderr").strip() or result.text("stdout").strip()
            if result.timed_out:
                raise AdbTimeoutError(
                    f"ADB_COMMAND_TIMEOUT after {timeout_sec:g}s: {' '.join(command)}"
                )
            raise AdbError(
                f"ADB_COMMAND_FAILED({result.returncode}): {' '.join(command)}: {detail}"
            )
        return result

    def shell(
        self,
        args: Sequence[str],
        *,
        timeout_sec: float = 60.0,
        check: bool = False,
    ) -> AdbCommandResult:
        return self.run(["shell", *[str(item) for item in args]], timeout_sec=timeout_sec, check=check)

    def shell_text(self, args: Sequence[str], *, timeout_sec: float = 60.0, check: bool = False) -> str:
        return self.shell(args, timeout_sec=timeout_sec, check=check).text().strip()

    def install(self, apk: Path, *, timeout_sec: float = 120.0) -> AdbCommandResult:
        if not apk.is_file():
            raise FileNotFoundError(apk)
        # Android 17's current Google APIs x86_64 emulator has a broken streamed-install
        # path: ``adb install`` reaches StorageManagerService.allocateBytes() with a null
        # PackageManagerInternal and returns a Broken pipe/NPE.  The platform package manager
        # itself is healthy, and an explicit push + ``pm install`` is the stable device-local
        # transport.  Keep the ordinary adb path for API32-36 so this is an API37 environment
        # adapter, not a test retry or a relaxed install assertion.
        if self._uses_api37_package_manager_transport():
            return self._install_via_package_manager([apk], timeout_sec=timeout_sec)
        return self.run(["install", "-r", str(apk)], timeout_sec=timeout_sec)

    def install_multiple(self, apks: Sequence[Path], *, timeout_sec: float = 120.0) -> AdbCommandResult:
        paths = list(apks)
        if not paths:
            raise ValueError("at least one APK is required")
        for apk in paths:
            if not apk.is_file():
                raise FileNotFoundError(apk)
        if self._uses_api37_package_manager_transport():
            return self._install_via_package_manager(paths, timeout_sec=timeout_sec)
        return self.run(
            ["install-multiple", "-r", *[str(apk) for apk in paths]],
            timeout_sec=timeout_sec,
        )

    def _uses_api37_package_manager_transport(self) -> bool:
        if self._api_level_cache is None:
            result = self.shell(["getprop", "ro.build.version.sdk"], timeout_sec=15.0)
            try:
                self._api_level_cache = int(result.text().strip()) if result.ok else -1
            except ValueError:
                self._api_level_cache = -1
        return self._api_level_cache >= 37

    def _install_via_package_manager(
        self, apks: Sequence[Path], *, timeout_sec: float
    ) -> AdbCommandResult:
        remote_paths: list[str] = []
        try:
            for index, apk in enumerate(apks):
                safe_name = "".join(
                    character if character.isalnum() or character in "._-" else "_"
                    for character in apk.name
                )
                remote = f"/data/local/tmp/cas-verify-{os.getpid()}-{index}-{safe_name}"
                remote_paths.append(remote)
                pushed = self.run(["push", str(apk), remote], timeout_sec=timeout_sec)
                if not pushed.ok:
                    return pushed

            command = ["shell", "pm"]
            command.append("install-multi-package" if len(remote_paths) > 1 else "install")
            command.extend(["-r", *remote_paths])
            result = self.run(command, timeout_sec=timeout_sec)
            if result.ok and self._uses_api37_package_manager_transport():
                # Android 17's PackageInstaller publishes the completed session and updates
                # package-manager state asynchronously.  A following install in the same
                # verification setup can otherwise observe the service during that handoff and
                # receive a Broken pipe even though the preceding transaction succeeded.  This
                # is a bounded transport settle, not a testcase retry or a changed PASS gate.
                time.sleep(2.0)
            return result
        finally:
            for remote in remote_paths:
                self.shell(["rm", "-f", remote], timeout_sec=30.0)

    def uninstall(self, package: str, *, timeout_sec: float = 60.0) -> AdbCommandResult:
        return self.run(["uninstall", package], timeout_sec=timeout_sec)

    def force_stop(self, package: str) -> AdbCommandResult:
        return self.shell(["am", "force-stop", package], timeout_sec=30.0)

    def wait_for_package_stopped(self, package: str, timeout_sec: float = 15.0) -> bool:
        """Wait until the exact base package PID disappears from the device."""
        if not package or not package.strip():
            raise ValueError("package is required")
        deadline = time.monotonic() + max(0.0, timeout_sec)
        while True:
            probe = self.shell(["pidof", package.strip()], timeout_sec=30.0)
            if not probe.timed_out and not probe.text().strip():
                return True
            remaining = deadline - time.monotonic()
            if remaining <= 0.0:
                return False
            time.sleep(min(0.1, remaining))

    def start_activity(
        self,
        component: str,
        *,
        extras: Sequence[tuple[str, str, str]] = (),
        flags: str = "0x10008000",
        timeout_sec: float = 30.0,
    ) -> AdbCommandResult:
        args = ["shell", "am", "start", "-W", "-f", flags, "-n", component]
        for kind, key, value in extras:
            if kind not in {"es", "ei", "el", "ez"}:
                raise ValueError(f"unsupported am extra kind: {kind}")
            args.extend([f"--{kind}", key, str(value)])
        return self.run(args, timeout_sec=timeout_sec)

    def clear_logcat(self) -> AdbCommandResult:
        return self.run(["logcat", "-c"], timeout_sec=30.0)

    def logcat(self, *, timeout_sec: float = 60.0) -> str:
        return self.run(["logcat", "-d", "-v", "threadtime"], timeout_sec=timeout_sec).text()

    def dump(self, service: str, *args: str, timeout_sec: float = 60.0) -> str:
        return self.shell(["dumpsys", service, *args], timeout_sec=timeout_sec).text()

    def processes(self, *, timeout_sec: float = 30.0) -> str:
        result = self.shell(["ps", "-A"], timeout_sec=timeout_sec)
        if not result.ok:
            result = self.shell(["ps"], timeout_sec=timeout_sec)
        return result.text()

    def pid_exists(self, pid: int, *, timeout_sec: float = 30.0) -> bool:
        needle = str(pid)
        for line in self.processes(timeout_sec=timeout_sec).splitlines():
            fields = line.split()
            if fields and needle in fields[:3]:
                return True
        return False

    def kill_pid(self, pid: int, *, signal: int = 9) -> AdbCommandResult:
        if pid <= 0:
            raise ValueError("pid must be positive")
        return self.shell(["kill", f"-{signal}", str(pid)], timeout_sec=30.0)

    def capture_screenshot(self, path: Path, *, timeout_sec: float = 30.0) -> AdbCommandResult:
        result = self.run(["exec-out", "screencap", "-p"], timeout_sec=timeout_sec, binary=True)
        if result.ok:
            path.parent.mkdir(parents=True, exist_ok=True)
            data = result.stdout if isinstance(result.stdout, bytes) else result.stdout.encode()
            path.write_bytes(data)
        return result

    def run_as_read(self, package: str, relative_path: str) -> str:
        return self.shell(["run-as", package, "cat", relative_path], timeout_sec=30.0).text()

    def run_as_remove(self, package: str, relative_path: str) -> AdbCommandResult:
        return self.shell(["run-as", package, "rm", "-f", relative_path], timeout_sec=30.0)

    def run_as_exists(self, package: str, relative_path: str) -> bool:
        return self.shell(
            ["run-as", package, "test", "-f", relative_path], timeout_sec=30.0
        ).ok

    def wait_for_pid_exit(self, pid: int, timeout_sec: float) -> bool:
        deadline = time.monotonic() + timeout_sec
        while time.monotonic() < deadline:
            if not self.pid_exists(pid):
                return True
            time.sleep(0.25)
        return not self.pid_exists(pid)

    @staticmethod
    def available(adb: str | None = None) -> bool:
        return bool(shutil.which(adb or os.environ.get("ADB", "adb")))
