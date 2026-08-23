#!/usr/bin/env python3
"""Static acceptance gate for the C3-T04 hostile native isolation corpus."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
REQUIRED_CASES = (
    "C3-T04-ISO-001",
    "C3-T04-FS-CORE-001",
    "C3-T04-FS-GUEST-001",
    "C3-T04-FS-UNGRANTED-001",
    "C3-T04-FD-INHERIT-001",
    "C3-T04-BINDER-001",
    "C3-T04-SOCKET-001",
    "C3-T04-PTRACE-001",
    "C3-T04-CLONE-001",
    "C3-T04-EXEC-001",
    "C3-T04-CAP-GRANT-001",
    "C3-T04-CAP-SCOPE-001",
    "C3-T04-CAP-REV-001",
    "C3-T04-CAP-EXPIRY-001",
    "C3-T04-CAP-REPLAY-001",
    "C3-T04-DEATH-001",
    "C3-T04-NET-BROKER-001",
)


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
    design = require(
        "docs/review/C3_T04_HOSTILE_NATIVE_ISOLATION_DESIGN.md",
        (
            "C3-T04",
            "ISOLATED_HOSTILE",
            "TRUSTED_COMPAT",
            "Broker-only",
            "KERNEL_LIMIT_EXPOSED",
            "capability scope/revision/expiry",
            "VA Pro",
            "RD测试",
        ),
    )
    _ = design
    campaign = require(
        "app/src/debug/java/com/warden/controlledsandbox/HostileProductionCampaign.java",
        REQUIRED_CASES
        + (
            "c3-t04-hostile-results.json",
            "CAPABILITY_EXPIRED",
            "CAPABILITY_REVOKED",
            "waitPidGone",
            "c3t04Pass",
        ),
    )
    child = require(
        "app/src/debug/java/com/warden/controlledsandbox/NativeEnforcementChild.java",
        (
            "C3-T04-FS-CORE-001",
            "C3-T04-FS-GUEST-001",
            "C3-T04-PTRACE-001",
            "C3-T04-EXEC-001",
            "C3-T04-CLONE-001",
            "C3-T04-BINDER-001",
            "C3-T04-FD-INHERIT-001",
            "probeAttack",
        ),
    )
    native = require(
        "verification/native-enforcement/enforcement_native.cpp",
        (
            "nativeProbeAttack",
            "SYS_ptrace",
            "SYS_execve",
            "/dev/binder",
            "/proc/self/fd",
            "host_private_leaks",
        ),
    )
    debug = require(
        "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
        ("c3-t04-hostile", "C3_T04_ATTACK_MATRIX_FAILED"),
    )
    runner = require(
        "tools/capability/run_c3_t04_rd.py",
        (
            "RD测试",
            "resolve_rd_environment",
            "c3-t04-hostile",
            "mumu_instance",
        )
        + REQUIRED_CASES,
    )
    registry = require(
        "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistry.java",
        (
            "GENERATION_MISMATCH",
            "OWNER_MISMATCH",
            "CAPABILITY_REVOKED",
            "CAPABILITY_EXPIRED",
            "HOSTILE_NETWORK_ENDPOINT_NOT_ALLOWLISTED",
        ),
    )
    selftest = require(
        "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistrySelfTest.java",
        ("CAPABILITY_EXPIRED", "CAPABILITY_REVOKED", "GENERATION_MISMATCH"),
    )
    issues = require(
        "docs/review/KNOWN_ISSUES.yaml",
        ("KI-R03-044", "C3-T04 hostile native"),
    )
    manifest = require(
        "app/src/debug/AndroidManifest.xml",
        ('android:isolatedProcess="true"', "NativeEnforcementIsolatedService"),
    )
    release = require("app/src/main/AndroidManifest.xml", ())
    if "NativeEnforcementIsolatedService" in release:
        errors.append("release manifest must not contain isolated POC service")
    for relative, text in (
        ("tools/capability/run_c3_t04_rd.py", runner),
        ("app/src/debug/java/com/warden/controlledsandbox/HostileProductionCampaign.java", campaign),
        ("app/src/debug/java/com/warden/controlledsandbox/NativeEnforcementChild.java", child),
        ("verification/native-enforcement/enforcement_native.cpp", native),
        ("app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java", debug),
        ("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistrySelfTest.java", selftest),
        ("docs/review/KNOWN_ISSUES.yaml", issues),
        ("app/src/debug/AndroidManifest.xml", manifest),
        ("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/hostile/HostileCapabilityRegistry.java", registry),
    ):
        if relative == "tools/capability/run_c3_t04_rd.py":
            for serial in FORBIDDEN_SERIALS:
                if serial in text and "FORBIDDEN" not in text.split(serial, 1)[0][-80:]:
                    # Allowed only as a negative assertion.
                    if "FORBIDDEN" not in text and "forbidden" not in text.lower():
                        errors.append(f"{relative} hard-coded serial {serial}")
        elif any(serial in text for serial in FORBIDDEN_SERIALS):
            errors.append(f"{relative} contains historical ADB serial")
    if errors:
        print("FAIL C3-T04 hostile isolation gate")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C3-T04 hostile isolation inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
