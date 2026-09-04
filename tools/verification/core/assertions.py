"""Fail-closed assertions used by capability executors."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Sequence

from .models import FailureClass


@dataclass
class VerificationFailure(AssertionError):
    signature: str
    detail: str
    classification: FailureClass = FailureClass.PRODUCT_DEFECT
    artifacts: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        super().__init__(f"{self.signature}: {self.detail}")


def require(
    condition: bool,
    signature: str,
    detail: str,
    *,
    classification: FailureClass = FailureClass.PRODUCT_DEFECT,
    artifacts: Sequence[str] = (),
) -> None:
    if not condition:
        raise VerificationFailure(signature, detail, classification, list(artifacts))


def require_command_pass(
    actual: dict[str, Any],
    expected_operation: str = "",
    *,
    artifacts: Sequence[str] = (),
) -> None:
    artifact_paths = (
        actual.get("artifacts", []) if isinstance(actual, dict) else []
    ) or list(artifacts)
    require(
        isinstance(actual, dict) and actual.get("status") == "PASS",
        "DEBUG_COMMAND_NOT_PASS",
        f"top-level debug command status={actual.get('status') if isinstance(actual, dict) else None!r}",
        artifacts=artifact_paths if isinstance(artifact_paths, list) else (),
    )
    if expected_operation:
        operation = actual.get("operation") if isinstance(actual, dict) else None
        status = operation.get("status") if isinstance(operation, dict) else None
        require(
            status == expected_operation,
            "DEBUG_OPERATION_STATUS_MISMATCH",
            f"expected operation status {expected_operation!r}, got {status!r}",
            artifacts=artifact_paths if isinstance(artifact_paths, list) else (),
        )


def require_launch_readiness(
    actual: dict[str, Any],
    screen: dict[str, Any],
    logcat: str,
    *,
    expected_component: str = "",
    artifacts: Sequence[str] = (),
) -> None:
    """Require the runtime's real first-frame contract, never just process existence."""

    actual_artifacts = actual.get("artifacts", []) if isinstance(actual, dict) else []
    artifact_paths = actual_artifacts if isinstance(actual_artifacts, list) else list(artifacts)
    require_command_pass(actual, "LAUNCH_PASS", artifacts=artifact_paths)
    operation = actual.get("operation") or {}
    required_flags = (
        "activityCreated",
        "activityResumed",
        "windowEvidence",
        "firstFrameDrawn",
    )
    for flag in required_flags:
        require(
            operation.get(flag) is True,
            "LAUNCH_READINESS_MISSING",
            f"{flag}=true is required for a launch PASS",
            artifacts=artifact_paths,
        )
    require(
        int(operation.get("fatalCount", 0) or 0) == 0
        and int(operation.get("anrCount", 0) or 0) == 0,
        "LAUNCH_FATAL_OR_ANR",
        f"fatalCount={operation.get('fatalCount')!r} anrCount={operation.get('anrCount')!r}",
        artifacts=artifact_paths,
    )
    require(
        screen.get("non_black") is True or screen.get("displayed_frame") is True,
        "BLACK_SCREEN_OR_SCREENSHOT_UNAVAILABLE",
        f"screen evidence={screen}",
        artifacts=artifact_paths,
    )
    require(
        "FIRST_FRAME_DRAWN" in logcat,
        "FIRST_FRAME_MARKER_MISSING",
        "logcat does not contain the request's FIRST_FRAME_DRAWN marker",
        artifacts=artifact_paths,
    )
    if expected_component:
        # The structured operation is authoritative for the target.  Logcat is
        # only used as a second witness, not as a substitute for readiness.
        component = operation.get("componentClass") or operation.get("component") or ""
        require(
            component == expected_component or expected_component in logcat,
            "LAUNCH_TARGET_MISMATCH",
            f"expected component {expected_component!r}, operation={component!r}",
            artifacts=artifact_paths,
        )


def require_marker(
    logcat: str,
    marker: str,
    *,
    detail: str = "",
    artifacts: Sequence[str] = (),
) -> None:
    require(
        marker in logcat,
        "CAPABILITY_MARKER_MISSING",
        detail or marker,
        artifacts=artifacts,
    )


def classify_failure_text(text: str, *, harness_error: bool = False) -> FailureClass:
    """Classify a failure after it has already become FAIL.

    This function intentionally never returns a result state and therefore cannot
    turn a failed testcase into PASS.
    """

    normalized = (text or "").upper()
    if harness_error or any(token in normalized for token in (
        "CONTRACTERROR",
        "PARSER_ERROR",
        "ARTIFACT_INDEX_ERROR",
        "HARNESS_DEFECT",
    )):
        return FailureClass.HARNESS_DEFECT
    if any(token in normalized for token in (
        "ADB_NOT_AVAILABLE",
        "ADB_COMMAND_TIMEOUT",
        "DEVICE_OFFLINE",
        "DEVICE_NOT_FOUND",
        "RD_ENVIRONMENT_RESOLUTION_BLOCKED",
        "DEBUG_RESULT_TIMEOUT",
        "BOOT_NOT_COMPLETED",
    )):
        return FailureClass.ENVIRONMENT
    if any(token in normalized for token in (
        "UNSUPPORTED_PLATFORM",
        "UNSUPPORTED_COMMAND",
        "API_NOT_SUPPORTED",
    )):
        return FailureClass.UNSUPPORTED_PLATFORM
    if any(token in normalized for token in (
        "BLACK_SCREEN",
        "FIRST_FRAME",
        "LAUNCH_GATE",
        "FATAL EXCEPTION",
        "ANR",
        "CRASH",
        "SERVICE_",
        "RECEIVER_",
        "PROVIDER_",
        "PENDING_INTENT",
        "PACKAGE_",
        "GUEST_",
        "DEBUG_COMMAND_NOT_PASS",
        "DEBUG_OPERATION_STATUS_MISMATCH",
    )):
        return FailureClass.PRODUCT_DEFECT
    return FailureClass.PRODUCT_DEFECT
