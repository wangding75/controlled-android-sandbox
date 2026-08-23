#!/usr/bin/env python3
"""Static acceptance gate for the C3-T02 file/proc/network/FD corpus."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


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


design = require(
    "docs/review/C3_T02_FILE_PROC_NETWORK_FD_DESIGN.md",
    (
        "C3-T02",
        "TRUSTED_COMPAT",
        "ISOLATED_HOSTILE",
        "authoritative FD ledger",
        "direct syscall",
        "VA Pro",
        "clear/death/exec",
    ),
)
fixture = require(
    "fixture-basic/src/main/cpp/c3_t02_native.cpp",
    (
        "C3-T02-FS-001",
        "C3-T02-PROC-001",
        "C3-T02-NET-001",
        "C3-T02-FD-001",
        "C3-T02-FD-002",
        "C3-T02-FD-003",
        "C3-T02-RAW-001",
        "openat",
        "symlinkat",
        "/proc/self/maps",
        "/proc/self/smaps",
        "/proc/self/fd",
        "/proc/self/task",
        "/proc/self/cgroup",
        "/proc/net",
        "getaddrinfo",
        "dup2",
        "dup3",
        "F_DUPFD_CLOEXEC",
        "SCM_RIGHTS",
        "FD_CLOEXEC",
        "UNMEDIATED_DIRECT_SYSCALL_EXPOSED",
    ),
)
procfs = require(
    "sandbox-native/src/main/cpp/native_procfs.cpp",
    (
        "allow_guest_descriptor",
        "UNKNOWN_OR_INTERNAL_FD_DENIED",
        "std::vector<int> visible_fd_candidates",
    ),
)
resolver = require(
    "sandbox-native/src/main/cpp/native_file_system.cpp",
    ("UNKNOWN_INHERITED_FD_DENIED", "STDERR_FILENO"),
)
interceptors = require(
    "sandbox-native/src/main/cpp/native_process_interceptors.cpp",
    ("fd_operation_current", "if (!record)", "STDERR_FILENO"),
)
network = require(
    "sandbox-native/src/main/cpp/native_network_interceptors.cpp",
    ("anonymous_unix_socket", "SCM_RIGHTS", "native_socket_address_allowed"),
)
cmake = require("fixture-basic/src/main/cpp/CMakeLists.txt", ("c3_t02_native.cpp",))
manifest = require(
    "fixture-basic/src/main/AndroidManifest.xml",
    ("C3T02FileProcNetworkFdActivity",),
)
debug = require(
    "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
    ("c3-t02-file-proc-network-fd", "IN_SANDBOX"),
)

for text, forbidden in (
    (fixture, ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")),
    (debug, ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")),
):
    for token in forbidden:
        if token in text:
            errors.append(f"C3-T02 implementation hard-codes forbidden endpoint: {token}")

if errors:
    print("FAIL C3-T02 file/proc/network/FD static acceptance")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("PASS C3-T02 file/proc/network/FD static acceptance")
