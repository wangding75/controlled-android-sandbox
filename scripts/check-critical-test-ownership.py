#!/usr/bin/env python3
"""Verify executable direct ownership for architecture-critical production classes.

The gate rejects mappings that only point at an existing test file. Every owner must have:
1. a production source file;
2. a test with a lexical direct constructor/static call after comments and strings are removed;
3. a runnable main entry point; and
4. exactly one execution entry in tools/static_android_compile.py.

This remains an ownership/critical-path gate. It does not claim line or branch coverage.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = "tools/static_android_compile.py"


@dataclass(frozen=True)
class OwnerSpec:
    owner: str
    production: str
    test: str
    direct_pattern: str


OWNERS = (
    OwnerSpec("PackageManagementSession",
              "app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java",
              "app/src/testHarness/java/com/warden/controlledsandbox/PackageSessionDirectOwnershipSelfTest.java",
              r"\bnew\s+PackageManagementSession\s*\("),
    OwnerSpec("PackageRuntimePermissionSession",
              "app/src/main/java/com/warden/controlledsandbox/PackageRuntimePermissionSession.java",
              "app/src/testHarness/java/com/warden/controlledsandbox/PackageSessionDirectOwnershipSelfTest.java",
              r"\bnew\s+PackageRuntimePermissionSession\s*\("),
    OwnerSpec("PackageVirtualSystemServiceSession",
              "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java",
              "app/src/testHarness/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSessionSelfTest.java",
              r"\bnew\s+PackageVirtualSystemServiceSession\s*\("),
    OwnerSpec("VirtualSystemServiceStore",
              "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
              "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java",
              r"\bnew\s+VirtualSystemServiceStore\s*\("),
    OwnerSpec("VirtualSystemServicePager",
              "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServicePager.java",
              "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServicePagingSelfTest.java",
              r"\bnew\s+VirtualSystemServicePager\s*\("),
    OwnerSpec("ActivityTaskLedger",
              "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java",
              "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java",
              r"\bnew\s+ActivityTaskLedger\s*\("),
    OwnerSpec("RuntimeGuestConnectionPool",
              "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java",
              "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java",
              r"\bnew\s+RuntimeGuestConnectionPool\s*\("),
    OwnerSpec("RuntimeOperationTransport",
              "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java",
              "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransportSelfTest.java",
              r"\bRuntimeOperationTransport\s*\.\s*(?:request|execute|fromLegacy|toLegacyBundle)\s*\("),
    OwnerSpec("InvocationMethodMatcher",
              "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcher.java",
              "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcherSelfTest.java",
              r"\bInvocationMethodMatcher\s*\.\s*(?:named|startsWith|containsAny)\s*\("),
    OwnerSpec("RebindableServiceConnector",
              "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnector.java",
              "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
              r"\bnew\s+RebindableServiceConnector\s*<[^>]*>\s*\("),
    OwnerSpec("BrokerObserverRuntime",
              "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntime.java",
              "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntimeSelfTest.java",
              r"\bnew\s+BrokerObserverRuntime\s*\("),
    OwnerSpec("OrderedReceiverPendingResultBridge",
              "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java",
              "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridgeSelfTest.java",
              r"\bOrderedReceiverPendingResultBridge\s*\.\s*install\s*\("),
)

P1_REGRESSIONS = (
    ("P1-01", "sandbox-native/src/test/cpp/native_syscall_boundary_self_test.cpp",
     ("SYS_openat", "SYS_connect", "SYS_sendto")),
    ("P1-02", "sandbox-native/src/test/cpp/native_network_interceptors_self_test.cpp",
     ("controlled_recvfrom", "controlled_getpeername", "controlled_dup")),
    ("P1-03", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java",
     ("BIND_TIMEOUT", "late callback")),
    ("P1-04", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntimeSelfTest.java",
     ("ImmediateDeathObserver", "testImmediateDeathDuringRegistration")),
    ("P1-05", "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServicePagingSelfTest.java",
     ("PAGING_REQUIRED", "VirtualSystemServicePager")),
    ("P1-06", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java",
     ("RuntimeGuestConnectionPool", "BINDER_DIED")),
)


def strip_comments_and_literals(source: str) -> str:
    """Remove Java comments and literal bodies while preserving token boundaries."""
    out: list[str] = []
    i = 0
    state = "code"
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line_comment"; out.extend("  "); i += 2; continue
            if ch == "/" and nxt == "*":
                state = "block_comment"; out.extend("  "); i += 2; continue
            if ch == '"':
                state = "string"; out.append(" "); i += 1; continue
            if ch == "'":
                state = "char"; out.append(" "); i += 1; continue
            out.append(ch); i += 1; continue
        if state == "line_comment":
            if ch == "\n": state = "code"; out.append("\n")
            else: out.append(" ")
            i += 1; continue
        if state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "code"; out.extend("  "); i += 2
            else:
                out.append("\n" if ch == "\n" else " "); i += 1
            continue
        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if ch == "\\":
                out.extend("  "); i += min(2, len(source) - i); continue
            if ch == quote: state = "code"
            out.append("\n" if ch == "\n" else " "); i += 1
    return "".join(out)


def direct_reference(source: str, pattern: str) -> bool:
    return re.search(pattern, strip_comments_and_literals(source), re.MULTILINE) is not None


def runner_count(runner: str, test_path: str) -> int:
    class_name = Path(test_path).stem
    package_match = re.search(r"^\s*package\s+([\w.]+)\s*;", (ROOT / test_path).read_text(encoding="utf-8"), re.MULTILINE)
    if not package_match:
        return 0
    fqcn = package_match.group(1) + "." + class_name
    return runner.count("'" + fqcn + "'")


def digest(paths: list[Path]) -> str:
    value = hashlib.sha256()
    for path in sorted(paths, key=lambda p: str(p)):
        value.update(str(path.relative_to(ROOT)).encode("utf-8"))
        value.update(b"\0")
        value.update(path.read_bytes())
        value.update(b"\0")
    return value.hexdigest()


def self_test() -> None:
    pattern = r"\bnew\s+TargetOwner\s*\("
    good = "final class T { void test(){ TargetOwner x = new TargetOwner(); x.run(); } }"
    comment_only = "final class T { /* new TargetOwner(); */ void test(){} }"
    string_only = 'final class T { String value = "new TargetOwner()"; }'
    indirect = "final class T { void test(){ Helper.run(); } }"
    if not direct_reference(good, pattern):
        raise AssertionError("direct constructor reference was rejected")
    for source in (comment_only, string_only, indirect):
        if direct_reference(source, pattern):
            raise AssertionError("non-executable ownership evidence was accepted")
    print("PASS critical test ownership gate self-test rejects existence-only mappings")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return

    errors: list[str] = []
    records: list[dict[str, object]] = []
    runner_path = ROOT / RUNNER
    runner = runner_path.read_text(encoding="utf-8") if runner_path.is_file() else ""
    scanned: list[Path] = [Path(__file__).resolve(), runner_path]

    for spec in OWNERS:
        production = ROOT / spec.production
        test = ROOT / spec.test
        owner_errors: list[str] = []
        if not production.is_file(): owner_errors.append("production source missing")
        if not test.is_file(): owner_errors.append("direct test source missing")
        direct = False
        has_main = False
        executions = 0
        if test.is_file():
            source = test.read_text(encoding="utf-8")
            direct = direct_reference(source, spec.direct_pattern)
            has_main = re.search(r"\bpublic\s+static\s+void\s+main\s*\(", strip_comments_and_literals(source)) is not None
            executions = runner_count(runner, spec.test)
            scanned.append(test)
        if production.is_file(): scanned.append(production)
        if not direct: owner_errors.append("test does not directly construct/call production owner")
        if not has_main: owner_errors.append("test has no executable main entry point")
        if executions != 1: owner_errors.append(f"static_android_compile execution count is {executions}, expected 1")
        for item in owner_errors: errors.append(f"{spec.owner}: {item}")
        records.append({
            "owner": spec.owner,
            "production": spec.production,
            "test": spec.test,
            "directReference": direct,
            "mainEntryPoint": has_main,
            "staticRunnerExecutions": executions,
            "status": "PASS" if not owner_errors else "FAIL",
        })

    regressions: list[dict[str, object]] = []
    for issue, rel, tokens in P1_REGRESSIONS:
        path = ROOT / rel
        present = path.is_file()
        content = path.read_text(encoding="utf-8", errors="ignore") if present else ""
        token_status = {token: token in content for token in tokens}
        if not present or not all(token_status.values()):
            errors.append(f"{issue}: direct regression evidence incomplete in {rel}")
        if present: scanned.append(path)
        regressions.append({"issue": issue, "test": rel, "tokens": token_status,
                            "status": "PASS" if present and all(token_status.values()) else "FAIL"})

    report = {
        "gate": "critical-source-test-ownership",
        "metricType": "executable direct critical-path regression ownership",
        "generationMode": "live source and static-runner scan",
        "lineCoverageClaimed": False,
        "branchCoverageClaimed": False,
        "owners": records,
        "ownerCount": len(records),
        "mappedOwnerCount": sum(1 for record in records if record["status"] == "PASS"),
        "directOwnerCount": sum(1 for record in records if record["directReference"]),
        "executedOwnerCount": sum(1 for record in records if record["staticRunnerExecutions"] == 1),
        "p1RegressionEvidence": regressions,
        "inputDigestSha256": digest(list(dict.fromkeys(scanned))),
    }
    output = ROOT / "verification/m5-t19-critical-test-ownership.json"
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if errors:
        print("FAIL critical source test ownership", file=sys.stderr)
        for item in errors: print(" - " + item, file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS critical source test ownership ({len(records)}/{len(records)} owners directly tested and executed; no coverage percentage claimed)")


if __name__ == "__main__":
    main()
