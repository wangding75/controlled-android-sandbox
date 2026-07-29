#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

# Freeze the legacy Bundle surface. Any new Bundle business method must be typed first.
manifest_path = ROOT / "verification/m4-t18-legacy-bundle-contracts.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 1:
    errors.append("legacy Bundle contract manifest must use schemaVersion 1")
allowed: set[tuple[str, str]] = set()
for item in manifest.get("contracts", []):
    path = item.get("path", "")
    signature = item.get("signature", "").strip()
    if not path or not signature or not item.get("reason") or not item.get("typedBoundary"):
        errors.append(f"invalid legacy Bundle contract entry: {item!r}")
        continue
    key = (path, signature)
    if key in allowed:
        errors.append(f"duplicate legacy Bundle contract entry: {path}: {signature}")
    allowed.add(key)

actual: set[tuple[str, str]] = set()
for path in sorted((ROOT / "sandbox-contract/src/main/aidl").rglob("*.aidl")):
    relative = path.relative_to(ROOT).as_posix()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("//") or line.startswith("import "):
            continue
        if "Bundle" in line and line.endswith(";"):
            actual.add((relative, line))
for key in sorted(actual - allowed):
    errors.append(f"unapproved AIDL Bundle business contract: {key[0]}: {key[1]}")
for key in sorted(allowed - actual):
    errors.append(f"stale Bundle allowlist entry: {key[0]}: {key[1]}")

# Production source-size threshold: large coordinators must be decomposed before device testing.
production_roots = [
    ROOT / "app/src/main",
    ROOT / "sandbox-domain/src/main",
    ROOT / "sandbox-framework/src/main",
    ROOT / "sandbox-runtime/src/main",
    ROOT / "sandbox-native/src/main",
    ROOT / "sandbox-companion32/src/main",
    ROOT / "sandbox-contract/src/main",
]
limits = {".java": 1800, ".kt": 1800, ".cpp": 1600, ".cc": 1600, ".c": 1600}
for root in production_roots:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        limit = limits.get(path.suffix)
        if limit is None or not path.is_file():
            continue
        count = len(path.read_text(encoding="utf-8", errors="replace").splitlines())
        if count > limit:
            errors.append(f"God Class/source unit exceeds {limit} lines: {path.relative_to(ROOT)} ({count})")

store = ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java"
store_text = store.read_text(encoding="utf-8")
for forbidden in ["org.json.", "FileOutputStream", "Files.move", "new JSONObject("]:
    if forbidden in store_text:
        errors.append(f"VirtualSystemServiceStore still owns persistence concern: {forbidden}")
for required in [
    "VirtualSystemServiceStorePersistence",
    "VirtualSystemServiceStoreCodec.encode",
    "VirtualSystemServiceStoreCodec.decode",
    "MutationSnapshot",
]:
    if required not in store_text:
        errors.append(f"VirtualSystemServiceStore closure evidence missing: {required}")

persistence = (ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStorePersistence.java").read_text(encoding="utf-8")
for required in ["MAX_PAYLOAD_BYTES", "MAX_FILE_BYTES", "CRC32", "ATOMIC_MOVE", ".corrupt"]:
    if required not in persistence:
        errors.append(f"bounded persistence evidence missing: {required}")

ledger = (ROOT / "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java").read_text(encoding="utf-8")
if "class ActivityTaskMutableTask" in ledger or "class ActivityTaskMutableActivity" in ledger:
    errors.append("ActivityTaskLedger still embeds mutable state model classes")
for required_file in [
    "ActivityTaskMutableTask.java",
    "ActivityTaskMutableActivity.java",
    "ActivityTaskTextPolicy.java",
]:
    path = ROOT / "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity" / required_file
    if not path.is_file():
        errors.append(f"Activity/Task decomposition file missing: {required_file}")

if errors:
    print("FAIL M4-T18 source-closure checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print(
    "PASS M4-T18 source-closure checks: "
    f"legacy Bundle methods={len(actual)}; production source units within thresholds"
)
