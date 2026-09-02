"""Summaries and the single compact C6-T01A report renderer.

Raw command output stays in ``out/verification/<run-id>``.  This module only
copies normalized metadata, result states, classifications and artifact paths
into the report.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

from ..core.models import FailureClass, ResultState


def _case_dict(case: Any) -> dict[str, Any]:
    if hasattr(case, "to_dict"):
        return case.to_dict()
    if isinstance(case, dict):
        return case
    raise TypeError(f"unsupported testcase value: {type(case)!r}")


def build_summary(run: dict[str, Any], cases: Iterable[Any]) -> dict[str, Any]:
    """Build an auditable aggregate without inventing a PASS state."""

    payloads = [_case_dict(case) for case in cases]
    counts = Counter(str(item.get("result", "")) for item in payloads)
    product_defects = [
        item
        for item in payloads
        if item.get("result") == ResultState.FAIL.value
        and item.get("failure_class") == FailureClass.PRODUCT_DEFECT.value
    ]
    blocking = [
        item
        for item in payloads
        if item.get("result") in {
            ResultState.BLOCKED_ENV.value,
            ResultState.UNSUPPORTED_PLATFORM.value,
            ResultState.SKIP.value,
        }
        or (
            item.get("result") == ResultState.FAIL.value
            and item.get("failure_class") != FailureClass.PRODUCT_DEFECT.value
        )
    ]
    if not payloads or blocking:
        overall = "BLOCKED"
    elif product_defects:
        overall = "PASS_WITH_DISCOVERED_PRODUCT_DEFECT"
    elif all(item.get("result") == ResultState.PASS.value for item in payloads):
        overall = "PASS"
    else:
        overall = "BLOCKED"
    return {
        "overall": overall,
        "total": len(payloads),
        "pass": counts[ResultState.PASS.value],
        "fail": counts[ResultState.FAIL.value],
        "skip": counts[ResultState.SKIP.value],
        "blocked_env": counts[ResultState.BLOCKED_ENV.value],
        "unsupported_platform": counts[ResultState.UNSUPPORTED_PLATFORM.value],
        "product_defects": len(product_defects),
        "blocking_cases": len(blocking),
        "failure_classes": dict(
            Counter(
                str(item.get("failure_class"))
                for item in payloads
                if item.get("failure_class") is not None
            )
        ),
        "testcases": payloads,
    }


def render_compact_report(run: dict[str, Any], *, final_head: str | None = None) -> str:
    """Render one compact report; never inline raw logcat or dump contents."""

    summary = run.get("summary") or build_summary(run, run.get("testcases", []))
    device = run.get("device_metadata") or {}
    build = run.get("build") or {}
    hygiene = run.get("git_hygiene") or {}
    effective_final_head = final_head or run.get("final_head", "")
    cases = summary.get("testcases") or run.get("testcases") or []
    lines = [
        "# C6-T01A — Unified Android Verification Harness Foundation",
        "",
        f"- Overall result: `{summary.get('overall', 'BLOCKED')}`",
        f"- START_HEAD: `{run.get('start_head', '')}`",
        f"- FINAL_HEAD (source baseline): `{effective_final_head}`",
        f"- Branch: `{run.get('branch', '')}`",
        "",
        "## Architecture",
        "",
        "Capability-oriented Python harness under `tools/verification/`, split into "
        "strict contract models/policies, reusable ADB device access, real S01–S10 "
        "capabilities, JSON schemas and compact reporting. Existing "
        "`scripts/mumu_instance.py` resolves the MuMu `RD测试` instance; no serial is "
        "embedded in the new source. Generated evidence is isolated under "
        "`out/verification/<run-id>/` and is not source/test data.",
        "",
        "## Implemented files",
        "",
        "- `.gitignore` — ignores generated `out/verification/` evidence.",
        "- `scripts/mumu_instance.py` — exposes the existing resolver's bounded ADB "
        "runner for reuse; instance-name resolution remains the single source of truth.",
        "- `tools/verification/core/` — testcase contract, fail-closed retry policy, "
        "timeout policy and readiness assertions.",
        "- `tools/verification/device/` — ADB wrapper, dynamic device metadata and "
        "non-black PNG inspection.",
        "- `tools/verification/capabilities/smoke.py` — real RD/API32 S01–S10 smoke "
        "operations.",
        "- `tools/verification/reporting/` and `tools/verification/schemas/` — "
        "normalized summary/report and machine-readable contracts.",
        "- `tools/verification/test_harness.py`, `tools/verification/run_rd_smoke.py` "
        "— self-tests and execution entry point.",
        "",
        "## Smoke cases",
        "",
        "| ID | Capability | Result | Failure class | Duration ms |",
        "|---|---|---:|---|---:|",
    ]
    for case in cases:
        lines.append(
            "| {id} | {capability} | {result} | {failure} | {duration} |".format(
                id=case.get("testcase_id", ""),
                capability=case.get("capability", ""),
                result=case.get("result", ""),
                failure=case.get("failure_class") or "—",
                duration=case.get("duration_ms", 0),
            )
        )
    lines.extend(
        [
            "",
            f"Smoke total/pass/fail: `{summary.get('total', 0)}` / "
            f"`{summary.get('pass', 0)}` / `{summary.get('fail', 0)}`; "
            f"blocked/unsupported: `{summary.get('blocked_env', 0)}` / "
            f"`{summary.get('unsupported_platform', 0)}`.",
            "",
            "## Device metadata",
            "",
            f"- Instance/serial: `{device.get('instance_name', '')}` / `{device.get('serial', '')}`",
            f"- Manufacturer/model: `{device.get('manufacturer', '')}` / `{device.get('model', '')}`",
            f"- API/Android: `{device.get('api_level', '')}` / `{device.get('android_version', '')}`",
            f"- ABI/ABI list: `{device.get('abi', '')}` / `{', '.join(device.get('abi_list', []) or [])}`",
            f"- Page size: `{device.get('page_size', '')}`",
            f"- Fingerprint: `{device.get('fingerprint', '')}`",
            f"- Kernel: `{device.get('kernel', '')}`",
            f"- CAS commit: `{device.get('cas_commit', '')}`",
            f"- APK hashes recorded: `{len(device.get('apk_hashes', {}) or {})}`",
            "",
            "## Build and harness tests",
            "",
            f"- `gradlew.bat projects`: `{run.get('gradle_projects', 'NOT_RECORDED')}`",
            f"- `gradlew.bat assembleDebug`: `{run.get('assemble_debug', 'NOT_RECORDED')}`",
            f"- `gradlew.bat test` (or actual unit tasks): `{run.get('unit_tests', 'NOT_RECORDED')}`",
            f"- Harness self-tests: `{run.get('harness_tests', 'NOT_RECORDED')}`",
            "",
            "## Defects and limitations",
            "",
        ]
    )
    defects = [
        case
        for case in cases
        if case.get("result") == ResultState.FAIL.value
        and case.get("failure_class") == FailureClass.PRODUCT_DEFECT.value
    ]
    if defects:
        for case in defects:
            first = case.get("first_attempt") or {}
            lines.append(
                f"- `DISCOVERED_PRODUCT_DEFECT` — `{case.get('testcase_id')}`; "
                f"reproduce with `{case.get('operation', '')}`; "
                f"signature `{first.get('failure_signature', '')}`; "
                "the first failure remains FAIL even if a diagnostic retry passes; "
                "next recommended task: C6-T01B product correction and rerun."
            )
    else:
        lines.append("- No product defect was classified by the completed run.")
    limitations = run.get("limitations") or []
    for item in limitations:
        lines.append(f"- {item}")
    lines.extend(
        [
            "",
            "## Evidence and Git hygiene",
            "",
            f"- Local evidence: `{run.get('evidence_root', '')}`",
            f"- `git status --short`: `{hygiene.get('status', 'NOT_RECORDED')}`",
            f"- `git diff --stat`: `{hygiene.get('diff_stat', 'NOT_RECORDED')}`",
            f"- `git ls-files out`: `{hygiene.get('tracked_out', 'NOT_RECORDED')}`",
            "- Raw logs, dumps, screenshots and command JSON remain only in the ignored "
            "run directory; the report contains paths and summaries, not full logs.",
            "",
        ]
    )
    return "\n".join(lines)


def write_report(path: Path, run: dict[str, Any], *, final_head: str | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_compact_report(run, final_head=final_head), encoding="utf-8")


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
