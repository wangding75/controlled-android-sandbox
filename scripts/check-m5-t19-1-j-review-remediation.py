#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
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


pool = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java",
    "service.linkToDeath(this, 0)", "&& service.isBinderAlive()",
    "binderToken = service", "guest = candidate", "if (!published && linked)",
)
if pool.index("service.linkToDeath(this, 0)") > pool.index("binderToken = service"):
    errors.append("Guest Binder is published before death registration")
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java",
    "testConnectionIsNotPublishedBeforeDeathLinkCompletes",
    "second call observed Binder before death registration completed",
)

codec = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodec.java",
    "ConcurrentHashMap<String, Object> JVM_LOCKS", "FileLock lock = channel.lock()",
    "if (!lock.isValid())",
    "Registry registry = loadRegistry()", "UUID.randomUUID()",
    "LEGACY_NAME_INDEX_AMBIGUOUS", "isProvablyUniqueLegacyLogical",
    "decodeReversible", "pruneMissingHashClaims",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestStorageNameCodecSelfTest.java",
    "testIndependentCodecInstancesMergeRegistryUpdates",
    "testIndependentOsProcessesMergeRegistryUpdates",
    "testLegacyEnumerationMigratesUniqueAndRejectsAmbiguous",
    "testShortNamesDoNotGrowRegistry",
)

ownership = require(
    "scripts/check-critical-test-ownership.py",
    "main_reachable_methods", "reachable_reference", "runtimeExecutionReceipt",
    "static-android-test-execution.json", "dead helper accepted",
)
runner = require(
    "tools/static_android_compile.py",
    "static-android-test-execution.json", "'completed': completed",
    "'executedTests': sorted(passed)", "write_execution_receipt(True)",
)
verify = require(
    "scripts/verify-all.sh",
    "verify-all-before.patch", "verify-all-after.patch",
    "cmp build/verification/verify-all-before.patch",
    "python3 tools/static_android_compile.py",
    "python3 scripts/check-critical-test-ownership.py",
)
if verify.index("python3 tools/static_android_compile.py") > verify.index(
        "python3 scripts/check-critical-test-ownership.py"):
    errors.append("critical ownership gate runs before the execution receipt is generated")

tracked_reports = subprocess.run(
    ["git", "ls-files", "verification/m5-t19-1-*.json",
     "verification/m5-t19-critical-test-ownership.json"],
    cwd=ROOT, text=True, capture_output=True, check=True,
).stdout.splitlines()
if tracked_reports:
    errors.append("generated live-source reports remain tracked: " + ", ".join(tracked_reports))
for path in (ROOT / "scripts").glob("check-m5-t19-1-*.py"):
    if path.resolve() == Path(__file__).resolve():
        continue
    if 'ROOT / "verification/m5-t19-1-' in path.read_text(encoding="utf-8"):
        errors.append(f"{path.name} still writes generated output into tracked verification")

report = {
    "task": "M5-T19.1-J",
    "finding": "recent F/G/H/I review remediation",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "fixedFindings": [
        "critical ownership dead-code false positive",
        "Guest Binder publication before death registration",
        "cross-instance storage registry lost update",
        "legacy enumeration omission",
        "legacy first-access-wins ownership",
        "reversible-name registry growth and stale claims",
        "tracked generated verification drift",
    ],
    "deviceEvidenceCount": 0,
    "errors": errors,
}
out = ROOT / "build/verification/m5-t19-1-j-review-remediation.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
if errors:
    print("FAIL M5-T19.1-J recent-review remediation", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-J recent-review remediation and read-only verification governance")
