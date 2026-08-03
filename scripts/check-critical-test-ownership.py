#!/usr/bin/env python3
"""Verify main-reachable ownership and successful Host-runner execution.

This is a critical-path ownership gate, not a line/branch coverage claim. A mapping passes only when
its direct production reference is reachable from the test's main method and the current static
Android runner completed that test successfully. Dead helper methods, comments and literals cannot
satisfy the gate.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/static_android_compile.py"
EXECUTION_RECEIPT = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
REPORT = ROOT / "build/verification/m5-t19-critical-test-ownership.json"


@dataclass(frozen=True)
class OwnerSpec:
    owner: str
    production: str
    test: str
    direct_pattern: str


@dataclass(frozen=True)
class RegressionSpec:
    issue: str
    test: str
    required_patterns: tuple[str, ...] = ()
    required_methods: tuple[str, ...] = ()


OWNERS = (
    OwnerSpec("PackageManagementSession", "app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java", "app/src/testHarness/java/com/warden/controlledsandbox/PackageSessionDirectOwnershipSelfTest.java", r"\bnew\s+PackageManagementSession\s*\("),
    OwnerSpec("PackageRuntimePermissionSession", "app/src/main/java/com/warden/controlledsandbox/PackageRuntimePermissionSession.java", "app/src/testHarness/java/com/warden/controlledsandbox/PackageSessionDirectOwnershipSelfTest.java", r"\bnew\s+PackageRuntimePermissionSession\s*\("),
    OwnerSpec("PackageVirtualSystemServiceSession", "app/src/main/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSession.java", "app/src/testHarness/java/com/warden/controlledsandbox/PackageVirtualSystemServiceSessionSelfTest.java", r"\bnew\s+PackageVirtualSystemServiceSession\s*\("),
    OwnerSpec("VirtualSystemServiceStore", "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java", "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServiceStoreSelfTest.java", r"\bnew\s+VirtualSystemServiceStore\s*\("),
    OwnerSpec("VirtualSystemServicePager", "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServicePager.java", "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServicePagingSelfTest.java", r"\bnew\s+VirtualSystemServicePager\s*\("),
    OwnerSpec("ActivityTaskLedger", "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java", "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java", r"\bnew\s+ActivityTaskLedger\s*\("),
    OwnerSpec("RuntimeGuestConnectionPool", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPool.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java", r"\bnew\s+RuntimeGuestConnectionPool\s*\("),
    OwnerSpec("RuntimeSystemServiceCoordinator", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeSystemServiceCoordinator.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeSystemServiceCoordinatorSelfTest.java", r"\bnew\s+RuntimeSystemServiceCoordinator\s*\("),
    OwnerSpec("RuntimeOperationTransport", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransport.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RuntimeOperationTransportSelfTest.java", r"\bRuntimeOperationTransport\s*\.\s*(?:request|execute|fromLegacy|toLegacyBundle)\s*\("),
    OwnerSpec("InvocationMethodMatcher", "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcher.java", "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/InvocationMethodMatcherSelfTest.java", r"\bInvocationMethodMatcher\s*\.\s*(?:named|startsWith|containsAny)\s*\("),
    OwnerSpec("RebindableServiceConnector", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnector.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java", r"\bnew\s+RebindableServiceConnector\s*<[^>]*>\s*\("),
    OwnerSpec("BrokerObserverRuntime", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntime.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntimeSelfTest.java", r"\bnew\s+BrokerObserverRuntime\s*\("),
    OwnerSpec("OrderedReceiverPendingResultBridge", "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridgeSelfTest.java", r"\bOrderedReceiverPendingResultBridge\s*\.\s*install\s*\("),
)

P1_REGRESSIONS = (
    RegressionSpec("P1-01", "sandbox-native/src/test/cpp/native_syscall_boundary_self_test.cpp", (r"\bSYS_openat\b", r"\bSYS_connect\b", r"\bSYS_sendto\b")),
    RegressionSpec("P1-02", "sandbox-native/src/test/cpp/native_network_interceptors_self_test.cpp", required_methods=("test_denied_recvfrom_preserves_buffers_and_queue", "test_denied_recvmsg_preserves_message_and_queue", "test_duplication_tracking")),
    RegressionSpec("P1-03", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java", required_methods=("timesOutMissingCallbackAndRebinds", "ignoresLateConnectionAfterTimeout")),
    RegressionSpec("P1-04", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/provider/BrokerObserverRuntimeSelfTest.java", required_methods=("testImmediateDeathDuringRegistration",)),
    RegressionSpec("P1-05", "app/src/testHarness/java/com/warden/controlledsandbox/VirtualSystemServicePagingSelfTest.java", required_methods=("pageTokensAndBudgets", "binaryOffload", "accountSessionPaging")),
    RegressionSpec("P1-06", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java", required_methods=("testDelayedDeathReconnectsWithinCurrentRequest", "testConnectionIsNotPublishedBeforeDeathLinkCompletes")),
)

POST_REVIEW_REGRESSIONS = (
    RegressionSpec("P2-01", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/protocol/RebindableServiceConnectorSelfTest.java", required_methods=("retiresSilentlyDeadBindingBeforeRebind",)),
    RegressionSpec("P2-02", "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestConnectionPoolSelfTest.java", required_methods=("testFrameworkDisconnectUnlinksDeathRecipient", "testBindingDiedUnlinksDeathRecipient")),
)


def strip_comments_and_literals(source: str) -> str:
    out: list[str] = []
    i = 0
    state = "code"
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if ch == "/" and nxt == "/": state = "line"; out.extend("  "); i += 2; continue
            if ch == "/" and nxt == "*": state = "block"; out.extend("  "); i += 2; continue
            if ch == '"': state = "string"; out.append(" "); i += 1; continue
            if ch == "'": state = "char"; out.append(" "); i += 1; continue
            out.append(ch); i += 1; continue
        if state == "line":
            out.append("\n" if ch == "\n" else " ")
            if ch == "\n": state = "code"
            i += 1; continue
        if state == "block":
            if ch == "*" and nxt == "/": state = "code"; out.extend("  "); i += 2
            else: out.append("\n" if ch == "\n" else " "); i += 1
            continue
        quote = '"' if state == "string" else "'"
        if ch == "\\": out.extend("  "); i += min(2, len(source) - i); continue
        if ch == quote: state = "code"
        out.append("\n" if ch == "\n" else " "); i += 1
    return "".join(out)


METHOD_DECL = re.compile(
    r"(?m)(?:^|[;{}])\s*(?:@[\w.]+(?:\([^{};]*\))?\s*)*"
    r"(?:(?:public|protected|private|static|final|synchronized|native|abstract|strictfp|inline|constexpr|virtual|explicit)\s+)*"
    r"(?:<[^{;}]+>\s*)?(?:[\w:.<>?\[\],*&]+\s+)+"
    r"(?P<name>[A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{;]+)?\{"
)


def method_bodies(source: str) -> dict[str, list[str]]:
    clean = strip_comments_and_literals(source)
    result: dict[str, list[str]] = {}
    for match in METHOD_DECL.finditer(clean):
        name = match.group("name")
        open_index = match.end() - 1
        depth = 1
        index = open_index + 1
        while index < len(clean) and depth:
            if clean[index] == "{": depth += 1
            elif clean[index] == "}": depth -= 1
            index += 1
        if depth == 0:
            result.setdefault(name, []).append(clean[open_index + 1:index - 1])
    return result


def main_reachable_methods(source: str) -> tuple[set[str], dict[str, list[str]]]:
    methods = method_bodies(source)
    reachable: set[str] = set()
    pending = ["main"] if "main" in methods else []
    while pending:
        name = pending.pop()
        if name in reachable: continue
        reachable.add(name)
        for body in methods.get(name, []):
            for called in re.findall(r"(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(", body):
                if called in methods and called not in reachable:
                    pending.append(called)
    return reachable, methods


def reachable_reference(source: str, pattern: str) -> bool:
    reachable, methods = main_reachable_methods(source)
    return any(re.search(pattern, body, re.MULTILINE) for name in reachable for body in methods.get(name, []))


def fqcn(test_path: str) -> str:
    source = (ROOT / test_path).read_text(encoding="utf-8")
    package = re.search(r"^\s*package\s+([\w.]+)\s*;", source, re.MULTILINE)
    return (package.group(1) + "." if package else "") + Path(test_path).stem


def runner_count(runner: str, test_path: str) -> int:
    return runner.count("'" + fqcn(test_path) + "'")


def digest(paths: list[Path]) -> str:
    value = hashlib.sha256()
    for path in sorted(set(paths), key=lambda p: str(p)):
        value.update(str(path.relative_to(ROOT)).encode()); value.update(b"\0")
        value.update(path.read_bytes()); value.update(b"\0")
    return value.hexdigest()


def self_test() -> None:
    pattern = r"\bnew\s+TargetOwner\s*\("
    good = "final class T { public static void main(String[] a){ run(); } static void run(){ new TargetOwner(); } }"
    dead = "final class T { public static void main(String[] a){ pass(); } static void pass(){} static void neverCalled(){ new TargetOwner(); } }"
    comment = "final class T { public static void main(String[] a){ /* new TargetOwner(); */ } }"
    if not reachable_reference(good, pattern): raise AssertionError("reachable helper rejected")
    if reachable_reference(dead, pattern): raise AssertionError("dead helper accepted")
    if reachable_reference(comment, pattern): raise AssertionError("comment accepted")
    print("PASS critical ownership self-test rejects dead-code and lexical-only references")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test(); return
    errors: list[str] = []
    if not EXECUTION_RECEIPT.is_file():
        errors.append("static Android execution receipt missing; run tools/static_android_compile.py first")
        receipt = {}
    else:
        receipt = json.loads(EXECUTION_RECEIPT.read_text(encoding="utf-8"))
        if receipt.get("completed") is not True:
            errors.append("static Android runner did not complete")
    executed = set(receipt.get("executedTests", []))
    runner = RUNNER.read_text(encoding="utf-8") if RUNNER.is_file() else ""
    scanned = [Path(__file__).resolve(), RUNNER]
    records: list[dict[str, object]] = []
    for spec in OWNERS:
        production = ROOT / spec.production
        test = ROOT / spec.test
        owner_errors: list[str] = []
        source = test.read_text(encoding="utf-8") if test.is_file() else ""
        reachable, methods = main_reachable_methods(source)
        direct = reachable_reference(source, spec.direct_pattern) if source else False
        registered = runner_count(runner, spec.test) if test.is_file() else 0
        runtime_executed = fqcn(spec.test) in executed if test.is_file() else False
        if not production.is_file(): owner_errors.append("production source missing")
        if not test.is_file(): owner_errors.append("direct test source missing")
        if "main" not in methods: owner_errors.append("test has no main entry point")
        if not direct: owner_errors.append("production reference is not reachable from main")
        if registered != 1: owner_errors.append(f"static runner registration count is {registered}, expected 1")
        if not runtime_executed: owner_errors.append("current static runner receipt does not show successful execution")
        errors.extend(f"{spec.owner}: {item}" for item in owner_errors)
        if production.is_file(): scanned.append(production)
        if test.is_file(): scanned.append(test)
        records.append({
            "owner": spec.owner, "production": spec.production, "test": spec.test,
            "mainReachableDirectReference": direct, "reachableMethods": sorted(reachable),
            "staticRunnerRegistrations": registered, "runtimeExecutionReceipt": runtime_executed,
            "status": "PASS" if not owner_errors else "FAIL",
        })

    regressions: list[dict[str, object]] = []
    post_review_regressions: list[dict[str, object]] = []
    for spec in P1_REGRESSIONS + POST_REVIEW_REGRESSIONS:
        path = ROOT / spec.test
        source = path.read_text(encoding="utf-8", errors="ignore") if path.is_file() else ""
        reachable, methods = main_reachable_methods(source)
        pattern_status = {pattern: reachable_reference(source, pattern) for pattern in spec.required_patterns}
        method_status = {name: name in reachable for name in spec.required_methods}
        status = path.is_file() and all(pattern_status.values()) and all(method_status.values())
        if not status: errors.append(f"{spec.issue}: main-reachable regression evidence incomplete in {spec.test}")
        if path.is_file(): scanned.append(path)
        record = {"issue": spec.issue, "test": spec.test,
                  "mainReachablePatterns": pattern_status,
                  "mainReachableMethods": method_status,
                  "status": "PASS" if status else "FAIL"}
        if spec in P1_REGRESSIONS:
            regressions.append(record)
        else:
            post_review_regressions.append(record)

    report = {
        "gate": "critical-source-test-ownership",
        "metricType": "main-reachable direct ownership plus successful Host-runner receipt",
        "generationMode": "live source reachability and current execution receipt",
        "lineCoverageClaimed": False, "branchCoverageClaimed": False,
        "owners": records, "ownerCount": len(records),
        "mappedOwnerCount": sum(r["status"] == "PASS" for r in records),
        "directOwnerCount": sum(bool(r["mainReachableDirectReference"]) for r in records),
        "executedOwnerCount": sum(bool(r["runtimeExecutionReceipt"]) for r in records),
        "p1RegressionEvidence": regressions,
        "postReviewRegressionEvidence": post_review_regressions,
        "executionReceipt": str(EXECUTION_RECEIPT.relative_to(ROOT)),
        "inputDigestSha256": digest(scanned + ([EXECUTION_RECEIPT] if EXECUTION_RECEIPT.is_file() else [])),
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if errors:
        print("FAIL critical source test ownership", file=sys.stderr)
        for item in errors: print(" - " + item, file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS critical source test ownership ({len(records)}/{len(records)} main-reachable and executed)")


if __name__ == "__main__":
    main()
