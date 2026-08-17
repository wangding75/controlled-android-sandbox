#!/usr/bin/env python3
"""Diagnostic-only collect-all capability audit.

This tool never modifies source, tests, gates, formatting, files, or the Git index.
A required gate that FAILs still causes a non-zero exit after every gate has run.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from campaign_status import CAMPAIGN_ID, REQUIRED_CAPABILITY_IDS
from common import (
    ROOT,
    artifacts_dir,
    expand_command,
    git_identity,
    host_os,
    load_yaml,
    now_iso,
    run_command,
    write_json,
)

GATES_PATH = ROOT / "tools/capability/campaign_gates.yaml"
ISSUES_PATH = ROOT / "docs/review/KNOWN_ISSUES.yaml"


def load_catalog() -> list[dict[str, Any]]:
    return list(load_yaml(GATES_PATH).get("gates") or [])


def load_issues() -> list[dict[str, Any]]:
    return list(load_yaml(ISSUES_PATH).get("issues") or [])


NATIVE_CAMPAIGN_ALIASES = {
    "native",
    "T57-R03-P0A-01",
    "native_loader_jni_io",
}


def select_gates(campaign: str, all_requested: bool) -> list[dict[str, Any]]:
    gates = load_catalog()
    if all_requested or campaign in {"all", "T57-R03-01", CAMPAIGN_ID}:
        return gates
    requested = "native_loader_jni_io" if campaign in NATIVE_CAMPAIGN_ALIASES else campaign
    if requested not in REQUIRED_CAPABILITY_IDS:
        raise SystemExit(f"unknown campaign/capability: {campaign}")
    selected = [gate for gate in gates if requested in (gate.get("capabilities") or [])]
    if not selected:
        raise SystemExit(f"no gates mapped to capability {campaign}")
    return selected


def classify_gate(
    gate: dict[str, Any],
    output: str,
    returncode: int,
    issues: list[dict[str, Any]],
) -> tuple[str, list[str]]:
    if returncode == 0:
        expected_ids = []
        for issue in issues:
            if issue.get("classification") != "EXPECTED_BEHAVIOR":
                continue
            if any(pattern and pattern in output for pattern in issue.get("match_patterns") or []):
                expected_ids.append(issue["issue_id"])
        if expected_ids:
            return "EXPECTED_WARNING", expected_ids
        return "PASS", []

    matched: list[str] = []
    declared = set(gate.get("known_issue_ids") or [])
    for issue in issues:
        issue_id = issue.get("issue_id")
        patterns = list(issue.get("match_patterns") or [])
        if issue_id in declared:
            matched.append(issue_id)
            continue
        if any(pattern and pattern in output for pattern in patterns):
            matched.append(issue_id)
    if matched:
        return "KNOWN_ISSUE", sorted(set(matched))
    return "NEW_REGRESSION", []


def render_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Capability collect-all audit",
        "",
        f"- campaign: `{summary['campaign_id']}`",
        f"- capability_filter: `{summary['capability_filter']}`",
        f"- branch: `{summary['branch']}`",
        f"- commit: `{summary['commit']}`",
        f"- tree: `{summary['tree']}`",
        f"- timestamp: `{summary['timestamp']}`",
        f"- host_os: `{summary['host_os']}`",
        f"- dry_run: `{summary['dry_run']}`",
        "",
        "## Counts",
        "",
        f"- total: {summary['counts']['total']}",
        f"- PASS: {summary['counts']['PASS']}",
        f"- KNOWN_ISSUE: {summary['counts']['KNOWN_ISSUE']}",
        f"- EXPECTED_WARNING: {summary['counts']['EXPECTED_WARNING']}",
        f"- NEW_REGRESSION: {summary['counts']['NEW_REGRESSION']}",
        f"- FAIL: {summary['counts']['FAIL']}",
        f"- UNVERIFIED: {summary['counts']['UNVERIFIED']}",
        "",
        "## Gates",
        "",
        "| Gate | Group | Result | Classification | Known issues | Exit |",
        "|---|---|---|---|---|---|",
    ]
    for gate in summary["gates"]:
        lines.append(
            "| {id} | {group} | {result} | {classification} | {issues} | {code} |".format(
                id=gate["id"],
                group=gate["group"],
                result=gate["result"],
                classification=gate["classification"],
                issues=", ".join(gate["known_issues"]) or "-",
                code=gate["returncode"],
            )
        )
    lines.extend(
        [
            "",
            "## Policy",
            "",
            "This audit is diagnostic only. FAIL/KNOWN_ISSUE does not authorize source edits.",
            "RD_BASELINE collect-all is not ANDROID_MATRIX, OEM, or VA Pro equivalent.",
            "",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", default="all", help="capability id or 'all'")
    parser.add_argument("--all", action="store_true", help="run every catalogued gate")
    parser.add_argument("--dry-run", action="store_true", help="list gates without executing them")
    args = parser.parse_args()

    identity = git_identity()
    issues = load_issues()
    selected = select_gates(args.campaign, args.all)
    stamp = now_iso()
    out_dir = artifacts_dir(args.campaign if not args.all else "all")
    results: list[dict[str, Any]] = []

    for gate in selected:
        command = expand_command(list(gate["command"]))
        if args.dry_run:
            results.append(
                {
                    "id": gate["id"],
                    "group": gate.get("group", ""),
                    "command": command,
                    "result": "SKIPPED",
                    "classification": "UNVERIFIED",
                    "known_issues": list(gate.get("known_issue_ids") or []),
                    "returncode": 0,
                    "required": bool(gate.get("required", True)),
                    "stdout": "",
                    "stderr": "",
                }
            )
            continue
        executed = run_command(command, timeout_sec=int(gate.get("timeout_sec") or 180))
        output = (executed.get("stdout") or "") + "\n" + (executed.get("stderr") or "")
        classification, matched = classify_gate(gate, output, int(executed["returncode"]), issues)
        result = "PASS" if executed["returncode"] == 0 else "FAIL"
        results.append(
            {
                "id": gate["id"],
                "group": gate.get("group", ""),
                "command": command,
                "result": result,
                "classification": classification,
                "known_issues": matched,
                "returncode": executed["returncode"],
                "required": bool(gate.get("required", True)),
                "timed_out": executed.get("timed_out", False),
                "stdout_tail": (executed.get("stdout") or "")[-4000:],
                "stderr_tail": (executed.get("stderr") or "")[-4000:],
            }
        )
        write_json(out_dir / f"{gate['id']}.json", executed)

    counts = {
        "total": len(results),
        "PASS": sum(1 for item in results if item["classification"] == "PASS"),
        "KNOWN_ISSUE": sum(1 for item in results if item["classification"] == "KNOWN_ISSUE"),
        "EXPECTED_WARNING": sum(1 for item in results if item["classification"] == "EXPECTED_WARNING"),
        "NEW_REGRESSION": sum(1 for item in results if item["classification"] == "NEW_REGRESSION"),
        "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
        "UNVERIFIED": sum(1 for item in results if item["classification"] == "UNVERIFIED"),
    }
    summary = {
        "campaign_id": CAMPAIGN_ID,
        "capability_filter": "all" if args.all or args.campaign == "all" else args.campaign,
        "branch": identity["branch"],
        "commit": identity["commit"],
        "tree": identity["tree"],
        "timestamp": stamp,
        "host_os": host_os(),
        "dry_run": args.dry_run,
        "output_dir": str(out_dir),
        "counts": counts,
        "gates": results,
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
        "source_modified": False,
    }
    write_json(out_dir / "summary.json", summary)
    markdown = render_markdown(summary)
    (out_dir / "summary.md").write_text(markdown, encoding="utf-8")
    print(markdown)
    print(f"EVIDENCE: {out_dir}")

    if args.dry_run:
        return 0
    required_fail = any(item["required"] and item["result"] == "FAIL" for item in results)
    return 1 if required_fail else 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
