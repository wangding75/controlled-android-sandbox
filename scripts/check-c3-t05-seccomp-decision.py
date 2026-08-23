#!/usr/bin/env python3
"""Static gate for the C3-T05 seccomp/user-notify decision."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")


def require(relative: str, tokens: tuple[str, ...]) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing token: {token}")
    return text


def main() -> int:
    adr = require(
        "docs/review/C3_T05_SECCOMP_USER_NOTIFY_ADR.md",
        (
            "NOT_APPLICABLE",
            "SECCOMP_USER_NOTIF",
            "REQUIRES_PRIVILEGE",
            "Option D",
            "Option E",
            "C3-T04",
            "VA Pro",
            "ordinary APK",
        ),
    )
    runner = require(
        "tools/capability/run_c3_t05_rd.py",
        (
            "RD测试",
            "resolve_rd_environment",
            "CONFIG_SECCOMP_USER_NOTIF",
            "NOT_APPLICABLE",
            "FORBIDDEN_SERIALS",
        ),
    )
    native = require(
        "sandbox-native/src/main/cpp/hostile_seccomp.cpp",
        ("SECCOMP_RET_ERRNO", "SYS_ptrace", "SYS_execve"),
    )
    issues = require(
        "docs/review/KNOWN_ISSUES.yaml",
        ("KI-R03-NATIVE-008", "user-notify"),
    )
    if "SECCOMP_RET_USER_NOTIF" in native:
        errors.append("production hostile filter must remain deny-errno, not user-notify")
    for relative, text in (
        ("tools/capability/run_c3_t05_rd.py", runner),
        ("docs/review/C3_T05_SECCOMP_USER_NOTIFY_ADR.md", adr),
        ("docs/review/KNOWN_ISSUES.yaml", issues),
    ):
        if relative.endswith("run_c3_t05_rd.py"):
            continue
        for serial in FORBIDDEN_SERIALS:
            if serial in text:
                errors.append(f"{relative} contains historical ADB serial")
    if errors:
        print("FAIL C3-T05 seccomp decision gate")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C3-T05 seccomp user-notify decision inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
