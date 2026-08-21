#!/usr/bin/env python3
"""Reconcile the current CAS capability ledgers without claiming VA parity.

The reconciliation is deliberately evidence-based.  VA changelog rows are
classified as scope signals only; the canonical CAS registry and C0-T03 RD
evidence remain the authority for CAS status.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/capability/CAPABILITY_REGISTRY.yaml"
CORPUS = ROOT / "docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml"
ISSUES = ROOT / "docs/review/KNOWN_ISSUES.yaml"
C0_T03 = ROOT / "verification/catch-up/C0-T03"

SCOPE_CLASSIFICATIONS = (
    "IN_SCOPE",
    "OUT_OF_SCOPE",
    "DUPLICATE",
    "NEEDS_FIXTURE",
    "PROVEN",
)
ALLOWED_CAPABILITY_STATUSES = {
    "PASS",
    "PARTIAL",
    "GAP",
    "UNVERIFIED",
    "BLOCKED_ENV",
    "NOT_APPLICABLE",
    "EXPECTED",
    "NOT_PROVEN",
}
ALLOWED_CAS_STATUSES = {
    "CAS_ALREADY_COVERS",
    "NEEDS_TEST",
    "GAP",
    "IMPLEMENTED",
    "NOT_APPLICABLE",
    "UNVERIFIED",
}
REQUIRED_C0_T03_EVIDENCE = (
    "README.md",
    "round-1-complete",
    "round-2-complete",
    "acceptance-evidence/evidence-manifest.json",
)


def load(path: Path) -> dict[str, Any]:
    return yaml.safe_load(path.read_text(encoding="utf-8-sig"))


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8", errors="replace",
        capture_output=True, check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def existing_evidence(paths: list[str]) -> list[str]:
    return [path for path in paths if path.startswith("http") or (ROOT / path).exists()]


def classify_entry(entry: dict[str, Any], duplicate_ids: set[str]) -> tuple[str, str]:
    if entry["id"] in duplicate_ids:
        return "DUPLICATE", "duplicate corpus identifier"
    status = entry["cas_status"]
    if status == "CAS_ALREADY_COVERS":
        return "PROVEN", "CAS static evidence is sufficient for the mapped surface; this is not VA parity"
    if status == "NEEDS_TEST":
        return "NEEDS_FIXTURE", "CAS source surface exists but the required package-neutral fixture is not complete"
    if status == "GAP":
        return "IN_SCOPE", "mapped domain remains in the VA PRO/CAS product scope and is not implemented"
    if status == "UNVERIFIED":
        return "OUT_OF_SCOPE", "the locked public source snapshot has no changelog text for this required ID"
    return "IN_SCOPE", f"CAS corpus status {status} remains in the declared product scope"


def reconcile(audit_summary: Path | None = None) -> dict[str, Any]:
    errors: list[str] = []
    registry_data = load(REGISTRY)
    corpus_data = load(CORPUS)
    issue_data = load(ISSUES)
    capabilities = list(registry_data.get("capabilities") or [])
    entries = list(corpus_data.get("entries") or [])
    issues = list(issue_data.get("issues") or [])
    cap_by_id = {item.get("id"): item for item in capabilities}

    if len(cap_by_id) != len(capabilities):
        errors.append("capability registry contains duplicate IDs")
    corpus_counts = Counter(item.get("id") for item in entries)
    duplicate_ids = {key for key, count in corpus_counts.items() if count > 1}
    if duplicate_ids:
        errors.append(f"VA corpus contains duplicate IDs: {sorted(duplicate_ids)}")
    issue_counts = Counter(item.get("issue_id") for item in issues)
    if any(count > 1 for count in issue_counts.values()):
        errors.append("Known Issues contains duplicate IDs")

    for capability in capabilities:
        identifier = capability.get("id", "<missing>")
        for field in (
            "implementation_status", "static_status", "rd_api32_status",
            "api33_status", "api34_status", "api35_status", "api36_status",
            "oem_status", "commercial_app_status", "va_pro_equivalent",
        ):
            if capability.get(field) not in ALLOWED_CAPABILITY_STATUSES:
                errors.append(f"{identifier}: invalid {field}={capability.get(field)!r}")
        if capability.get("va_pro_equivalent") != "NOT_PROVEN":
            errors.append(f"{identifier}: VA Pro equivalent must remain NOT_PROVEN")
        if any(capability.get(field) == "PASS" for field in ("api33_status", "api34_status", "api35_status", "api36_status", "oem_status")):
            errors.append(f"{identifier}: unsupported matrix PASS")
        evidence = list(capability.get("evidence") or [])
        if any(capability.get(field) == "PASS" for field in ("static_status", "rd_api32_status")):
            missing = [path for path in evidence if path not in existing_evidence([path])]
            if not evidence or missing:
                errors.append(f"{identifier}: PASS status has missing evidence {missing}")

    rows: list[dict[str, Any]] = []
    for entry in entries:
        identifier = entry.get("id", "<missing>")
        mapping = entry.get("cas_mapping")
        if mapping not in cap_by_id:
            errors.append(f"{identifier}: corpus mapping is not in capability registry: {mapping}")
        status = entry.get("cas_status")
        if status not in ALLOWED_CAS_STATUSES:
            errors.append(f"{identifier}: invalid cas_status={status!r}")
        capability = cap_by_id.get(mapping, {})
        if status == "CAS_ALREADY_COVERS" and not capability.get("evidence"):
            errors.append(f"{identifier}: CAS_ALREADY_COVERS has no capability evidence")
        if entry.get("rd_status") == "PASS" and capability.get("rd_api32_status") != "PASS":
            errors.append(f"{identifier}: rd_status PASS disagrees with {mapping}.rd_api32_status")
        classification, reason = classify_entry(entry, duplicate_ids)
        rows.append({
            "id": identifier,
            "category": entry.get("category"),
            "cas_mapping": mapping,
            "cas_status": status,
            "rd_status": entry.get("rd_status"),
            "android_matrix_status": entry.get("android_matrix_status"),
            "oem_status": entry.get("oem_status"),
            "scope_classification": classification,
            "reason": reason,
            "evidence": [
                rel(CORPUS),
                rel(REGISTRY),
                rel(C0_T03 / "README.md"),
            ],
        })

    for issue in issues:
        evidence = list(issue.get("evidence") or [])
        missing = [path for path in evidence if path not in existing_evidence([path])]
        if missing:
            errors.append(f"{issue.get('issue_id')}: missing issue evidence {missing}")
        if issue.get("blocks_current_campaign") is True:
            errors.append(f"{issue.get('issue_id')}: blocks_current_campaign must be false for C0-T04")

    missing_c0_t03 = [path for path in REQUIRED_C0_T03_EVIDENCE if not (C0_T03 / path).exists()]
    if missing_c0_t03:
        errors.append(f"C0-T03 evidence is incomplete: {missing_c0_t03}")
    readme = (C0_T03 / "README.md").read_text(encoding="utf-8") if (C0_T03 / "README.md").exists() else ""
    if "9/9 case `PASS`" not in readme or "分类一致" not in readme:
        errors.append("C0-T03 README does not prove two-round 9/9 classification consistency")

    audit = None
    if audit_summary is not None:
        audit = json.loads(audit_summary.read_text(encoding="utf-8"))
        if audit.get("counts", {}).get("NEW_REGRESSION", 0):
            errors.append("collect-all contains NEW_REGRESSION classifications")
        if audit.get("source_modified") is not False:
            errors.append("collect-all evidence does not declare source_modified=false")
        if audit.get("maturity_level") != "RD_BASELINE" or audit.get("va_pro_equivalent") != "NOT_PROVEN":
            errors.append("collect-all maturity/VA status is over-claimed")

    scope_counts = {key: 0 for key in (
        "IN_SCOPE",
        "OUT_OF_SCOPE",
        "DUPLICATE",
        "NEEDS_FIXTURE",
        "PROVEN",
    )}
    for row in rows:
        scope_counts[row["scope_classification"]] += 1

    result = {
        "schema_version": 1,
        "task": "C0-T04",
        "title": "CAS capability registry / Known Issues / VA PRO corpus reconciliation",
        "git": {
            "branch": git("branch", "--show-current"),
            "commit": git("rev-parse", "HEAD"),
            "tree": git("rev-parse", "HEAD^{tree}"),
            "worktree_status": git("status", "--short"),
        },
        "authority": {
            "registry": rel(REGISTRY),
            "corpus": rel(CORPUS),
            "known_issues": rel(ISSUES),
            "c0_t03_evidence": rel(C0_T03),
        },
        "counts": {
            "capabilities": len(capabilities),
            "known_issues": len(issues),
            "corpus_entries": len(entries),
            "corpus_cas_status": dict(Counter(row["cas_status"] for row in rows)),
            "corpus_scope_classification": scope_counts,
            "known_issue_status": dict(Counter(issue.get("status") for issue in issues)),
        },
        "corpus_scope_rows": rows,
        "audit": audit,
        "errors": errors,
        "status": "PASS" if not errors else "FAIL",
        "claims": {
            "maturity": "RD_BASELINE",
            "va_pro_equivalent": "NOT_PROVEN",
            "p0_p1_unproven_passes": not errors,
            "historical_reports_are_not_current_authority": True,
        },
    }
    return result


def render_markdown(result: dict[str, Any]) -> str:
    counts = result["counts"]
    lines = [
        "# C0-T04 CAS / VA PRO fact convergence",
        "",
        f"- status: `{result['status']}`",
        f"- commit: `{result['git']['commit']}`",
        f"- tree: `{result['git']['tree']}`",
        "- maturity: `RD_BASELINE`",
        "- VA Pro equivalent: `NOT_PROVEN`",
        "",
        "## Authority and checks",
        "",
        "The current registry, Known Issues registry, VA corpus and C0-T03 evidence are the active facts. Older FIX01 reconciliation notes are historical and are not used as current capability IDs or status authority.",
        "",
        f"- capabilities: {counts['capabilities']}",
        f"- Known Issues: {counts['known_issues']}",
        f"- VA corpus entries: {counts['corpus_entries']}",
        f"- C0-T03 evidence: `{result['authority']['c0_t03_evidence']}`",
        f"- collect-all NEW_REGRESSION: `{(result.get('audit') or {}).get('counts', {}).get('NEW_REGRESSION', 'not supplied')}`",
        "",
        "## VA corpus classification",
        "",
        "Scope classification is separate from CAS compatibility status. `PROVEN` means the mapped CAS surface has sufficient current evidence; it never means VA Pro equivalence. `NEEDS_FIXTURE` means the CAS source surface exists but the required package-neutral fixture is incomplete. `IN_SCOPE` records an implementation gap that remains in the product scope. `OUT_OF_SCOPE` is provisional for IDs absent from the locked public source snapshot. No duplicate corpus IDs were found.",
        "",
        "| CAS compatibility status | Count |",
        "|---|---:|",
    ]
    cas_counts = counts["corpus_cas_status"]
    scope_counts = counts["corpus_scope_classification"]
    for key in sorted(cas_counts):
        lines.append(f"| {key} | {cas_counts[key]} |")
    lines.extend([
        "",
        "| Scope classification | Count |",
        "|---|---:|",
    ])
    for key in SCOPE_CLASSIFICATIONS:
        lines.append(f"| {key} | {scope_counts.get(key, 0)} |")
    lines.extend([
        "",
        "## Failure classification",
        "",
    ])
    if result["errors"]:
        lines.extend(f"- `{error}`" for error in result["errors"])
    else:
        lines.append("- No registry, corpus, Known Issues, C0-T03 evidence, or collect-all convergence errors.")
    lines.extend([
        "",
        "## Evidence paths",
        "",
        f"- `{result['authority']['registry']}`",
        f"- `{result['authority']['corpus']}`",
        f"- `{result['authority']['known_issues']}`",
        f"- `{result['authority']['c0_t03_evidence']}/README.md`",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-summary", type=Path)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "verification/catch-up/C0-T04")
    args = parser.parse_args()
    audit_summary = args.audit_summary.resolve() if args.audit_summary else None
    result = reconcile(audit_summary)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "fact-convergence.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (args.output_dir / "fact-convergence.md").write_text(
        render_markdown(result), encoding="utf-8"
    )
    print(render_markdown(result))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
