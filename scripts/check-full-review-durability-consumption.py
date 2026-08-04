#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION_ROOTS = [
    ROOT / "app/src/main/java",
    ROOT / "sandbox-domain/src/main/java",
    ROOT / "sandbox-runtime/src/main/java",
    ROOT / "sandbox-companion32/src/main/java",
]
errors: list[str] = []
raw = re.compile(r"DurableAtomicFile\.(write|replacePrepared|move)\s*\(")
allowed_raw = {
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestStorageTransferCoordinator.java",
    "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/DurableAtomicFile.java",
}
for base in PRODUCTION_ROOTS:
    for path in base.rglob("*.java"):
        rel = str(path.relative_to(ROOT)).replace("\\", "/")
        matches = list(raw.finditer(path.read_text(encoding="utf-8")))
        if matches and rel not in allowed_raw:
            errors.append(f"raw durability result not consumed: {rel} ({len(matches)})")

atomic = (ROOT / "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/DurableAtomicFile.java").read_text(encoding="utf-8")
for token in ("writeAcknowledged", "replacePreparedAcknowledged", "moveAcknowledged",
              "acknowledge(Path destination", "repairPending(Path destination",
              "DURABILITY_REPAIR_PREFIX"):
    if token not in atomic:
        errors.append(f"DurableAtomicFile missing {token}")

test = (ROOT / "sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/persistence/DurableAtomicFileSelfTest.java").read_text(encoding="utf-8")
for token in ("testAcknowledgedUncertaintyLeavesRepairMarker", "hasPendingDurabilityRepair",
              "repairPending(destination)"):
    if token not in test:
        errors.append(f"durability regression missing {token}")

verify = (ROOT / "scripts/verify-all.sh").read_text(encoding="utf-8")
if "python3 scripts/check-full-review-durability-consumption.py" not in verify:
    errors.append("verify-all.sh does not enforce durability result consumption")

if errors:
    print("FAIL full-review durability consumption", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS all production durability results are consumed or transactionally repaired")
