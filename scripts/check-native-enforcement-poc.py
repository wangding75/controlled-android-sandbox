#!/usr/bin/env python3
"""Static check for T57-R03-P0A-02 Native enforcement POC artifacts."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tools" / "capability") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools" / "capability"))

from common import load_yaml  # noqa: E402

POC_MATRIX = ROOT / "docs/native/T57_R03_NATIVE_ENFORCEMENT_POC_MATRIX.yaml"
DECISION = ROOT / "docs/native/T57_R03_NATIVE_BOUNDARY_DECISION.md"
NATIVE_CPP = ROOT / "verification/native-enforcement/enforcement_native.cpp"
HOST_SERVICE = ROOT / "app/src/debug/java/com/warden/controlledsandbox/NativeEnforcementIsolatedService.java"
HOST_BROKER = ROOT / "app/src/debug/java/com/warden/controlledsandbox/NativeEnforcementBroker.java"
HOST_CAMPAIGN = ROOT / "app/src/debug/java/com/warden/controlledsandbox/NativeEnforcementCampaign.java"
DEBUG_MANIFEST = ROOT / "app/src/debug/AndroidManifest.xml"
RELEASE_MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
DEBUG_COMMAND = ROOT / "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java"
RD_RUNNER = ROOT / "tools/capability/run_native_enforcement_rd.py"

REQUIRED_CASES = [
    "NATIVE-ENF-FS-001",
    "NATIVE-ENF-FS-002",
    "NATIVE-ENF-FS-003",
    "NATIVE-ENF-FS-004",
    "NATIVE-ENF-FS-005",
    "NATIVE-ENF-NET-001",
    "NATIVE-ENF-NET-002",
    "NATIVE-ENF-NET-003",
    "NATIVE-ENF-NET-004",
]
REQUIRED_MATRIX_FIELDS = {
    "case_id",
    "domain",
    "process_mode",
    "uid",
    "abi",
    "libc_result",
    "syscall_result",
    "raw_result",
    "broker_result",
    "kernel_policy",
    "seccomp_result",
    "conclusion",
    "evidence",
}


def main() -> int:
    errors: list[str] = []
    for path in (
        POC_MATRIX,
        DECISION,
        NATIVE_CPP,
        HOST_SERVICE,
        HOST_BROKER,
        HOST_CAMPAIGN,
        DEBUG_MANIFEST,
        RELEASE_MANIFEST,
        DEBUG_COMMAND,
        RD_RUNNER,
    ):
        if not path.is_file():
            errors.append(f"missing required file: {path.relative_to(ROOT)}")

    if RELEASE_MANIFEST.is_file():
        text = RELEASE_MANIFEST.read_text(encoding="utf-8")
        for token in (
            "NativeEnforcementIsolatedService",
            "native_enf_iso",
            "INativeEnforcementBroker",
        ):
            if token in text:
                errors.append(f"release manifest must not contain POC token {token}")

    if DEBUG_MANIFEST.is_file():
        text = DEBUG_MANIFEST.read_text(encoding="utf-8")
        if "NativeEnforcementIsolatedService" not in text:
            errors.append("debug manifest missing isolated POC service")
        if 'android:isolatedProcess="true"' not in text:
            errors.append("debug isolated service must set isolatedProcess")
        if 'android:exported="false"' not in text:
            errors.append("debug isolated service must not be exported")

    if DEBUG_COMMAND.is_file():
        text = DEBUG_COMMAND.read_text(encoding="utf-8")
        if "native-enforcement" not in text:
            errors.append("DebugCommand missing native-enforcement command")
        if "NativeEnforcementCampaign" not in text:
            errors.append("DebugCommand must invoke the host campaign, not guest runtime")
        if "KI-R03-NATIVE-010" not in text:
            errors.append("DebugCommand must document that guest runtime is not used")

    if HOST_BROKER.is_file():
        text = HOST_BROKER.read_text(encoding="utf-8")
        for token in ("looksLikePath", "SESSION_MISMATCH", "CAPABILITY_MISMATCH", "allowlist"):
            if token not in text:
                errors.append(f"broker missing allowlist control {token}")

    if NATIVE_CPP.is_file():
        text = NATIVE_CPP.read_text(encoding="utf-8")
        for token in (
            "PR_SET_NO_NEW_PRIVS",
            "SECCOMP_RET_ERRNO",
            "__NR_getppid",
            "svc #0",
            "syscall",
            "int $0x80",
            "SYS_openat",
        ):
            if token not in text:
                errors.append(f"enforcement native missing token {token}")
        if "sandbox-native" in text or "controlled_sandbox::" in text:
            errors.append("enforcement native must not link production interceptors")
        if "SECCOMP_RET_KILL" in text:
            errors.append("first-version seccomp probe must not use KILL_PROCESS")

    if RD_RUNNER.is_file():
        text = RD_RUNNER.read_text(encoding="utf-8")
        if "127.0.0.1:16416" in text and "FORBIDDEN_SERIALS" not in text:
            errors.append("RD runner must not hard-code historical serials")
        if "resolve_rd_environment" not in text:
            errors.append("RD runner must resolve RD测试 dynamically")
        if "native-enforcement" not in text:
            errors.append("RD runner must invoke native-enforcement")

    if DECISION.is_file():
        text = DECISION.read_text(encoding="utf-8")
        for token in ("P0A-02 POC Findings", "BROKER_FS_CAPABILITY", "BROKER_NET_CAPABILITY", "Option B"):
            if token not in text:
                errors.append(f"decision doc missing P0A-02 token {token}")

    if POC_MATRIX.is_file():
        data = load_yaml(POC_MATRIX)
        cases = list(data.get("cases") or [])
        ids = [item.get("case_id") for item in cases]
        missing = [case for case in REQUIRED_CASES if case not in ids]
        if missing:
            errors.append(f"POC matrix missing cases: {missing}")
        for item in cases:
            absent = [field for field in REQUIRED_MATRIX_FIELDS if field not in item]
            if absent:
                errors.append(f"{item.get('case_id')}: missing {absent}")

    if errors:
        print("FAIL native enforcement POC inventory")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS native enforcement POC inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
