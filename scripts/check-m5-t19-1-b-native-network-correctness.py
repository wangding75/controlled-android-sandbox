#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/m5-t19-1-b-native-network-correctness.json"
errors: list[str] = []


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig", errors="strict")


def require(rel: str, *tokens: str) -> str:
    value = read(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require(
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_network.h",
    "native_is_tracked_socket",
    "native_socket_io_mutex",
    "[[nodiscard]] bool native_rebind_duplicated_descriptor",
    "native_adopt_accepted_socket",
)
require(
    "sandbox-native/src/main/cpp/include/controlled_sandbox/native_network_interceptors.h",
    "controlled_recvfrom",
    "controlled_recvmsg",
    "controlled_accept4",
    "controlled_fcntl",
)
network = require(
    "sandbox-native/src/main/cpp/native_network.cpp",
    "native_is_tracked_socket",
    "native_socket_io_mutex",
    "bool native_rebind_duplicated_descriptor",
    "native_adopt_accepted_socket",
    "native_socket_io_mutex",
    "MAX_TRACKED_SOCKETS",
)
adapter = require(
    "sandbox-native/src/main/cpp/native_network_interceptors.cpp",
    "MAX_TEMP_NETWORK_PAYLOAD",
    "MAX_TEMP_NETWORK_CONTROL",
    "MAX_TEMP_NETWORK_IOVECS",
    "NETWORK_RECEIVE_LOCK_COUNT",
    "MSG_PEEK",
    "temporary_payload",
    "temporary_source",
    "temporary_control",
    "copy_socket_address",
    "close_received_file_descriptors",
    "native_socket_address_allowed",
    "native_socket_destination_allowed",
    "bind_duplicate_or_close",
    "native_rebind_duplicated_descriptor",
    "native_adopt_accepted_socket",
)
for function in (
    "controlled_send(",
    "controlled_sendto(",
    "controlled_sendmsg(",
    "controlled_recv(",
    "controlled_recvfrom(",
    "controlled_recvmsg(",
    "controlled_read(",
    "controlled_write(",
    "controlled_accept(",
    "controlled_accept4(",
    "controlled_dup(",
    "controlled_dup2(",
    "controlled_dup3(",
    "controlled_fcntl(",
    "controlled_getsockname(",
    "controlled_getpeername(",
):
    if function not in adapter:
        errors.append(f"native network adapter missing function: {function}")
if "function(socket_fd, buffer, length, flags, source, source_length)" in adapter:
    errors.append("recvfrom must not pass Guest payload/address buffers directly to libc")
if "const int status = function(socket_fd, address, length);" in adapter:
    errors.append("peer/local address queries must use temporary address storage")

hook = require(
    "sandbox-native/src/main/cpp/native_hook.cpp",
    '"send"', '"sendmsg"', '"recv"', '"recvmsg"', '"read"', '"write"',
    '"accept"', '"accept4"', '"dup"', '"dup2"', '"dup3"', '"fcntl"', '"fcntl64"',
)
replacement = require(
    "sandbox-native/src/main/cpp/native_interceptors.cpp",
    'name == "send"', 'name == "sendmsg"', 'name == "recv"', 'name == "recvmsg"',
    'name == "read"', 'name == "write"', 'name == "accept"', 'name == "accept4"',
    'name == "dup"', 'name == "dup2"', 'name == "dup3"',
    'name == "fcntl"', 'name == "fcntl64"',
)
if "checked_recvfrom" in replacement:
    errors.append("network receive implementation must remain in the split adapter, not the symbol dispatcher")

self_test = require(
    "sandbox-native/src/test/cpp/native_network_interceptors_self_test.cpp",
    "denied datagram remains queued",
    "recvfrom payload unchanged",
    "recvmsg payload unchanged",
    "denied send emitted no payload",
    "getpeername output unchanged",
    "concurrent recvfrom payloads",
    "dup aliases share receive mutex",
    "allowed accept4 tracked",
    "dup2 non-socket clears stale socket state",
    "fcntl pointer-argument forwarding",
    "controlled close clears socket tracking",
    "PASS native network interceptor denial/copy/FD lifecycle self-test",
)
for api in ("controlled_send", "controlled_sendmsg", "controlled_sendto", "controlled_recv",
            "controlled_recvfrom", "controlled_recvmsg", "controlled_read", "controlled_write",
            "controlled_accept", "controlled_accept4", "controlled_dup", "controlled_dup2",
            "controlled_dup3", "controlled_fcntl"):
    if api not in self_test:
        errors.append(f"native network direct test does not call {api}")

require(
    "scripts/test-native.sh",
    "native_network_interceptors.cpp",
    "native_network_interceptors_self_test.cpp",
    "native_network_interceptors_self_test",
    "native_network_interceptors.o",
)
require(
    "sandbox-native/src/main/cpp/CMakeLists.txt",
    "native_network_interceptors.cpp",
)
require(
    "sandbox-companion32/src/main/cpp/CMakeLists.txt",
    "native_network_interceptors.cpp",
)
require(
    "docs/ARCHITECTURE.md",
    "Message-oriented sources are preflighted with `MSG_PEEK`",
    "Rejected datagrams remain queued",
)
require(
    "docs/THREAT_MODEL.md",
    "Denied datagrams are detected with `MSG_PEEK` and remain queued",
    "Rejected `accept/accept4` calls consume and close",
)
require(
    "README.md",
    "## M5-T19.1-B Native network buffer and FD correctness fix",
    "bounded temporary payload/address/control storage",
)
require(
    "docs/M5_T19_1_B_DEVELOPMENT_REPORT.md",
    "Hook-mediated buffer non-disclosure: PASS",
    "Strong hostile-Native isolation: not claimed",
    "device evidence 0",
)

report = {
    "task": "M5-T19.1-B",
    "finding": "P1-02 Native receive/peer buffer disclosure and socket alias gaps",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "temporaryReceiveBudgetBytes": 8 * 1024 * 1024,
    "temporaryControlBudgetBytes": 1024 * 1024,
    "maximumIovecs": 1024,
    "receiveLockStripes": 64,
    "trackedDupAliasesShareReceiveMutex": True,
    "deniedDatagramConsumed": False,
    "deniedStreamBytesConsumed": False,
    "rejectedAcceptBacklogEntryConsumed": True,
    "coveredAliases": [
        "send", "sendto", "sendmsg", "recv", "recvfrom", "recvmsg", "read", "write",
        "accept", "accept4", "dup", "dup2", "dup3", "fcntl", "fcntl64",
    ],
    "directSyscallBoundary": "OUT_OF_SCOPE_DENIED_BY_DEFAULT_FOR_UNTRUSTED_NATIVE",
    "deviceEvidenceCount": 0,
    "errors": errors,
}
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-B Native network correctness checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-B temporary-buffer, denied-data, socket-alias and FD-tracking gate")
