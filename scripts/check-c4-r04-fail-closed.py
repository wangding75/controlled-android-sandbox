#!/usr/bin/env python3
"""Static contract checks for the C4-R04 acceptance orchestrator."""

from __future__ import annotations

import ast
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/capability/run_c4_r04_rd.py"
PREDICATES = ROOT / "tools/capability/c4_r04_fail_closed.py"


def main() -> int:
    errors: list[str] = []
    for path in (RUNNER, PREDICATES):
        if not path.is_file():
            errors.append(f"missing {path.relative_to(ROOT)}")
            continue
        try:
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError as error:
            errors.append(f"syntax {path.relative_to(ROOT)}: {error}")
    runner_text = RUNNER.read_text(encoding="utf-8") if RUNNER.is_file() else ""
    predicate_text = PREDICATES.read_text(encoding="utf-8") if PREDICATES.is_file() else ""
    if re.search(r"\btime\.sleep\s*\(", runner_text + predicate_text):
        errors.append("fixed sleep is forbidden in the C4-R04 runner/predicates")
    required_tokens = (
        "FIRST_FRAME_DRAWN", "windowsCount", "reportedDrawn", "hasVisible",
        "surfaceCount", "hostPlaceholder", "retryDecision", "artifact-index.json",
    )
    for token in required_tokens:
        if token not in runner_text + predicate_text:
            errors.append(f"missing fail-closed contract token: {token}")
    if "GUEST_ACTIVITY_CREATE" in predicate_text and "static_markers_are_non_authoritative" not in predicate_text:
        errors.append("static marker appears without an explicit non-authoritative predicate")
    if errors:
        for error in errors:
            print(f"FAIL {error}")
        return 1
    print("PASS C4-R04 fail-closed static contract")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
