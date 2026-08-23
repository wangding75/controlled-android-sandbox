#!/usr/bin/env python3
"""Static freeze gate for C4-T01 SX dependency/feature/runtime inventory."""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FREEZE = ROOT / "verification/catch-up/C4-T01/c4-t01-freeze.json"
errors: list[str] = []
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
LEGAL = {
    "REPLACE",
    "REIMPLEMENT_DATA",
    "DELETE",
    "DROP_NON_BUSINESS",
    "GENERIC_ALREADY",
    "OPTIONAL_SKU",
}
ENGINE_METHODS = (
    "initialize",
    "onAttachBaseContext",
    "onAppCreate",
    "isReady",
    "installFromHost",
    "installFromApk",
    "uninstall",
    "clearData",
    "listInstalled",
    "get",
    "isInstalled",
    "launch",
    "kill",
    "killAll",
    "clone",
    "createShortcut",
    "setDisplayName",
)
CAS_SDK = ROOT / "sandbox-sdk/src/main/java/com/warden/controlledsandbox/sdk/SandboxSdk.java"
CAS_ADAPTER = ROOT / "app/src/main/java/com/warden/controlledsandbox/SxSandboxAdapter.java"
PRODUCTION_ROOTS = (
    ROOT / "sandbox-runtime/src/main",
    ROOT / "sandbox-framework/src/main",
    ROOT / "sandbox-native/src/main",
    ROOT / "sandbox-contract/src/main",
    ROOT / "sandbox-sdk/src/main",
    ROOT / "app/src/main",
)


def fail(message: str) -> None:
    errors.append(message)


def sx_root() -> Path | None:
    override = os.environ.get("SX_SOURCE", "").strip()
    candidate = Path(override) if override else Path(r"D:\github\all_project\sx")
    if (candidate / "settings.gradle").is_file():
        return candidate
    return None


def main() -> int:
    if not FREEZE.is_file():
        fail("missing freeze JSON")
        print("FAIL C4-T01 SX freeze")
        for item in errors:
            print(" -", item)
        return 1
    data = json.loads(FREEZE.read_text(encoding="utf-8"))
    if data.get("cas_only_host") is not True:
        fail("freeze must declare cas_only_host true")
    if data.get("va_pro_equivalent") != "NOT_PROVEN":
        fail("va_pro_equivalent must remain NOT_PROVEN")
    if data.get("migration_order") != ["C4-T02", "C4-T03", "C4-T04", "C4-T05"]:
        fail("migration_order must be C4-T02..T05")

    def check_rows(key: str, required_ids: tuple[str, ...] | None = None) -> dict[str, dict]:
        rows = data.get(key) or []
        if not isinstance(rows, list) or not rows:
            fail(f"{key} missing")
            return {}
        by_id: dict[str, dict] = {}
        for row in rows:
            if not isinstance(row, dict):
                fail(f"{key} has non-object row")
                continue
            item_id = str(row.get("id") or row.get("class") or row.get("asset") or row.get("library") or "")
            if not item_id:
                fail(f"{key} row missing id")
                continue
            disposition = str(row.get("disposition") or "")
            if disposition not in LEGAL:
                fail(f"{key}/{item_id} illegal disposition {disposition}")
            if disposition not in {"DELETE", "DROP_NON_BUSINESS", "OPTIONAL_SKU"}:
                if not str(row.get("cas_target") or "").strip():
                    fail(f"{key}/{item_id} missing cas_target")
            by_id[item_id] = row
        if required_ids:
            missing = [item for item in required_ids if item not in by_id]
            if missing:
                fail(f"{key} missing ids {missing}")
        return by_id

    engine = check_rows("engine_methods", ENGINE_METHODS)
    check_rows("gradle_modules")
    check_rows("startup_entries")
    check_rows("ui_entries")
    check_rows("f1_f5_hooks")
    check_rows("data_stores")
    check_rows("dynamic_loads")
    check_rows("unknown_or_missing")
    check_rows("blockers")
    if "DELETE" not in {row.get("disposition") for row in data.get("gradle_modules") or []}:
        fail("Gradle graph must mark Bcore/Pine DELETE")
    if engine.get("launch", {}).get("cas_target") != "SandboxSdk.launch":
        fail("launch must map to SandboxSdk.launch")

    sdk = CAS_SDK.read_text(encoding="utf-8") if CAS_SDK.is_file() else ""
    for token in (
        "catalog(",
        "importPackage(",
        "ensureInstance(",
        "cloneInstance(",
        "launch(",
        "stop(",
        "clearData(",
        "deleteInstance(",
        "status(",
    ):
        if token not in sdk:
            fail(f"SandboxSdk missing {token}")
    if not CAS_ADAPTER.is_file():
        fail("missing SxSandboxAdapter")
    for root in PRODUCTION_ROOTS:
        if not root.is_dir():
            fail(f"missing {root.relative_to(ROOT)}")
            continue
        for path in root.rglob("*"):
            if path.suffix.lower() not in {".java", ".kt", ".xml", ".aidl", ".cpp", ".h"}:
                if path.name != "xposed_init":
                    continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for token in ("BlackBoxCore", "PineXposed", "top.niunaijun.blackbox"):
                if token in text:
                    fail(f"CAS production {path.relative_to(ROOT)} contains {token}")

    design = ROOT / "docs/review/C4_T01_SX_DEPENDENCY_FREEZE_DESIGN.md"
    if not design.is_file():
        fail("missing design")
    else:
        text = design.read_text(encoding="utf-8")
        for token in ("SandboxEngine", "C4-T02", "DELETE", "ConfigProvider", "BlackBoxCore", "VA Pro"):
            if token not in text:
                fail(f"design missing {token}")
        for serial in FORBIDDEN_SERIALS:
            if serial in text:
                fail("design contains historical ADB serial")

    source = sx_root()
    if source is None:
        fail("SX source tree not found; set SX_SOURCE or keep D:/github/all_project/sx")
    else:
        engine_src = source / "sandbox-api/src/main/java/com/sx/app/sandbox/SandboxEngine.java"
        if not engine_src.is_file():
            fail("SX SandboxEngine.java missing")
        else:
            text = engine_src.read_text(encoding="utf-8")
            for method in ENGINE_METHODS:
                if not re.search(rf"\b{re.escape(method)}\s*\(", text):
                    fail(f"live SX SandboxEngine missing {method}")
        settings = (source / "settings.gradle").read_text(encoding="utf-8")
        for module in (":app", ":sandbox-api", ":engine-bb", ":Bcore", ":Bcore:pine-core"):
            if module not in settings and f"include '{module}'" not in settings:
                if module not in settings:
                    fail(f"live SX settings.gradle missing {module}")
        engine_bb = (source / "engine-bb/build.gradle").read_text(encoding="utf-8")
        for dep in (":Bcore", ":Bcore:pine-core", ":Bcore:pine-xposed"):
            if dep not in engine_bb:
                fail(f"engine-bb/build.gradle missing {dep}")
        xposed_init = source / "app/src/main/assets/xposed_init"
        if not xposed_init.is_file():
            fail("live SX missing assets/xposed_init")

    if errors:
        print("FAIL C4-T01 SX freeze")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C4-T01 SX dependency freeze")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
