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
    value = path.read_text(encoding="utf-8-sig")
    for token in tokens:
        if token not in value: errors.append(f"{rel} missing: {token}")
    return value


store = require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
                "private boolean closed", "requireOpenForMutation()",
                "VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED",
                "observerDispatcher.dispatch(\"CLIPBOARD\"",
                "if (closed) return;", "closed = true")
if len(store.splitlines()) > 1500:
    errors.append("VirtualSystemServiceStore exceeds the existing 1500-line architecture limit")

require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceObserverDispatcher.java",
        "Best-effort observer delivery", "scheduler.execute", "catch (RuntimeException rejected)",
        "VIRTUAL_OBSERVER_DISPATCH_FAILED")
require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreCommitConsistencySelfTest.java",
        "testCommittedMutationSurvivesRejectedObserverDispatch",
        "testClosedStoreRejectsBeforeMutation",
        "testWaitingMutationLosesToCloseWithoutPersisting",
        "RejectedExecutionException", "VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED")

runner = require("tools/static_android_compile.py", "VirtualSystemServiceStoreCommitConsistencySelfTest")
if runner.count("VirtualSystemServiceStoreCommitConsistencySelfTest") != 1:
    errors.append("static Android runner must execute Store consistency test exactly once")
verify = require("scripts/verify-all.sh", "check-m5-t19-1-g-store-commit-consistency.py")
if verify.count("check-m5-t19-1-g-store-commit-consistency.py") != 1:
    errors.append("verify-all must execute the Store consistency gate exactly once")

report_paths = [
    ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    ROOT / "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceObserverDispatcher.java",
    ROOT / "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreCommitConsistencySelfTest.java",
    ROOT / "tools/static_android_compile.py",
]
digest = hashlib.sha256()
for path in report_paths:
    digest.update(str(path.relative_to(ROOT)).encode("utf-8"))
    digest.update(b"\0")
    digest.update(path.read_bytes())
    digest.update(b"\0")
report = {
    "gate": "m5-t19-1-g-store-commit-consistency",
    "sourceStatus": "PASS" if not errors else "FAIL",
    "storeLines": len(store.splitlines()),
    "existingStoreLineLimit": 1500,
    "closedMutationContract": "VIRTUAL_SYSTEM_SERVICE_STORE_CLOSED",
    "observerDeliveryContract": "best-effort-after-durable-commit",
    "directTest": "VirtualSystemServiceStoreCommitConsistencySelfTest",
    "staticRunnerExecutions": runner.count("VirtualSystemServiceStoreCommitConsistencySelfTest"),
    "inputDigestSha256": digest.hexdigest(),
}
(ROOT / "verification/m5-t19-1-g-store-commit-consistency.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")

if errors:
    print("FAIL M5-T19.1-G Store commit consistency", file=sys.stderr)
    for error in errors: print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T19.1-G Store closed-state and post-commit notification consistency")
