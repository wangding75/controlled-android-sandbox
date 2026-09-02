"""Execution coordinator that preserves first failures and diagnostic retries."""

from __future__ import annotations

import json
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .assertions import VerificationFailure, classify_failure_text
from .models import (
    AttemptResult,
    FailureClass,
    ResultState,
    Testcase,
    TestcaseSpec,
)
from .policy import RetryPolicy


@dataclass
class AttemptExecution:
    result: ResultState
    actual: Any = field(default_factory=dict)
    package_revision: str = ""
    artifacts: list[str] = field(default_factory=list)
    failure_signature: str = ""
    failure_class: FailureClass | None = None


Executor = Callable[[Any, Path, int], AttemptExecution]


def _relative(root: Path, value: Path) -> str:
    try:
        return value.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(value.resolve())


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _exception_execution(error: BaseException) -> AttemptExecution:
    if isinstance(error, VerificationFailure):
        return AttemptExecution(
            result=ResultState.FAIL,
            actual={
                "error_type": error.__class__.__name__,
                "error_message": error.detail,
                "failure_signature": error.signature,
                "artifacts": list(error.artifacts),
            },
            failure_signature=error.signature,
            failure_class=error.classification,
            artifacts=list(error.artifacts),
        )
    text = f"{error.__class__.__name__}: {error}"
    explicit_class = getattr(error, "failure_class", None)
    if explicit_class is not None:
        classification = (
            explicit_class
            if isinstance(explicit_class, FailureClass)
            else FailureClass(str(explicit_class))
        )
    else:
        # Contract/programming errors indicate a harness defect.  Device and
        # subprocess errors carry their own environment signatures and must not
        # be relabeled as harness defects merely because they escaped an
        # executor boundary.
        classification = classify_failure_text(
            text, harness_error=isinstance(error, (TypeError, ValueError, KeyError))
        )
    return AttemptExecution(
        result=ResultState.FAIL,
        actual={
            "error_type": error.__class__.__name__,
            "error_message": str(error),
            "failure_signature": "HARNESS_EXCEPTION",
            "traceback_tail": traceback.format_exc().splitlines()[-8:],
            "artifacts": list(getattr(error, "artifacts", [])),
        },
        failure_signature="HARNESS_EXCEPTION",
        failure_class=classification,
        artifacts=list(getattr(error, "artifacts", [])),
    )


def run_case(
    spec: TestcaseSpec,
    *,
    context: Any,
    output_dir: Path,
    device: dict[str, Any],
    retry_policy: RetryPolicy | None = None,
    executor: Executor,
) -> Testcase:
    """Run one capability testcase and serialize both attempts and final contract."""

    selected_retry = retry_policy or RetryPolicy()
    case_dir = output_dir / "cases" / spec.testcase_id
    case_dir.mkdir(parents=True, exist_ok=True)
    metadata = device or {}
    testcase = Testcase(
        spec=spec,
        device=metadata,
        api_level=_int_or_none(metadata.get("api_level")),
        abi=str(metadata.get("abi") or ""),
        page_size=_int_or_none(metadata.get("page_size")),
    )

    attempt_number = 1
    while True:
        attempt_dir = case_dir / f"attempt-{attempt_number:03d}"
        attempt_dir.mkdir(parents=True, exist_ok=True)
        started = time.monotonic()
        try:
            execution = executor(context, attempt_dir, attempt_number)
            if not isinstance(execution, AttemptExecution):
                raise TypeError("capability executor returned a non-AttemptExecution value")
        except BaseException as error:  # noqa: BLE001 - convert every case failure to evidence
            execution = _exception_execution(error)
        duration_ms = max(0, int(round((time.monotonic() - started) * 1000)))
        failure_class = execution.failure_class
        if execution.result is ResultState.FAIL:
            if failure_class is None:
                failure_class = classify_failure_text(execution.failure_signature)
        else:
            failure_class = None
        artifacts = list(execution.artifacts)
        attempt_payload = {
            "attempt": attempt_number,
            "result": execution.result.value,
            "actual": execution.actual,
            "failure_class": failure_class.value if failure_class else None,
            "failure_signature": execution.failure_signature,
            "duration_ms": duration_ms,
            "retry_reason": "diagnostic" if attempt_number > 1 else "",
            "artifacts": artifacts,
        }
        attempt_path = attempt_dir / "attempt.json"
        attempt_payload["artifacts"] = list(dict.fromkeys(
            artifacts + [_relative(getattr(context, "root", Path.cwd()), attempt_path)]
        ))
        _write_json(attempt_path, attempt_payload)
        attempt = AttemptResult(
            attempt=attempt_number,
            result=execution.result,
            actual=execution.actual,
            failure_class=failure_class,
            failure_signature=execution.failure_signature,
            duration_ms=duration_ms,
            artifacts=attempt_payload["artifacts"],
            retry_reason=attempt_payload["retry_reason"],
        )
        testcase.add_attempt(attempt)
        if execution.package_revision:
            testcase.package_revision = execution.package_revision

        # Only a failed testcase receives the default diagnostic retry.  A
        # BLOCKED_ENV result is retained as a blocked environment observation,
        # not silently converted through an unrelated retry.
        if (
            execution.result is ResultState.FAIL
            and attempt_number == 1
            and selected_retry.can_retry(0)
            and spec.diagnostic_retry
        ):
            attempt_number += 1
            continue
        break

    _write_json(case_dir / "testcase.json", testcase.to_dict())
    return testcase


def _int_or_none(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
