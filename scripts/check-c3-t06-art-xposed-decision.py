#!/usr/bin/env python3
"""Static gate for the C3-T06 ART/Xposed Compatibility Extension decision."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
FORBIDDEN_RUNTIME = (
    "de.robv.android.xposed",
    "XposedBridge",
    "PineXposed",
    "xposed_init",
)
PRODUCTION_ROOTS = (
    ROOT / "sandbox-runtime/src/main",
    ROOT / "sandbox-framework/src/main",
    ROOT / "sandbox-native/src/main",
    ROOT / "sandbox-contract/src/main",
    ROOT / "app/src/main",
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


def scan_production() -> None:
    for root in PRODUCTION_ROOTS:
        if not root.is_dir():
            errors.append(f"missing production root {root.relative_to(ROOT)}")
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix.lower() not in {".java", ".kt", ".xml", ".aidl", ".cpp", ".h", ".gradle"}:
                if path.name != "xposed_init":
                    continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                errors.append(f"cannot read {path}: {exc}")
                continue
            for token in FORBIDDEN_RUNTIME:
                if token in text:
                    errors.append(
                        f"production {path.relative_to(ROOT)} contains forbidden {token}"
                    )


def main() -> int:
    adr = require(
        "docs/review/C3_T06_ART_XPOSED_EXTENSION_ADR.md",
        (
            "NOT_APPLICABLE",
            "arbitrary third-party Xposed",
            "F2-F5",
            "spoofer_project",
            "PackageService",
            "RuntimeBroker",
            "license",
            "C5-T04",
        ),
    )
    runner = require(
        "tools/capability/run_c3_t06_rd.py",
        (
            "RD测试",
            "resolve_rd_environment",
            "NOT_APPLICABLE",
            "FORBIDDEN_SERIALS",
        ),
    )
    inventory = require(
        "docs/sx-migration/SX_LEGACY_FEATURE_INVENTORY.md",
        ("xposed_init", "OBSOLETE"),
    )
    scan_production()
    for relative, text in (
        ("docs/review/C3_T06_ART_XPOSED_EXTENSION_ADR.md", adr),
        ("docs/sx-migration/SX_LEGACY_FEATURE_INVENTORY.md", inventory),
        ("tools/capability/run_c3_t06_rd.py", runner),
    ):
        if relative.endswith("run_c3_t06_rd.py"):
            continue
        for serial in FORBIDDEN_SERIALS:
            if serial in text:
                errors.append(f"{relative} contains historical ADB serial")
    if errors:
        print("FAIL C3-T06 ART/Xposed decision gate")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C3-T06 ART/Xposed decision inventory")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
