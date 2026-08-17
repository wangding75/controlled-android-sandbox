#!/usr/bin/env python3
"""Static check for T57-R03-P0A-03 ISOLATED_HOSTILE production profile."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

REQUIRED = [
    ROOT / "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeExecutionProfile.java",
    ROOT / "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/HostileAdmissionSnapshot.java",
    ROOT / "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/HostileCapabilityRequest.java",
    ROOT / "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IHostileCapabilityBroker.aidl",
    ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistry.java",
    ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityBrokerStub.java",
    ROOT / "sandbox-native/src/main/cpp/hostile_seccomp.cpp",
    ROOT / "sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativePolicy.java",
]


def main() -> int:
    for path in REQUIRED:
        if not path.is_file():
            errors.append(f"missing {path.relative_to(ROOT)}")
    profile = (ROOT / "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/NativeExecutionProfile.java").read_text(encoding="utf-8")
    for token in ("TRUSTED_COMPAT", "ISOLATED_HOSTILE"):
        if token not in profile:
            errors.append(f"profile missing {token}")
    native = (ROOT / "sandbox-native/src/main/cpp/hostile_seccomp.cpp").read_text(encoding="utf-8")
    for token in ("SYS_socket", "SYS_connect", "SYS_ptrace", "SYS_execve", "SECCOMP_RET_ERRNO", "PR_SET_NO_NEW_PRIVS"):
        if token not in native:
            errors.append(f"hostile_seccomp missing {token}")
    if "SECCOMP_RET_KILL" in native:
        errors.append("first-version hostile seccomp must not KILL")
    policy = (ROOT / "sandbox-native/src/main/java/com/warden/controlledsandbox/nativebridge/NativePolicy.java").read_text(encoding="utf-8")
    if "installHostileSeccomp" not in policy:
        errors.append("NativePolicy missing installHostileSeccomp")
    isolated = (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/BaseIsolatedGuestProcessService.java").read_text(encoding="utf-8")
    if "installHostileSeccomp" not in isolated:
        errors.append("isolated worker must install hostile seccomp only when profile is hostile")
    broker = (ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistry.java").read_text(encoding="utf-8")
    for token in ("GENERATION_MISMATCH", "OWNER_MISMATCH", "CAPABILITY_REVOKED", "HOSTILE_NETWORK_ENDPOINT_NOT_ALLOWLISTED"):
        if token not in broker:
            errors.append(f"registry missing {token}")
    if errors:
        print("FAIL native hostile profile")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS native hostile profile inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
