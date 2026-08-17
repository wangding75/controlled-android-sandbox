#!/usr/bin/env python3
"""Static check for T57-R03-P0A-01 Native boundary campaign artifacts."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tools" / "capability") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))

from common import load_yaml  # noqa: E402

MATRIX = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_MATRIX.yaml"
THREAT = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_THREAT_MODEL.md"
OPTIONS = ROOT / "docs/native/T57_R03_NATIVE_ENFORCEMENT_OPTIONS.md"
DECISION = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_DECISION.md"
FIXTURE_CPP = ROOT / "fixture-basic/src/main/cpp/adversarial_native.cpp"
PAYLOAD_CPP = ROOT / "fixture-basic/src/main/cpp/adversarial_payload.cpp"
FIXTURE_JAVA = ROOT / "fixture-basic/src/main/java/com/warden/controlledsandbox/fixture/NativeAdversarialProbe.java"

REQUIRED_DOMAINS = {
    "filesystem",
    "network",
    "process",
    "identity",
    "procfs",
    "loader",
    "binder",
    "syscall",
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
REQUIRED_OPERATIONS = {
    "open",
    "openat",
    "openat2",
    "access/faccessat/faccessat2",
    "stat/lstat/fstatat/statx",
    "readlink/readlinkat",
    "unlink/unlinkat",
    "rename/renameat/renameat2",
    "mkdir/mkdirat",
    "chmod/fchmodat",
    "chown/fchownat",
    "xattr family",
    "getcwd/chdir",
    "realpath",
    "mmap/mprotect",
    "getpid",
    "getppid",
    "gettid",
    "getuid/geteuid",
    "getgid/getegid",
    "kill/tgkill",
    "prctl",
    "ptrace",
    "clone/fork/vfork",
    "execve",
    "/proc/self",
    "/proc/<pid>",
    "/proc/<pid>/task",
    "/proc/<pid>/fd",
    "/proc/<pid>/maps",
    "/proc/<pid>/map_files",
    "/proc/net",
    "socket",
    "connect",
    "bind",
    "getsockname",
    "getpeername",
    "sendto/recvfrom",
    "dlopen",
    "android_dlopen_ext",
    "dlsym",
    "custom loader path",
    "JNI_OnLoad",
    "System.load / System.loadLibrary",
    "linker namespace",
    "native library search path",
    "libc wrapper",
    "syscall()",
    "__syscall",
    "architecture raw instruction",
    "inline asm",
}
REQUIRED_CASES = [f"NATIVE-ADV-{index:03d}" for index in range(1, 11)]
LEGAL_STATUS = {
    "INTERCEPTED",
    "PARTIAL",
    "NOT_INTERCEPTED",
    "NOT_APPLICABLE",
    "UNKNOWN",
}


def main() -> int:
    errors: list[str] = []
    for path in (MATRIX, THREAT, OPTIONS, DECISION, FIXTURE_CPP, PAYLOAD_CPP, FIXTURE_JAVA):
        if not path.is_file():
            errors.append(f"missing required file: {path.relative_to(ROOT)}")

    if MATRIX.is_file():
        data = load_yaml(MATRIX)
        entries = list(data.get("entries") or [])
        if len(entries) < 40:
            errors.append(f"boundary matrix too small: {len(entries)} entries")
        domains = {item.get("domain") for item in entries}
        missing_domains = REQUIRED_DOMAINS - domains
        if missing_domains:
            errors.append(f"matrix missing domains: {sorted(missing_domains)}")
        operations = {item.get("operation") for item in entries}
        missing_ops = REQUIRED_OPERATIONS - operations
        if missing_ops:
            errors.append(f"matrix missing operations: {sorted(missing_ops)}")
        uncovered = [
            item.get("id")
            for item in entries
            if item.get("current_status") == "INTERCEPTED"
            and item.get("raw_instruction_path") not in {"NOT_INTERCEPTED", "NOT_APPLICABLE"}
        ]
        if uncovered:
            errors.append(f"intercepted rows must still record raw path: {uncovered}")
        for item in entries:
            missing = [field for field in REQUIRED_FIELDS if field not in item]
            if missing:
                errors.append(f"{item.get('id')}: missing {missing}")
                continue
            for field in ("libc_path", "syscall_path", "raw_instruction_path", "current_status"):
                if item.get(field) not in LEGAL_STATUS:
                    errors.append(f"{item.get('id')}.{field} illegal status {item.get(field)!r}")
        if data.get("security_statement", "").find("TRUSTED_COMPAT") < 0:
            errors.append("matrix security_statement must mention TRUSTED_COMPAT")
        if "ISOLATED_HOSTILE" not in str(data.get("security_statement") or ""):
            errors.append("matrix security_statement must mention ISOLATED_HOSTILE")

    if THREAT.is_file():
        text = THREAT.read_text(encoding="utf-8")
        for token in (
            "TRUSTED_COMPAT",
            "ISOLATED_HOSTILE",
            "cannot prove",
            "raw SVC",
            "Host path privacy",
            "intentionally hostile",
        ):
            if token not in text:
                errors.append(f"threat model missing token: {token}")

    if DECISION.is_file():
        text = DECISION.read_text(encoding="utf-8")
        for token in (
            "Option A",
            "Option B",
            "REQUIRES_PRIVILEGE",
            "T57-R03-P0A-02",
            "NOT_PROVEN",
        ):
            if token not in text:
                errors.append(f"decision doc missing token: {token}")

    if FIXTURE_CPP.is_file():
        text = FIXTURE_CPP.read_text(encoding="utf-8")
        for case in REQUIRED_CASES:
            if case not in text:
                errors.append(f"adversarial fixture missing {case}")
        for token in ("svc #0", "syscall", "PTRACE_ATTACH", "execve", "/dev/binder"):
            if token not in text:
                errors.append(f"adversarial fixture missing token: {token}")
        if "sandbox-native" in text and "controlled_sandbox::" in text:
            errors.append("adversarial fixture must not link production interceptors")

    if errors:
        print("FAIL native boundary matrix")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS native boundary matrix and adversarial fixture inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
