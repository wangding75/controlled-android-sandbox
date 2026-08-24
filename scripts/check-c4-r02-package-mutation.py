#!/usr/bin/env python3
"""Fail-closed static contract checks for C4-R02 package mutation work."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


checks = {
    "package/user single flight": ("app/src/main/java/com/warden/controlledsandbox/PackageMutationCoordinator.java",
                                    ("active.get(key)", "Start.busy", "active.put(key, trace)")),
    "request-scoped trace": ("app/src/main/java/com/warden/controlledsandbox/PackageMutationTrace.java",
                              ("requestId", "operationId", "stageTimingsMs", "stageDeadlinesMs",
                               "retryBudget", "attempt")),
    "all required import stages": ("app/src/main/java/com/warden/controlledsandbox/PackageMutationTrace.java",
                                   ("COPY", "HASH", "PARSE", "NATIVE_EXTRACT", "PUBLISH",
                                    "CATALOG", "ENSURE_INSTANCE")),
    "atomic import and instance": ("app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java",
                                   ("importInstalledApplicationAndEnsure", "withImported",
                                    "withEnsuredInstance", "catalogRepository.save")),
    "no hidden connector retry": ("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnector.java",
                                  ("requireSingleAttempt", "requireAttempts(1)")),
    "client uses single attempt": ("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
                                   ("requireSingleAttempt()", "importInstalledApplicationAndEnsure",
                                    ".deleteInstanceWithOperation(")),
    "mixed ELF is auditable": ("app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java",
                               ("MIXED_ELF_MACHINE", "trace.anomaly")),
    "UI immediate operation state": ("app/src/main/java/com/warden/controlledsandbox/MainActivity.java",
                                     ("beginMutation", "activeMutationRequestId",
                                      "importButton.setEnabled(false)",
                                      "addInstalledButton.setEnabled(false)", "elapsed=")),
    "device runner fail fast": ("tools/capability/run_c4_r02_rd.py",
                                ("attempt", "retryBudget", "capture_snapshot",
                                 "PARTIAL_CYCLE_REQUIRES_INVESTIGATION")),
    "negative rollback gate": ("tools/capability/run_c4_r02_negative_rd.py",
                               ("UNTRUSTED_NATIVE_GUEST_DENIED", "retryDecision",
                                "residue", "resolve_rd_environment")),
    "device concurrency classification": ("app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java",
                                          ("MUTATION_BUSY", "sameOperationId")),
    "dynamic RD resolution": ("tools/capability/run_c4_r02_rd.py",
                              ("resolve_rd_environment", "instance-name")),
}

failures = []
for name, (relative, needles) in checks.items():
    source = text(relative)
    missing = [needle for needle in needles if needle not in source]
    if missing:
        failures.append(f"{name}: {relative} missing {missing}")

runner = text("tools/capability/run_c4_r02_rd.py")
for forbidden in ("127.0.0.1:" + "16416", "Thread.sleep", "deadline_sec=600"):
    if forbidden in runner:
        failures.append(f"device runner contains forbidden token: {forbidden}")

if failures:
    raise SystemExit("C4-R02 STATIC GATE FAIL\n" + "\n".join(failures))

print("PASS C4-R02 package mutation transaction/static contracts")
