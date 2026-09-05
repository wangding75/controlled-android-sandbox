"""Validation helpers for compact cross-API capability matrices.

The closure report is the source of truth for the compact matrix.  This module
keeps accounting fail-closed: every cell needs a unique id and an allowed
status, and deferred cells need an explicit reason.
"""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


ALLOWED_STATUSES = frozenset(
    {
        "PASS",
        "FAIL",
        "SKIP",
        "EXPECTED_PLATFORM_BEHAVIOR",
        "UNSUPPORTED_PLATFORM",
        "NOT_IN_CURRENT_SCOPE",
        "DEFERRED_ENVIRONMENT",
    }
)


class MatrixAccountingError(ValueError):
    """Raised when a matrix violates the frozen accounting contract."""


@dataclass(frozen=True)
class MatrixSummary:
    """Immutable status totals for one validated matrix."""

    name: str
    total: int
    counts: Mapping[str, int]

    def count(self, status: str) -> int:
        return self.counts.get(status, 0)


def validate_cells(
    name: str,
    cells: Iterable[Mapping[str, object]],
    *,
    expected_total: int | None = None,
) -> MatrixSummary:
    """Validate and count a matrix represented as cell mappings.

    ``cells`` deliberately uses a small, report-friendly shape::

        {"id": "API37-memory-limiter", "status": "DEFERRED_ENVIRONMENT",
         "reason": "status disabled on the public image"}

    No status outside :data:`ALLOWED_STATUSES` can enter a summary.
    """

    seen: set[str] = set()
    counts = {status: 0 for status in sorted(ALLOWED_STATUSES)}
    total = 0
    for index, cell in enumerate(cells, start=1):
        cell_id = str(cell.get("id", "")).strip()
        if not cell_id:
            raise MatrixAccountingError(f"{name}: cell {index} has no id")
        if cell_id in seen:
            raise MatrixAccountingError(f"{name}: duplicate id {cell_id}")
        seen.add(cell_id)

        status_value = cell.get("status")
        status = str(status_value).strip() if status_value is not None else ""
        if not status:
            raise MatrixAccountingError(f"{name}: {cell_id} has no status")
        if status not in ALLOWED_STATUSES:
            raise MatrixAccountingError(f"{name}: {cell_id} has unknown status {status}")
        if status == "DEFERRED_ENVIRONMENT" and not str(cell.get("reason", "")).strip():
            raise MatrixAccountingError(f"{name}: {cell_id} deferred without reason")

        counts[status] += 1
        total += 1

    if expected_total is not None and total != expected_total:
        raise MatrixAccountingError(
            f"{name}: total mismatch expected {expected_total}, actual {total}"
        )
    return MatrixSummary(name=name, total=total, counts=counts)


def _section(text: str, heading: str) -> str:
    start = text.find(heading)
    if start < 0:
        raise MatrixAccountingError(f"report section missing: {heading}")
    body = text[start + len(heading) :]
    next_heading = re.search(r"^##\s+", body, flags=re.MULTILINE)
    return body[: next_heading.start()] if next_heading else body


def _split_row(line: str) -> list[str]:
    return [part.strip() for part in line.strip().strip("|").split("|")]


def _table(section: str) -> tuple[list[str], list[list[str]]]:
    lines = [line.strip() for line in section.splitlines() if line.strip().startswith("|")]
    if len(lines) < 2:
        raise MatrixAccountingError("matrix table missing")
    header = _split_row(lines[0])
    rows: list[list[str]] = []
    for line in lines[1:]:
        values = _split_row(line)
        if values and all(re.fullmatch(r":?-{3,}:?", value) for value in values):
            continue
        if len(values) != len(header):
            raise MatrixAccountingError(
                f"matrix table row has {len(values)} columns, expected {len(header)}"
            )
        rows.append(values)
    if not rows:
        raise MatrixAccountingError("matrix table has no data rows")
    return header, rows


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", value).strip("-").lower()


def _report_cells(report: Path) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    text = report.read_text(encoding="utf-8")

    unified_header, unified_rows = _table(_section(text, "## 5. Unified Capability Matrix"))
    api_columns = [
        (index, column)
        for index, column in enumerate(unified_header)
        if re.fullmatch(r"API\d+", column)
    ]
    if [column for _, column in api_columns] != [f"API{api}" for api in range(32, 38)]:
        raise MatrixAccountingError("unified matrix must contain API32 through API37")
    reason_index = unified_header.index("Reason") if "Reason" in unified_header else None
    unified_cells: list[dict[str, str]] = []
    for row in unified_rows:
        capability = row[0]
        reason = row[reason_index] if reason_index is not None else ""
        for index, api in api_columns:
            unified_cells.append(
                {
                    "id": f"{_slug(capability)}-{api.lower()}",
                    "status": row[index],
                    "reason": reason,
                }
            )

    version_header, version_rows = _table(
        _section(text, "## 6. Version-Specific Matrix")
    )
    required_version_columns = {"API", "Case", "Status"}
    if not required_version_columns.issubset(version_header):
        raise MatrixAccountingError("version-specific matrix columns are incomplete")
    api_index = version_header.index("API")
    case_index = version_header.index("Case")
    status_index = version_header.index("Status")
    evidence_index = (
        version_header.index("Evidence / boundary")
        if "Evidence / boundary" in version_header
        else None
    )
    version_cells: list[dict[str, str]] = []
    for row in version_rows:
        reason = row[evidence_index] if evidence_index is not None else ""
        version_cells.append(
            {
                "id": f"api{row[api_index]}-{_slug(row[case_index])}",
                "status": row[status_index],
                "reason": reason,
            }
        )
    return unified_cells, version_cells


def validate_report(report: str | Path) -> dict[str, MatrixSummary]:
    """Parse and validate the two closure matrices in ``report``."""

    unified_cells, version_cells = _report_cells(Path(report))
    return {
        "unified": validate_cells(
            "Unified Capability", unified_cells, expected_total=48
        ),
        "version_specific": validate_cells(
            "Version-Specific", version_cells, expected_total=31
        ),
    }


def _print_summary(summary: MatrixSummary) -> None:
    prefix = "UNIFIED_CAPABILITY" if summary.name == "Unified Capability" else "VERSION_SPECIFIC"
    print(f"{prefix}_TOTAL={summary.total}")
    for status, key in (
        ("PASS", "PASS"),
        ("FAIL", "FAIL"),
        ("SKIP", "SKIP"),
        ("EXPECTED_PLATFORM_BEHAVIOR", "EXPECTED"),
        ("UNSUPPORTED_PLATFORM", "UNSUPPORTED"),
        ("NOT_IN_CURRENT_SCOPE", "NOT_IN_SCOPE"),
        ("DEFERRED_ENVIRONMENT", "DEFERRED"),
    ):
        print(f"{prefix}_{key}={summary.count(status)}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        summaries = validate_report(args.report)
    except (OSError, MatrixAccountingError) as error:
        print("MATRIX_ACCOUNTING=FAIL")
        print("MATRIX_VALIDATOR=FAIL")
        print(f"MATRIX_VALIDATOR_ERROR={error}")
        return 1
    print("MATRIX_ACCOUNTING=PASS")
    print("MATRIX_VALIDATOR=PASS")
    _print_summary(summaries["unified"])
    _print_summary(summaries["version_specific"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
