#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def require(rel: str, *tokens: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    text = path.read_text(encoding="utf-8-sig")
    for token in tokens:
        if token not in text:
            errors.append(f"{rel} missing: {token}")
    return text


codec = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodec.java",
    'LEGACY_COLLISION = "LEGACY_NAME_COLLISION_AMBIGUOUS"',
    'PHYSICAL_COLLISION = "STORAGE_NAME_PHYSICAL_COLLISION"',
    '"v2_" + Base64.getUrlEncoder().withoutPadding()',
    '"v2h_" + hex(sha256(bytes))',
    "crc32(payload)",
    "raw.getFD().sync()",
    "StandardCopyOption.ATOMIC_MOVE",
    "Persist the unique legacy claim before moving any artifact",
    "Move the main artifact last",
    "registryPersisted",
)
if "replaceAll(\"[^A-Za-z0-9._-]\", \"_\")" not in codec:
    errors.append("codec must preserve the exact legacy mapping only for migration detection")
if len(codec.splitlines()) > 500:
    errors.append("GuestStorageNameCodec exceeds the 500-line production-class threshold")

context = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java",
    "private final GuestStorageNameCodec storageNames",
    '"database", name, "", "", "-journal", "-wal", "-shm"',
    '"shared_preferences", name, "", ".cspf", ".tmp"',
    'storageNames.resolve(getFilesDir(), "file", name, "", "")',
    'storageNames.resolve(dataRoot, "dir", name, "app_", "")',
    '"external_" + category',
    'storageNames.listExisting(getFilesDir(), "file")',
)
if "safeName(" in context:
    errors.append("GuestContext still contains or calls the lossy safeName mapping")

test = require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodecSelfTest.java",
    "testCollisionFreeContextSurfaces",
    "testLegacyMigrationAndAmbiguity",
    "testLongNameRestartStability",
    '"prefs a"',
    '"prefs?a"',
    '"a/b"',
    '"資料"',
    '"pet-🐾"',
    '"."',
    '".."',
    "LEGACY_COLLISION",
)
for direct_call in (
    "testCollisionFreeContextSurfaces();",
    "testLegacyMigrationAndAmbiguity();",
    "testLongNameRestartStability();",
):
    if test.count(direct_call) != 1:
        errors.append(f"storage-name direct test must execute exactly once: {direct_call}")

runner = require("tools/static_android_compile.py", "GuestStorageNameCodecSelfTest")
if runner.count("GuestStorageNameCodecSelfTest") != 1:
    errors.append("static Android runner must execute GuestStorageNameCodecSelfTest exactly once")
verify = require("scripts/verify-all.sh", "check-m5-t19-1-i-guest-storage-name-codec.py")
if verify.count("check-m5-t19-1-i-guest-storage-name-codec.py") != 1:
    errors.append("verify-all must execute the P2-03 gate exactly once")
require("README.md", "M5-T19.1-I Guest storage-name collision fix")
require("docs/ARCHITECTURE.md", "Guest storage logical-name mapping")
require("docs/M5_T19_1_I_DEVELOPMENT_REPORT.md", "Legacy collision fail-closed")

digest = hashlib.sha256()
for rel in (
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodec.java",
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java",
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodecSelfTest.java",
):
    digest.update(rel.encode())
    digest.update(b"\0")
    digest.update((ROOT / rel).read_bytes())
    digest.update(b"\0")
report = {
    "task": "M5-T19.1-I",
    "finding": "P2-03 lossy GuestContext safeName mapping caused silent storage aliasing",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "normalEncoding": "v2_base64url_utf8",
    "longEncoding": "v2h_sha256_with_persistent_owner_claim",
    "legacyCollisionError": "LEGACY_NAME_COLLISION_AMBIGUOUS",
    "coveredSurfaces": ["shared_preferences", "database", "file", "getDir", "external_files_type"],
    "androidFilesystemEvidenceCount": 0,
    "deviceEvidenceCount": 0,
    "inputDigestSha256": digest.hexdigest(),
    "errors": errors,
}
(ROOT / "verification/m5-t19-1-i-guest-storage-name-codec.json").write_text(
    json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-I Guest storage-name codec", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-I collision-free Guest storage names and fail-closed legacy migration")
