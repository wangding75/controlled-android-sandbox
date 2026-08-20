#!/usr/bin/env python3
"""Fail-closed aggregation for the required API32/API35/API36 A01 evidence."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Iterable

REQUIRED_API_LEVELS = ("32", "35", "36")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_evidence_sha256(payload: dict[str, Any]) -> str:
    """Hash the evidence payload without its self-referential digest field."""
    unsigned = copy.deepcopy(payload)
    unsigned.pop("evidence_sha256", None)
    encoded = json.dumps(unsigned, sort_keys=True, ensure_ascii=False,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _commit(payload: dict[str, Any]) -> str:
    return str(payload.get("tested_source_commit")
               or (payload.get("git") or {}).get("commit") or "").strip()


def _tree(payload: dict[str, Any]) -> str:
    return str(payload.get("tested_tree")
               or (payload.get("git") or {}).get("tree") or "").strip()


def _clean(payload: dict[str, Any]) -> bool:
    if "worktree_clean" in payload:
        return payload.get("worktree_clean") is True
    return not str((payload.get("git") or {}).get("status") or "").strip()


def aggregate_matrix(
    evidence_paths: Iterable[Path],
    *,
    output_path: Path | None = None,
    expected_commit: str = "",
) -> dict[str, Any]:
    paths = [Path(path) for path in evidence_paths]
    failed: list[str] = []
    per_api: dict[str, list[dict[str, Any]]] = {}
    commits: set[str] = set()
    trees: set[str] = set()

    for path in paths:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            failed.append(f"invalid_evidence:{path}:{error.__class__.__name__}")
            continue
        if not isinstance(payload, dict):
            failed.append(f"invalid_evidence:{path}:not_object")
            continue
        api = str(payload.get("api") or payload.get("api_level") or "").strip()
        commit = _commit(payload)
        tree = _tree(payload)
        clean = _clean(payload)
        digest = sha256_file(path)
        if api not in REQUIRED_API_LEVELS:
            failed.append(f"unexpected_api_{api or 'missing'}")
        if not commit:
            failed.append(f"missing_tested_source_commit:{path.name}")
        else:
            commits.add(commit)
        if tree:
            trees.add(tree)
        if not clean:
            failed.append(f"dirty_worktree:{api or path.name}")
        if payload.get("overall_pass") is not True:
            failed.append(f"api_{api or 'missing'}_overall_pass")
        recorded_digest = str(payload.get("evidence_sha256") or "").strip()
        if not recorded_digest:
            failed.append(f"missing_evidence_sha256:{path.name}")
        elif recorded_digest.lower() != canonical_evidence_sha256(payload).lower():
            failed.append(f"evidence_sha256_mismatch:{path.name}")
        per_api.setdefault(api, []).append({
            "path": str(path),
            "serial": str(payload.get("serial") or ""),
            "api": api,
            "tested_source_commit": commit,
            "tested_tree": tree,
            "worktree_clean": clean,
            "overall_pass": payload.get("overall_pass") is True,
            "sha256": digest,
            "evidence_sha256": recorded_digest,
        })

    if expected_commit:
        commits.add(expected_commit)
        if any(row.get("tested_source_commit") != expected_commit
               for rows in per_api.values() for row in rows):
            failed.append("tested_source_commit_mismatch")
    if len(commits) > 1:
        failed.append("tested_source_commit_mismatch")
    if len(trees) > 1:
        failed.append("tested_tree_mismatch")

    for api in REQUIRED_API_LEVELS:
        rows = per_api.get(api, [])
        if not rows:
            failed.append(f"missing_api_{api}")
        elif not all(row["overall_pass"] and row["worktree_clean"] for row in rows):
            failed.append(f"api_{api}_device_gate")

    tested_commit = next(iter(commits), "") if len(commits) == 1 else ""
    evidence = {
        "tested_source_commit": tested_commit,
        "required_api_levels": list(REQUIRED_API_LEVELS),
        "observed_api_levels": sorted(api for api in per_api if api),
        "per_api_evidence": {api: per_api.get(api, []) for api in REQUIRED_API_LEVELS},
        "overall_pass": not failed and all(per_api.get(api) for api in REQUIRED_API_LEVELS),
        "failed_gates": sorted(set(failed)),
    }
    if output_path is not None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n",
                               encoding="utf-8")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-commit", default="")
    args = parser.parse_args()
    result = aggregate_matrix(args.evidence, output_path=args.output,
                               expected_commit=args.expected_commit)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["overall_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
