#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from m5_device_lab import validate_formal_evidence  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--commit")
    args = parser.parse_args()
    root = args.root.resolve()
    lock = json.loads((root / "build-environment.lock.json").read_text())
    evidence = json.loads(args.evidence.read_text(encoding="utf-8-sig"))
    commit = args.commit or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    errors = validate_formal_evidence(evidence, lock, commit)
    if errors:
        print("FAIL M5-T5 formal device evidence", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1
    print("PASS M5-T5 formal device evidence")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
