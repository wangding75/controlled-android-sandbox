"""MuMu instance resolution and complete per-run device metadata."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path
from typing import Any, Iterable

from .adb import AdbDevice


ROOT = Path(__file__).resolve().parents[3]
REQUIRED_PROPERTIES = {
    "api_level": "ro.build.version.sdk",
    "android_version": "ro.build.version.release",
    "abi": "ro.product.cpu.abi",
    "abi_list": "ro.product.cpu.abilist",
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "build_fingerprint": "ro.build.fingerprint",
}


class DeviceMetadataError(RuntimeError):
    """The requested named device could not be resolved or fully described."""


def resolve_rd_device(instance_name: str = "RD测试", *, root: Path | None = None) -> tuple[dict[str, Any], AdbDevice]:
    """Reuse the repository MuMu resolver; return its session serial as an ADB object."""

    repo_root = (root or ROOT).resolve()
    scripts = repo_root / "scripts"
    if str(scripts) not in sys.path:
        sys.path.insert(0, str(scripts))
    try:
        import mumu_instance

        resolved = mumu_instance.resolve_instance(instance_name)
    except Exception as exc:  # noqa: BLE001 - expose a stable environment failure to the runner
        raise DeviceMetadataError(
            f"RD_ENVIRONMENT_RESOLUTION_BLOCKED: {exc}"
        ) from exc
    serial = str(resolved.get("resolvedSerial") or "").strip()
    if not serial:
        raise DeviceMetadataError("RD_ENVIRONMENT_RESOLUTION_BLOCKED: resolver returned no serial")
    return resolved, AdbDevice(serial, root=repo_root)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_device_metadata(
    device: AdbDevice,
    *,
    instance_name: str,
    resolver_snapshot: dict[str, Any] | None = None,
    cas_commit: str = "",
    apk_paths: Iterable[Path] = (),
) -> dict[str, Any]:
    """Capture raw command outputs and normalized fields for every run."""

    raw: dict[str, Any] = {}
    values: dict[str, str] = {}
    for field, prop in REQUIRED_PROPERTIES.items():
        result = device.shell(["getprop", prop], timeout_sec=30.0)
        raw[f"getprop {prop}"] = {
            "returncode": result.returncode,
            "stdout": result.text(),
            "stderr": result.text("stderr"),
        }
        values[field] = result.text().strip()

    page = device.shell(["getconf", "PAGE_SIZE"], timeout_sec=30.0)
    raw["getconf PAGE_SIZE"] = {
        "returncode": page.returncode,
        "stdout": page.text(),
        "stderr": page.text("stderr"),
    }
    kernel = device.shell(["uname", "-a"], timeout_sec=30.0)
    raw["uname -a"] = {
        "returncode": kernel.returncode,
        "stdout": kernel.text(),
        "stderr": kernel.text("stderr"),
    }
    state = device.run(["get-state"], timeout_sec=30.0)
    raw["adb get-state"] = {
        "returncode": state.returncode,
        "stdout": state.text(),
        "stderr": state.text("stderr"),
    }
    boot = device.shell(["cat", "/proc/sys/kernel/random/boot_id"], timeout_sec=30.0)
    raw["cat /proc/sys/kernel/random/boot_id"] = {
        "returncode": boot.returncode,
        "stdout": boot.text(),
        "stderr": boot.text("stderr"),
    }

    try:
        page_size: int | None = int(page.text().strip())
    except ValueError:
        page_size = None
    abi_list = [item for item in values["abi_list"].replace(",", " ").split() if item]
    apk_hashes = {
        str(path): sha256_file(path) if path.is_file() else ""
        for path in apk_paths
    }
    metadata = {
        "instance_name": instance_name,
        "serial": device.serial,
        "adb_serial": device.serial,
        "manufacturer": values["manufacturer"],
        "model": values["model"],
        "api_level": _int_or_none(values["api_level"]),
        "android_version": values["android_version"],
        "abi": values["abi"],
        "abi_list": abi_list,
        "page_size": page_size,
        "build_fingerprint": values["build_fingerprint"],
        "fingerprint": values["build_fingerprint"],
        "kernel": kernel.text().strip(),
        "boot_id": boot.text().strip(),
        "adb_state": state.text().strip(),
        "cas_commit": cas_commit,
        "apk_hashes": apk_hashes,
        "resolver": resolver_snapshot or {},
        "raw_commands": raw,
    }
    missing = [
        field for field in (*REQUIRED_PROPERTIES.keys(), "page_size", "kernel", "boot_id")
        if not metadata.get(field)
    ]
    if metadata["adb_state"] != "device":
        missing.append("adb_state")
    metadata["metadata_complete"] = not missing
    metadata["missing_fields"] = missing
    return metadata


def _int_or_none(value: str) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
