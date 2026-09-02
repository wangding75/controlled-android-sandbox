"""Strict models for the C6-T01A testcase contract.

The model deliberately keeps result state and failure classification separate.  A
classification is a diagnosis of a failed testcase; it is never a replacement
for the testcase result.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable


class ResultState(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    SKIP = "SKIP"
    BLOCKED_ENV = "BLOCKED_ENV"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"


class FailureClass(str, Enum):
    PRODUCT_DEFECT = "PRODUCT_DEFECT"
    HARNESS_DEFECT = "HARNESS_DEFECT"
    ENVIRONMENT = "ENVIRONMENT"
    EXPECTED_LIMITATION = "EXPECTED_LIMITATION"
    UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"


RESULT_STATES = frozenset(item.value for item in ResultState)
FAILURE_CLASSES = frozenset(item.value for item in FailureClass)

FORBIDDEN_RESULT_WORDS = frozenset(
    {
        "SUCCESS_WITH_WARNING",
        "ASSUME_PASS",
        "SOFT_PASS",
        "MARKER_PASS",
    }
)


class ContractError(ValueError):
    """Raised when a testcase cannot be serialized as the strict contract."""


@dataclass(frozen=True)
class TestcaseSpec:
    testcase_id: str
    capability: str
    description: str
    virtual_user: int = 0
    guest_package: str = ""
    precondition: str = ""
    operation: str = ""
    expected: Any = field(default_factory=dict)
    timeout_kind: str = "recovery"
    diagnostic_retry: bool = True


@dataclass
class AttemptResult:
    attempt: int
    result: ResultState
    actual: Any = field(default_factory=dict)
    failure_class: FailureClass | None = None
    failure_signature: str = ""
    duration_ms: int = 0
    artifacts: list[str] = field(default_factory=list)
    retry_reason: str = ""

    def __post_init__(self) -> None:
        if not isinstance(self.result, ResultState):
            self.result = ResultState(str(self.result))
        if self.failure_class is not None and not isinstance(self.failure_class, FailureClass):
            self.failure_class = FailureClass(str(self.failure_class))
        if self.result is ResultState.FAIL:
            if self.failure_class is None:
                raise ContractError("FAIL attempt requires a failure_class")
        elif self.failure_class is not None:
            raise ContractError("failure classification is only valid for FAIL attempts")
        if self.duration_ms < 0:
            raise ContractError("duration_ms cannot be negative")

    def to_dict(self) -> dict[str, Any]:
        return {
            "attempt": self.attempt,
            "result": self.result.value,
            "actual": self.actual,
            "failure_class": self.failure_class.value if self.failure_class else None,
            "failure_signature": self.failure_signature,
            "duration_ms": self.duration_ms,
            "artifacts": list(self.artifacts),
            "retry_reason": self.retry_reason,
        }


@dataclass
class Testcase:
    """One auditable testcase, including first attempt and diagnostic retry."""

    spec: TestcaseSpec
    device: dict[str, Any]
    api_level: int | None
    abi: str
    page_size: int | None
    package_revision: str = ""
    attempts: list[AttemptResult] = field(default_factory=list)

    def add_attempt(self, attempt: AttemptResult) -> None:
        expected_number = len(self.attempts) + 1
        if attempt.attempt != expected_number:
            raise ContractError(
                f"attempt numbering must be contiguous: expected {expected_number}, "
                f"got {attempt.attempt}"
            )
        self.attempts.append(attempt)

    @property
    def first_attempt(self) -> AttemptResult | None:
        return self.attempts[0] if self.attempts else None

    @property
    def retry_attempt(self) -> AttemptResult | None:
        return self.attempts[1] if len(self.attempts) > 1 else None

    @property
    def result(self) -> ResultState:
        if not self.first_attempt:
            return ResultState.BLOCKED_ENV
        # Fail-closed: a retry can add diagnosis but cannot erase any first-attempt
        # non-PASS.  This is particularly important for a real PRODUCT_DEFECT.
        return self.first_attempt.result

    @property
    def failure_class(self) -> FailureClass | None:
        return self.final_classification

    @property
    def final_classification(self) -> FailureClass | None:
        first = self.first_attempt
        if not first or first.result is not ResultState.FAIL:
            return None
        classifications = [
            attempt.failure_class for attempt in self.attempts if attempt.failure_class is not None
        ]
        # Preserve a first-attempt harness diagnosis, and preserve a first-attempt
        # product diagnosis even when a later diagnostic attempt is environmental.
        # If the first attempt was environmental but the retry supplies concrete
        # product evidence, retain that stronger final diagnosis without changing
        # the testcase result from FAIL.
        if first.failure_class is FailureClass.HARNESS_DEFECT:
            return FailureClass.HARNESS_DEFECT
        if first.failure_class is FailureClass.PRODUCT_DEFECT:
            return FailureClass.PRODUCT_DEFECT
        for preferred in (
            FailureClass.HARNESS_DEFECT,
            FailureClass.PRODUCT_DEFECT,
            FailureClass.UNSUPPORTED_PLATFORM,
            FailureClass.ENVIRONMENT,
            FailureClass.EXPECTED_LIMITATION,
        ):
            if preferred in classifications:
                return preferred
        return first.failure_class

    @property
    def duration_ms(self) -> int:
        return sum(item.duration_ms for item in self.attempts)

    @property
    def actual(self) -> Any:
        if not self.attempts:
            return {}
        if len(self.attempts) == 1:
            return self.attempts[0].actual
        return {
            "first_attempt": self.attempts[0].actual,
            "retry_attempt": self.attempts[1].actual,
        }

    @property
    def artifacts(self) -> list[str]:
        output: list[str] = []
        for attempt in self.attempts:
            for item in attempt.artifacts:
                if item not in output:
                    output.append(item)
        return output

    def to_dict(self) -> dict[str, Any]:
        first = self.first_attempt.to_dict() if self.first_attempt else None
        retry = self.retry_attempt.to_dict() if self.retry_attempt else None
        payload = {
            "testcase_id": self.spec.testcase_id,
            "capability": self.spec.capability,
            "description": self.spec.description,
            "device": self.device,
            "api_level": self.api_level,
            "abi": self.abi,
            "page_size": self.page_size,
            "virtual_user": self.spec.virtual_user,
            "guest_package": self.spec.guest_package,
            "package_revision": self.package_revision,
            "precondition": self.spec.precondition,
            "operation": self.spec.operation,
            "expected": self.spec.expected,
            "actual": self.actual,
            "result": self.result.value,
            "failure_class": self.failure_class.value if self.failure_class else None,
            "duration_ms": self.duration_ms,
            "artifacts": self.artifacts,
            "first_attempt": first,
            "retry_attempt": retry,
            "final_classification": (
                self.final_classification.value if self.final_classification else None
            ),
        }
        validate_testcase_payload(payload)
        return payload


def validate_testcase_payload(payload: dict[str, Any]) -> None:
    """Validate required fields without requiring a third-party JSON-schema package."""

    required = {
        "testcase_id",
        "capability",
        "description",
        "device",
        "api_level",
        "abi",
        "page_size",
        "virtual_user",
        "guest_package",
        "package_revision",
        "precondition",
        "operation",
        "expected",
        "actual",
        "result",
        "failure_class",
        "duration_ms",
        "artifacts",
        "first_attempt",
        "retry_attempt",
        "final_classification",
    }
    missing = sorted(required - payload.keys())
    if missing:
        raise ContractError(f"missing testcase contract fields: {missing}")
    result = payload["result"]
    if result in FORBIDDEN_RESULT_WORDS or result not in RESULT_STATES:
        raise ContractError(f"illegal testcase result: {result!r}")
    failure_class = payload["failure_class"]
    if result == ResultState.FAIL.value:
        if failure_class not in FAILURE_CLASSES:
            raise ContractError("FAIL testcase requires a valid failure_class")
        if payload["final_classification"] != failure_class:
            raise ContractError("final_classification must match the final testcase diagnosis")
    elif failure_class is not None or payload["final_classification"] is not None:
        raise ContractError("classification must be null unless result is FAIL")
    if not isinstance(payload["artifacts"], list):
        raise ContractError("artifacts must be an array")
    if not isinstance(payload["duration_ms"], int) or payload["duration_ms"] < 0:
        raise ContractError("duration_ms must be a non-negative integer")


Executor = Callable[[Any, Any, int], AttemptResult]
