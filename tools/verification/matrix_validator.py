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

# ABI names are a validation vocabulary, not a product-support assertion.  The
# audit derives the declared/buildable set from Gradle/CMake; this list only
# prevents a typo or an obsolete ABI from silently entering a receipt.
ABI_NAMES = frozenset({"armeabi-v7a", "arm64-v8a", "x86", "x86_64"})
ABI_MATRIX_STATUSES = frozenset(
    {"BUILT", "DECLARED_NOT_BUILT", "NOT_DECLARED", "TEST_ONLY", "COMPANION_ONLY"}
)
ABI_ELF_CONTRACT = {
    "armeabi-v7a": ("ELF32", "ARM"),
    "arm64-v8a": ("ELF64", "AArch64"),
    "x86": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}


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


def _required_text(
    name: str, index: int, cell: Mapping[str, object], key: str
) -> str:
    value = str(cell.get(key, "")).strip()
    if not value:
        raise MatrixAccountingError(f"{name}: cell {index} has no {key}")
    return value


def _normalise_elf_value(value: object) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value).strip().lower())


def validate_abi_matrix(
    cells: Iterable[Mapping[str, object]],
    *,
    expected_abis: Iterable[str] = ABI_NAMES,
    name: str = "ABI Matrix",
) -> MatrixSummary:
    """Validate the declared/build ABI matrix and its artifact closure.

    Each row is intentionally small and JSON/report friendly::

        {"module": "app", "abi": "arm64-v8a", "status": "BUILT",
         "artifact": "app-debug.apk"}

    ``DECLARED_NOT_BUILT`` is rejected: a declared ABI without a real output
    is precisely the failure this gate is meant to expose.  ``NOT_DECLARED``
    is allowed for an explicit matrix row and does not claim support.
    """

    allowed_abis = {str(abi).strip() for abi in expected_abis}
    if not allowed_abis.issubset(ABI_NAMES):
        raise MatrixAccountingError(f"{name}: expected ABI vocabulary is invalid")
    seen: set[tuple[str, str]] = set()
    counts = {status: 0 for status in sorted(ABI_MATRIX_STATUSES)}
    total = 0
    for index, cell in enumerate(cells, start=1):
        module = _required_text(name, index, cell, "module")
        abi = _required_text(name, index, cell, "abi")
        if abi not in allowed_abis or abi not in ABI_NAMES:
            raise MatrixAccountingError(f"{name}: {module} has unknown ABI {abi}")
        key = (module, abi)
        if key in seen:
            raise MatrixAccountingError(f"{name}: duplicate cell {module}/{abi}")
        seen.add(key)

        status = _required_text(name, index, cell, "status")
        if status not in ABI_MATRIX_STATUSES:
            raise MatrixAccountingError(f"{name}: {module}/{abi} has unknown status {status}")
        if status == "DECLARED_NOT_BUILT":
            raise MatrixAccountingError(
                f"{name}: declared ABI missing artifact for {module}/{abi}"
            )
        if status in {"BUILT", "TEST_ONLY", "COMPANION_ONLY"}:
            _required_text(name, index, cell, "artifact")
        artifact_abi = str(cell.get("artifact_abi", "")).strip()
        if artifact_abi and artifact_abi != abi:
            raise MatrixAccountingError(
                f"{name}: artifact ABI mismatch for {module}/{abi}: {artifact_abi}"
            )
        counts[status] += 1
        total += 1
    return MatrixSummary(name=name, total=total, counts=counts)


