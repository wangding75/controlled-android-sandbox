#!/usr/bin/env python3
"""Static gate for the C4-T02 SX CAS SDK adapter."""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAPPING = ROOT / "verification/catch-up/C4-T02/c4-t02-engine-mapping.json"
DESIGN = ROOT / "docs/review/C4_T02_SX_CAS_SDK_ADAPTER_DESIGN.md"
SDK = ROOT / "sandbox-sdk/src/main/java/com/warden/controlledsandbox/sdk/SandboxSdk.java"
ENGINE = ROOT / "sandbox-sdk/src/main/java/com/warden/controlledsandbox/sdk/CasSandboxEngine.java"
ADAPTER = ROOT / "app/src/main/java/com/warden/controlledsandbox/SxSandboxAdapter.java"
LAYER = ROOT / "app/src/main/java/com/warden/controlledsandbox/SandboxApplicationLayer.java"
DEBUG = ROOT / "app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java"
FORBIDDEN_SERIALS = ("127.0.0.1:16416", "127.0.0.1:16384", "127.0.0.1:7555")
FORBIDDEN_RUNTIME = ("BlackBoxCore", "PineXposed", "top.niunaijun.blackbox")
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
SDK_TOKENS = (
    "catalog(",
    "importPackage(",
    "importInstalledApplication(",
    "ensureInstance(",
    "cloneInstance(",
    "launch(",
    "stop(",
    "stopAll(",
    "clearData(",
    "deleteInstance(",
    "status(",
)
PRODUCTION_ROOTS = (
    ROOT / "sandbox-runtime/src/main",
    ROOT / "sandbox-framework/src/main",
    ROOT / "sandbox-native/src/main",
    ROOT / "sandbox-contract/src/main",
    ROOT / "sandbox-sdk/src/main",
    ROOT / "app/src/main",
)
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def main() -> int:
    mapping_text = read(MAPPING)
    data = json.loads(mapping_text) if mapping_text else {}
    if data.get("cas_only_host") is not True:
        fail("mapping must declare cas_only_host true")
    if data.get("adapter_owns_authority") is not False:
        fail("adapter must not own CAS authority")
    if data.get("va_pro_equivalent") != "NOT_PROVEN":
        fail("va_pro_equivalent must remain NOT_PROVEN")
    rows = {str(row.get("id")): row for row in data.get("engine_methods") or [] if isinstance(row, dict)}
    missing = [name for name in ENGINE_METHODS if name not in rows]
    if missing:
        fail(f"mapping missing engine methods {missing}")

    sdk = read(SDK)
    for token in SDK_TOKENS:
        if token not in sdk:
            fail(f"SandboxSdk missing {token}")

    engine = read(ENGINE)
    for name in ENGINE_METHODS:
        if not re.search(rf"\b{re.escape(name)}\s*\(", engine):
            fail(f"CasSandboxEngine missing {name}")
    if re.search(r"private\s+SandboxCatalog\b", engine):
        fail("CasSandboxEngine must not store a catalog field")
    if "sdk.catalog()" not in engine or "sdk.status()" not in engine:
        fail("CasSandboxEngine must re-read catalog/status from SandboxSdk")
    if "rollbackNewInstances" not in engine:
        fail("CasSandboxEngine missing clone rollback")
    if "NO_OP_CAS_HOST" not in engine:
        fail("onAttachBaseContext must remain a CAS no-op")

    adapter = read(ADAPTER)
    for token in ("importInstalledApplication(", "stopAll(", "rollbackClone(", "PACKAGE_NOT_INSTALLED"):
        if token not in adapter:
            fail(f"SxSandboxAdapter missing {token}")

    layer = read(LAYER)
    if "new CasSandboxEngine" not in layer:
        fail("SandboxApplicationLayer must construct CasSandboxEngine")
    for token in ("engine.installFromHost", "engine.launch", "engine.kill", "engine.clone",
                  "engine.clearData", "engine.uninstall"):
        if token not in layer:
            fail(f"SandboxApplicationLayer missing {token}")

    debug = read(DEBUG)
    if "c4-t02-engine" not in debug or "new CasSandboxEngine" not in debug:
        fail("DebugCommandActivity missing c4-t02-engine CasSandboxEngine path")
    if "new PackageServiceClient" in debug.split('if ("c4-t02-engine"', 1)[-1].split(
            'if ("lifecycle-clone"', 1)[0]:
        fail("c4-t02-engine command must not construct PackageServiceClient")

    ui_files = (
        ROOT / "app/src/main/java/com/warden/controlledsandbox/MainActivity.java",
        ROOT / "app/src/main/java/com/warden/controlledsandbox/ShortcutActivity.java",
        ROOT / "app/src/main/java/com/warden/controlledsandbox/InstanceSettingsActivity.java",
    )
    for path in ui_files:
        text = read(path)
        if "new PackageServiceClient" in text or "new RuntimeClient" in text:
            fail(f"{path.name} must not construct PackageServiceClient/RuntimeClient")

    design = read(DESIGN)
    for token in ("DISCOVER", "CLASSIFY", "CasSandboxEngine", "SandboxSdk", "observer",
                  "rollback", "NO_OP_CAS_HOST", "NOT_PROVEN", "C4-T03", "BlackBox"):
        if token not in design:
            fail(f"design missing {token}")
    for serial in FORBIDDEN_SERIALS:
        if serial in design or serial in mapping_text:
            fail("design/mapping contains historical ADB serial")

    for root in PRODUCTION_ROOTS:
        if not root.is_dir():
            fail(f"missing {root.relative_to(ROOT)}")
            continue
        for path in root.rglob("*"):
            if path.suffix.lower() not in {".java", ".kt", ".xml", ".aidl", ".cpp", ".h"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for token in FORBIDDEN_RUNTIME:
                if token in text:
                    fail(f"CAS production {path.relative_to(ROOT)} contains {token}")

    if errors:
        print("FAIL C4-T02 SX CAS SDK adapter")
        for item in errors:
            print(" -", item)
        return 1
    print("PASS C4-T02 SX CAS SDK adapter")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
