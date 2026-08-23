#!/usr/bin/env python3
"""Static acceptance gate for the C3-T01 native compatibility corpus."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tools" / "capability") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))

from common import load_yaml  # noqa: E402

MATRIX = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml"
DESIGN = ROOT / "docs/review/C3_T01_NATIVE_CORPUS_DESIGN.md"
FIXTURE_CPP = ROOT / "fixture-basic/src/main/cpp/adversarial_native.cpp"
FIXTURE_64 = ROOT / "fixture-basic/build.gradle"
FIXTURE_32 = ROOT / "fixture-compat32/build.gradle"
CMAKE = ROOT / "fixture-basic/src/main/cpp/CMakeLists.txt"

REQUIRED_CASES = {f"NATIVE-ADV-{index:03d}" for index in range(1, 12)}
REQUIRED_OPERATIONS = {
    "openat2",
    "access/faccessat/faccessat2",
    "stat/lstat/fstatat/statx",
    "xattr family",
    "getcwd/chdir",
    "realpath",
    "execve",
    "socket",
    "ioctl",
    "architecture raw instruction",
}
REQUIRED_FIELDS = {
    "id",
    "domain",
    "operation",
    "libc_path",
    "syscall_path",
    "raw_instruction_path",
    "current_interceptor",
    "current_status",
    "bypass_risk",
    "required_fixture",
    "required_rd_test",
    "target_mode",
    "va_pro_signal",
    "notes",
}
LEGAL_STATUS = {"INTERCEPTED", "PARTIAL", "NOT_INTERCEPTED", "NOT_APPLICABLE", "UNKNOWN"}
LEGAL_MODE = {"TRUSTED_COMPAT", "ISOLATED_HOSTILE"}


def require_text(path: Path, tokens: tuple[str, ...], errors: list[str]) -> str:
    if not path.is_file():
        errors.append(f"missing {path.relative_to(ROOT)}")
        return ""
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{path.relative_to(ROOT)} missing token: {token}")
    return text


def main() -> int:
    errors: list[str] = []
    design = require_text(
        DESIGN,
        (
            "C3-T01",
            "TRUSTED_COMPAT",
            "ISOLATED_HOSTILE",
            "BYPASS_CONFIRMED",
            "NATIVE-ADV-011",
            "negative_host_path=DENIED",
            "va_pro_equivalent",
        ),
        errors,
    )
    fixture = require_text(
        FIXTURE_CPP,
        (
            "openat2",
            "faccessat2",
            "setxattr",
            "getcwd",
            "realpath",
            "ioctl",
            "negative_host_path",
            "UNCONTROLLED_ENTRYPOINTS_RECORDED",
            "NATIVE-ADV-011",
            "raw_instruction_case=NATIVE-ADV-003",
        ),
        errors,
    )
    build64 = require_text(FIXTURE_64, ("arm64-v8a", "x86_64"), errors)
    build32 = require_text(FIXTURE_32, ("armeabi-v7a", "x86"), errors)
    cmake = require_text(CMAKE, ("adversarial_native.cpp", "adversarial_payload.cpp"), errors)

    if MATRIX.is_file():
        try:
            data = load_yaml(MATRIX) or {}
        except Exception as exc:  # pragma: no cover - gate reports parser failures
            errors.append(f"matrix YAML parse failed: {exc}")
            data = {}
        entries = list(data.get("entries") or [])
        if len(entries) < 55:
            errors.append(f"matrix entry count is {len(entries)}, expected at least 55")
        operations = {str(item.get("operation") or "") for item in entries}
        missing_operations = REQUIRED_OPERATIONS - operations
        if missing_operations:
            errors.append(f"matrix missing operations: {sorted(missing_operations)}")
        ids = {str(item.get("id") or "") for item in entries}
        if "SYS-IOCTL" not in ids:
            errors.append("matrix missing SYS-IOCTL classification")
        for item in entries:
            row_id = str(item.get("id") or "<unnamed>")
            missing = REQUIRED_FIELDS - set(item)
            if missing:
                errors.append(f"{row_id} missing fields: {sorted(missing)}")
                continue
            for field in ("libc_path", "syscall_path", "raw_instruction_path", "current_status"):
                if item[field] not in LEGAL_STATUS:
                    errors.append(f"{row_id}.{field} has illegal status {item[field]!r}")
            if item["target_mode"] not in LEGAL_MODE:
                errors.append(f"{row_id}.target_mode has illegal value {item['target_mode']!r}")
            if item["current_status"] == "UNKNOWN":
                errors.append(f"{row_id} remains UNKNOWN")
        for operation in REQUIRED_OPERATIONS:
            rows = [item for item in entries if item.get("operation") == operation]
            if rows and operation not in {"execve", "socket", "architecture raw instruction"}:
                row = rows[0]
                if row.get("required_fixture") != "NATIVE-ADV-011":
                    errors.append(
                        f"{row.get('id')}.required_fixture must be NATIVE-ADV-011 for {operation}"
                    )
                if row.get("required_rd_test") != "NATIVE-ADV-011":
                    errors.append(
                        f"{row.get('id')}.required_rd_test must be NATIVE-ADV-011 for {operation}"
                    )
        statement = str(data.get("security_statement") or "")
        if "TRUSTED_COMPAT" not in statement or "ISOLATED_HOSTILE" not in statement:
            errors.append("matrix security_statement must separate both evidence modes")

    for case_id in REQUIRED_CASES:
        if case_id not in fixture:
            errors.append(f"fixture missing {case_id}")
    if "sandbox-native" in fixture or "sandbox-native" in cmake:
        errors.append("C3 fixture must not link production sandbox-native interception code")
    if "UNCONTROLLED_ENTRYPOINTS_RECORDED" in fixture and "PASS_COMPAT" not in fixture:
        errors.append("fixture must retain a direct compatibility status distinct from bypass discovery")
    if not build64 or not build32 or "adversarial_native.cpp" not in cmake:
        errors.append("four-ABI fixture build wiring is incomplete")
    if "SYS_ioctl" not in fixture:
        errors.append("fixture must include the syscall ioctl branch")

    if errors:
        print("FAIL C3-T01 native corpus")
        for error in errors:
            print(" -", error)
        return 1
    print("PASS C3-T01 native corpus static acceptance")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
