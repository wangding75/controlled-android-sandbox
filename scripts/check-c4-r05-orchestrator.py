#!/usr/bin/env python3
"""Static contract checks for the C4-R05 formal closure orchestrator."""

from __future__ import annotations

import ast
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "tools/capability/run_c4_r05_rd.py"
R02 = ROOT / "tools/capability/run_c4_r02_rd.py"


def main() -> int:
    errors: list[str] = []
    for path in (RUNNER, R02):
        if not path.is_file():
            errors.append(f"missing {path.relative_to(ROOT)}")
            continue
        try:
            ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except SyntaxError as error:
            errors.append(f"syntax {path.relative_to(ROOT)}: {error}")
    text = RUNNER.read_text(encoding="utf-8") if RUNNER.is_file() else ""
    required = (
        "clean-install-cold", "retained-hot-recovery", "run_c4_r04_rd.py",
        "run_c4_r02_rd.py", "run_c4_r03_rd.py", "FIRST_FRAME_DRAWN",
        "reported_drawn_false", "surfaceNonEmpty", "nonBlack", "retryBudget",
        "automaticRetryPerformed", "firstFailure", "pressure-minutes",
        "c1-activity", "c2-window-audio", "c4-cas-only", "sx-f1-f5-business",
        "artifact-index.json",
    )
    for token in required:
        if token not in text:
            errors.append(f"missing R05 contract token: {token}")
    if re.search(r"\btime\.sleep\s*\(", text):
        errors.append("R05 orchestrator must not use fixed sleep for readiness")
    if "--resume" in text:
        errors.append("R05 orchestrator must not invoke a resume lane")
    if "return_code = 0" not in text or "return_code = 1" not in text:
        errors.append("R05 orchestrator does not expose fail/pass process status")
    if "expected_rounds = 2" not in text:
        errors.append("R05 stage and overall scopes must both require two formal rounds")
    if 'round_names = ("clean-install-cold", "retained-hot-recovery")' not in text:
        errors.append("R05 formal round sequence is not fixed to clean then retained")
    if errors:
        for error in errors:
            print(f"FAIL {error}")
        return 1
    print("PASS C4-R05 formal closure orchestrator static contract")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
