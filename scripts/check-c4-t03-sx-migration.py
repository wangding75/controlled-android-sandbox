#!/usr/bin/env python3
"""Static gate for C4-T03 SX data migration."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
errors: list[str] = []


def require(relative: str, tokens: tuple[str, ...]) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing {relative}")
        return ""
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing {token}")
    for serial in FORBIDDEN_SERIALS:
        if serial in text:
            errors.append(f"{relative} contains historical ADB serial")
    return text


def main() -> int:
    require(
        "docs/review/C4_T03_SX_DATA_MIGRATION_DESIGN.md",
        ("DISCOVER", "CLASSIFY", "sx-config-v1", "IDEMPOTENT", "rollback",
         "instance-scoped", "NOT_PROVEN", "BlackBoxCore"),
    )
    require(
        "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/migration/SxInstanceProfileMigrator.java",
        ("COMMITTED", "IDEMPOTENT", "INTERRUPTED", "abortAfterBackup"),
    )
    require(
        "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/migration/SxMigrationRecord.java",
        ("sourceKept", "COMMITTED", "ROLLED_BACK"),
    )
    require(
        "app/src/main/java/com/warden/controlledsandbox/SxMigrationHostStore.java",
        ("snapshotProfiles", "restoreProfiles", "writeMedia", "sx-migration"),
    )
    debug = require(
        "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
        ("c4-t03-migrate", "CROSS_USER_PROFILE_LEAK", "OLD_SOURCE_DROPPED"),
    )
    if "BlackBoxCore" in debug.split("c4-t03-migrate", 1)[-1].split("c4-t02-engine", 1)[0]:
        errors.append("c4-t03-migrate must not reference BlackBoxCore")
    require(
        "sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/migration/SxMigrationSelfTest.java",
        ("idempotent", "rollback", "users must not share latitude"),
    )
    if errors:
        print("FAIL C4-T03 SX data migration")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C4-T03 SX data migration")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