def validate_native_inventory(
    records: Iterable[Mapping[str, object]],
    *,
    expected_abis: Iterable[str] = ABI_NAMES,
    name: str = "Native Inventory",
) -> MatrixSummary:
    """Validate final native records, ELF identity, and 16 KB status.

    The key is ``artifact + ABI + library``.  Repeating it is rejected so a
    duplicate Gradle/pickFirst result cannot disappear from the audit.  Every
    record must carry a first/third-party classification and an explicit
    16 KB alignment status; the validator therefore also catches incomplete
    inventories instead of treating omitted fields as pass.
    """

    allowed_abis = {str(abi).strip() for abi in expected_abis}
    if not allowed_abis.issubset(ABI_NAMES):
        raise MatrixAccountingError(f"{name}: expected ABI vocabulary is invalid")
    seen: set[tuple[str, str, str]] = set()
    counts = {"PASS": 0, "FAIL": 0}
    total = 0
    for index, record in enumerate(records, start=1):
        artifact = _required_text(name, index, record, "artifact")
        abi = _required_text(name, index, record, "abi")
        library = _required_text(name, index, record, "library")
        if abi not in allowed_abis or abi not in ABI_NAMES:
            raise MatrixAccountingError(f"{name}: {library} has unknown ABI {abi}")
        key = (artifact, abi, library)
        if key in seen:
            raise MatrixAccountingError(
                f"{name}: duplicate native record {artifact}/{abi}/{library}"
            )
        seen.add(key)

        classification = str(
            record.get("classification", record.get("party", ""))
        ).strip().lower()
        if classification not in {"first_party", "third_party"}:
            raise MatrixAccountingError(
                f"{name}: unclassified library {artifact}/{abi}/{library}"
            )

        alignment = str(
            record.get("alignment_status", record.get("page_size_16k_status", ""))
        ).strip().upper()
        if not alignment:
            raise MatrixAccountingError(
                f"{name}: 16KB status missing for {artifact}/{abi}/{library}"
            )
        if alignment != "PASS":
            raise MatrixAccountingError(
                f"{name}: 16KB alignment failure for {artifact}/{abi}/{library}: {alignment}"
            )

        elf_class = str(record.get("elf_class", "")).strip()
        machine = str(record.get("machine", "")).strip()
        expected_class, expected_machine = ABI_ELF_CONTRACT[abi]
        if elf_class != expected_class or _normalise_elf_value(machine) != _normalise_elf_value(expected_machine):
            raise MatrixAccountingError(
                f"{name}: ELF machine mismatch for {artifact}/{abi}/{library}: "
                f"{elf_class}/{machine}, expected {expected_class}/{expected_machine}"
            )

        dependency = str(record.get("dependency_status", "PASS")).strip().upper()
        if dependency != "PASS":
            raise MatrixAccountingError(
                f"{name}: dependency failure for {artifact}/{abi}/{library}: {dependency}"
            )
        counts["PASS"] += 1
        total += 1
    return MatrixSummary(name=name, total=total, counts=counts)


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


def _load_json_rows(path: Path, key: str) -> list[Mapping[str, object]]:
    import json

    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        rows = payload
    elif isinstance(payload, dict) and isinstance(payload.get(key), list):
        rows = payload[key]
    else:
        raise MatrixAccountingError(f"{path}: expected a list or object field {key}")
    if not all(isinstance(row, Mapping) for row in rows):
        raise MatrixAccountingError(f"{path}: every row must be an object")
    return rows


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--abi-matrix", type=Path)
    parser.add_argument("--native-inventory", type=Path)
    args = parser.parse_args(argv)
    if not any((args.report, args.abi_matrix, args.native_inventory)):
        parser.error("at least one of --report, --abi-matrix, --native-inventory is required")
    try:
        summaries: dict[str, MatrixSummary] = {}
        if args.report:
            summaries.update(validate_report(args.report))
        if args.abi_matrix:
            summaries["abi"] = validate_abi_matrix(
                _load_json_rows(args.abi_matrix, "cells")
            )
        if args.native_inventory:
            summaries["native"] = validate_native_inventory(
                _load_json_rows(args.native_inventory, "records")
            )
    except (OSError, MatrixAccountingError, ValueError) as error:
        print("MATRIX_ACCOUNTING=FAIL")
        print("MATRIX_VALIDATOR=FAIL")
        if args.abi_matrix or args.native_inventory:
            print("ABI_MATRIX_VALIDATOR=FAIL")
        print(f"MATRIX_VALIDATOR_ERROR={error}")
        return 1
    print("MATRIX_ACCOUNTING=PASS")
    print("MATRIX_VALIDATOR=PASS")
    for key in ("unified", "version_specific", "abi", "native"):
        if key not in summaries:
            continue
        if key == "abi":
            print("ABI_MATRIX_VALIDATOR=PASS")
            print(f"ABI_MATRIX_TOTAL={summaries[key].total}")
            for status in sorted(ABI_MATRIX_STATUSES):
                print(f"ABI_MATRIX_{status}={summaries[key].count(status)}")
        elif key == "native":
            print("NATIVE_INVENTORY_VALIDATOR=PASS")
            print(f"NATIVE_INVENTORY_TOTAL={summaries[key].total}")
        else:
            _print_summary(summaries[key])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
