#!/usr/bin/env python3
"""C4-R02 expected-failure rollback probe on dynamically resolved RD测试."""

from __future__ import annotations

import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "capability"))

from run_c4_r02_rd import residue
from run_p1_00_rd import debug_command
from run_rd_campaign import resolve_rd_environment

OUTPUT = ROOT / "verification" / "catch-up" / "C4-R02" / "rd-acceptance" / "negative-rollback.json"
HOST_PACKAGE = "com.warden.controlledsandbox.debug"
FIXTURE_PACKAGE = "com.warden.controlledsandbox.fixture"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    environment = resolve_rd_environment("RD测试")
    serial = environment["adb_serial"]
    request_id = "c4-r02-negative-" + str(uuid.uuid4())
    result = debug_command(
        serial,
        ["--es", "command", "import-only", "--es", "package", FIXTURE_PACKAGE,
         "--ei", "user", "0", "--es", "requestId", request_id,
         "--ez", "trustNativeGuest", "false"],
        deadline_sec=240,
        force_stop_host=True,
    )
    cleanup = residue(serial)
    payload = result.get("result", result)
    message = str(payload.get("errorMessage", "")) if isinstance(payload, dict) else ""
    expected = payload.get("status") == "FAIL" and "UNTRUSTED_NATIVE_GUEST_DENIED" in message
    report = {
        "task": "C4-R02",
        "case": "expected untrusted native import failure rolls back staging",
        "environment": environment,
        "requestId": request_id,
        "attempt": 1,
        "retryBudget": 0,
        "retryDecision": "NO_RETRY_NON_RETRYABLE_SECURITY_POLICY",
        "expectedFailureObserved": expected,
        "result": result,
        "residue": cleanup,
        "assertionCorrection": "authoritative DebugCommand errorMessage contains the stable code",
        "status": "PASS" if expected and cleanup["pass"] else "FAIL",
    }
    write_json(OUTPUT, report)
    print(json.dumps({"status": report["status"], "requestId": request_id,
                      "expectedFailureObserved": expected,
                      "residuePass": cleanup["pass"]}, ensure_ascii=False))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
