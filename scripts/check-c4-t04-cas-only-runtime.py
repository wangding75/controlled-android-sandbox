#!/usr/bin/env python3
"""Fail-closed CAS production gate: no BlackBox/NBB/Pine runtime."""

from __future__ import annotations

import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
FORBIDDEN_TOKENS = (
    "BlackBoxCore",
    "PineXposed",
    "top.niunaijun.blackbox",
    "xposed_init",
    "libpine.so",
    "libblackbox.so",
)
FORBIDDEN_GRADLE = (":Bcore", ":engine-bb", ":pine-core", ":pine-xposed", "blackbox")
PRODUCTION_MODULES = (
    "app",
    "sandbox-domain",
    "sandbox-contract",
    "sandbox-sdk",
    "sandbox-runtime",
    "sandbox-framework",
    "sandbox-native",
    "sandbox-companion32",
)
HOST_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def main() -> int:
    settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
    for token in FORBIDDEN_GRADLE:
        if token in settings:
            fail(f"settings.gradle includes {token}")
    for module in PRODUCTION_MODULES:
        gradle = ROOT / module / "build.gradle"
        if not gradle.is_file():
            fail(f"missing {gradle.relative_to(ROOT)}")
            continue
        text = gradle.read_text(encoding="utf-8")
        for token in FORBIDDEN_GRADLE:
            if token in text:
                fail(f"{module}/build.gradle depends on {token}")
        src = ROOT / module / "src/main"
        if not src.is_dir():
            continue
        for path in src.rglob("*"):
            if not path.is_file():
                continue
            if path.suffix.lower() not in {".java", ".kt", ".xml", ".aidl", ".cpp", ".h", ".gradle"}:
                if path.name != "xposed_init":
                    continue
            payload = path.read_text(encoding="utf-8", errors="replace")
            for token in FORBIDDEN_TOKENS:
                if token in payload:
                    fail(f"production {path.relative_to(ROOT)} contains {token}")
    design = ROOT / "docs/review/C4_T04_CAS_ONLY_RUNTIME_DESIGN.md"
    if not design.is_file():
        fail("missing design")
    else:
        text = design.read_text(encoding="utf-8")
        for token in ("DISCOVER", "CLASSIFY", "libpine", "CAS-only", "NOT_PROVEN", "ref/"):
            if token not in text:
                fail(f"design missing {token}")
        for serial in FORBIDDEN_SERIALS:
            if serial in text:
                fail("design contains historical ADB serial")
    if HOST_APK.is_file():
        with zipfile.ZipFile(HOST_APK) as archive:
            names = "\n".join(archive.namelist()).lower()
            for token in ("libpine", "libblackbox", "xposed_init", "niunaijun"):
                if token in names:
                    fail(f"host APK zip contains {token}")
    else:
        fail("host debug APK missing; assemble before APK content scan")
    if errors:
        print("FAIL C4-T04 CAS-only runtime")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C4-T04 CAS-only runtime")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
