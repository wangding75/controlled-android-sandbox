"""ADB-backed device abstraction for the verification harness."""

from .adb import AdbCommandResult, AdbDevice, AdbError, AdbTimeoutError
from .metadata import DeviceMetadataError, collect_device_metadata, resolve_rd_device

__all__ = [
    "AdbCommandResult",
    "AdbDevice",
    "AdbError",
    "AdbTimeoutError",
    "DeviceMetadataError",
    "collect_device_metadata",
    "resolve_rd_device",
]
