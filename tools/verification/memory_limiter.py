"""Android 17 memory-limiter observability and fail-closed classification helpers.

The device commands in this module are deliberately thin wrappers around the
documented ``am memory-limiter`` shell interface.  A constrained process exit is
still a failed operation until the caller proves cleanup and recovery; the
classifier only labels the cause and never promotes a result to PASS.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from .device.adb import AdbCommandResult, AdbDevice


MEMORY_LIMITER_CLASSIFICATION = "API37_MEMORY_LIMITER"
EXPECTED_PLATFORM_BEHAVIOR = "EXPECTED_PLATFORM_BEHAVIOR"
PRODUCT_CRASH = "PRODUCT_CRASH"
UNKNOWN_EXIT = "UNKNOWN_EXIT"
REASON_OTHER = 13
ANON_SWAP_MARKER = "MemoryLimiter:AnonSwap"


def status(device: AdbDevice) -> AdbCommandResult:
    """Read the current limiter status without changing device state."""

    return device.shell(["am", "memory-limiter", "status"], timeout_sec=30.0)


def manual(device: AdbDevice, pid: int, limit_percent: int | str | None) -> AdbCommandResult:
    """Apply or remove the target image's per-process manual limit.

    The API37 system image used by this campaign exposes
    ``manual <PID> <PERCENT|none>`` through ``am help``.  Keep the value
    device-native instead of converting it to a unit inferred from a different
    Android 17 documentation revision.  ``none`` restores the device default;
    ``max`` is accepted by some revisions and removes the process limit.
    """

    if pid <= 0:
        raise ValueError("pid must be positive")
    if limit_percent is None:
        value = "none"
    elif isinstance(limit_percent, bool):
        raise ValueError("limit_percent must be a positive percentage, 'max', or 'none'")
    elif isinstance(limit_percent, int):
        if limit_percent <= 0 or limit_percent > 100:
            raise ValueError("limit_percent must be in the range 1..100")
        value = str(limit_percent)
    else:
        value = str(limit_percent).strip().lower()
        if value not in {"max", "none"}:
            try:
                parsed = int(value)
            except ValueError as exc:
                raise ValueError(
                    "limit_percent must be a positive percentage, 'max', or 'none'"
                ) from exc
            if parsed <= 0 or parsed > 100:
                raise ValueError("limit_percent must be in the range 1..100")
            value = str(parsed)
    return device.shell(["am", "memory-limiter", "manual", str(pid), value], timeout_sec=30.0)


def ignore(device: AdbDevice, uid_or_scope: str = "none") -> AdbCommandResult:
    """Set the documented limiter ignore scope; ``none`` restores normal behavior."""

    value = str(uid_or_scope).strip()
    if value != "none" and value != "all":
        try:
            if int(value) <= 0:
                raise ValueError
        except ValueError as exc:
            raise ValueError("uid_or_scope must be a positive UID, 'none', or 'all'") from exc
    return device.shell(["am", "memory-limiter", "ignore", value], timeout_sec=30.0)


def exit_info(device: AdbDevice, package: str) -> AdbCommandResult:
    """Read ActivityManager's persisted ApplicationExitInfo history for a package."""

    if not package or not package.strip():
        raise ValueError("package is required")
    return device.shell(["dumpsys", "activity", "exit-info", package.strip()], timeout_sec=30.0)


def parse_exit_info(value: Mapping[str, Any] | str) -> dict[str, Any]:
    """Normalize a mapping or ``dumpsys activity exit-info`` text into exit fields."""

    if isinstance(value, Mapping):
        reason = value.get("reason", value.get("reason_code", ""))
        description = value.get("description", "")
        return {
            "reason": reason,
            "description": "" if description is None else str(description),
            "raw": dict(value),
        }
    text = str(value)
    reason_match = re.search(
        r"(?im)^\s*reason\s*=\s*(?P<code>\d+)\s*(?:\((?P<name>[^)]+)\))?",
        text,
    )
    if reason_match:
        reason: Any = int(reason_match.group("code"))
        if reason_match.group("name"):
            reason = f"{reason} ({reason_match.group('name').strip()})"
    else:
        named = re.search(r"(?im)^\s*reason\s*=\s*(?P<name>[^\r\n]+)", text)
        reason = named.group("name").strip() if named else ""
    description_match = re.search(r"(?im)^\s*description\s*=\s*(?P<value>[^\r\n]*)", text)
    description = description_match.group("value").strip() if description_match else ""
    return {"reason": reason, "description": description, "raw": text}


def _is_reason_other(reason: Any) -> bool:
    if isinstance(reason, bool):
        return False
    if isinstance(reason, int):
        return reason == REASON_OTHER
    normalized = str(reason).strip().upper()
    return bool(
        normalized == "REASON_OTHER"
        or normalized == "OTHER"
        or normalized.startswith("13 (REASON_OTHER")
        or normalized.startswith("13(REASON_OTHER")
    )


def classify_exit(
    exit_info_value: Mapping[str, Any] | str,
    evidence: str = "",
) -> str:
    """Classify the exit cause without changing the caller's operation result."""

    parsed = parse_exit_info(exit_info_value)
    description = parsed["description"]
    raw = parsed["raw"] if isinstance(parsed["raw"], str) else ""
    combined = "\n".join((description, raw, evidence))
    if _is_reason_other(parsed["reason"]) and ANON_SWAP_MARKER in combined:
        return MEMORY_LIMITER_CLASSIFICATION
    reason = str(parsed["reason"]).upper()
    if "CRASH" in reason or "ANR" in reason or "LOW_MEMORY" in reason:
        return PRODUCT_CRASH
    return UNKNOWN_EXIT


def classify_recovery(*, process_died: bool, cleanup_ok: bool, restarted: bool) -> str:
    """Return a cause classification for M05's fail-closed recovery gate."""

    if process_died and cleanup_ok and restarted:
        return EXPECTED_PLATFORM_BEHAVIOR
    return "PRODUCT_DEFECT"


def status_payload(result: AdbCommandResult) -> dict[str, Any]:
    """Return compact status evidence suitable for JSON reports."""

    output = result.text()
    lowered = output.lower()
    return {
        "command": result.command,
        "returncode": result.returncode,
        "ok": result.ok,
        "output": output,
        "enabled": "enabled" in lowered and "disabled" not in lowered,
    }
