"""Bounded timeout and retry policy shared by every capability testcase."""

from __future__ import annotations

import threading
from dataclasses import dataclass
from enum import Enum
from typing import Callable, TypeVar


class TimeoutKind(str, Enum):
    INSTALL = "install"
    ADD_IMPORT = "add_import"
    COLD_LAUNCH = "cold_launch"
    WARM_LAUNCH = "warm_launch"
    FIRST_FRAME = "first_frame"
    PROCESS_DEATH = "process_death"
    RECOVERY = "recovery"


@dataclass(frozen=True)
class TimeoutPolicy:
    """Seconds for the distinct operations required by the acceptance contract."""

    install: float = 120.0
    add_import: float = 240.0
    cold_launch: float = 45.0
    warm_launch: float = 20.0
    first_frame: float = 35.0
    process_death: float = 30.0
    recovery: float = 120.0

    def seconds(self, kind: TimeoutKind | str) -> float:
        normalized = kind.value if isinstance(kind, TimeoutKind) else str(kind)
        try:
            value = getattr(self, normalized)
        except AttributeError as exc:
            raise ValueError(f"unknown timeout kind: {kind!r}") from exc
        if value <= 0:
            raise ValueError(f"timeout for {normalized} must be positive")
        return float(value)


@dataclass(frozen=True)
class RetryPolicy:
    """At most one explicitly labelled diagnostic retry by default."""

    max_diagnostic_retries: int = 1

    def __post_init__(self) -> None:
        if self.max_diagnostic_retries < 0:
            raise ValueError("max_diagnostic_retries cannot be negative")

    def can_retry(self, completed_retries: int) -> bool:
        return completed_retries < self.max_diagnostic_retries


class HarnessTimeout(TimeoutError):
    def __init__(self, kind: TimeoutKind | str, timeout_seconds: float) -> None:
        self.kind = kind.value if isinstance(kind, TimeoutKind) else str(kind)
        self.timeout_seconds = float(timeout_seconds)
        super().__init__(f"{self.kind} timeout after {self.timeout_seconds:g}s")


T = TypeVar("T")


def run_bounded(
    operation: Callable[[], T],
    kind: TimeoutKind | str,
    policy: TimeoutPolicy | None = None,
) -> T:
    """Run a small local operation with a daemon worker and a hard join deadline."""

    selected = policy or TimeoutPolicy()
    timeout = selected.seconds(kind)
    result: list[T] = []
    error: list[BaseException] = []

    def invoke() -> None:
        try:
            result.append(operation())
        except BaseException as exc:  # propagate the original failure to the caller
            error.append(exc)

    thread = threading.Thread(target=invoke, name=f"verification-{kind}", daemon=True)
    thread.start()
    thread.join(timeout)
    if thread.is_alive():
        raise HarnessTimeout(kind, timeout)
    if error:
        raise error[0]
    return result[0] if result else None  # type: ignore[return-value]
