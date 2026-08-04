#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "build/verification/gradle-lock-state.json"
DYNAMIC = re.compile(r"(?:\+|latest|snapshot|release\]|\[[^]]*[,)]|\([^)]*,[^)]*\))", re.I)


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    value.update(path.read_bytes())
    return value.hexdigest()


def lock_files() -> list[Path]:
    result = []
    for path in ROOT.rglob("gradle.lockfile"):
        relative = path.relative_to(ROOT)
        if any(part in {".git", ".gradle", "build", "ref"} for part in relative.parts):
            continue
        result.append(path)
    return sorted(result)


def parse_lock(path: Path) -> tuple[list[str], list[str]]:
    coordinates: list[str] = []
    errors: list[str] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            errors.append(f"{path.relative_to(ROOT)}:{line_number}: malformed lock row")
            continue
        coordinate, configurations = line.split("=", 1)
        if coordinate != "empty":
            pieces = coordinate.split(":")
            if len(pieces) != 3 or any(not item for item in pieces):
                errors.append(f"{path.relative_to(ROOT)}:{line_number}: invalid coordinate {coordinate}")
            elif DYNAMIC.search(pieces[-1]):
                errors.append(f"{path.relative_to(ROOT)}:{line_number}: dynamic coordinate {coordinate}")
            else:
                coordinates.append(coordinate)
        if not configurations.strip():
            errors.append(f"{path.relative_to(ROOT)}:{line_number}: empty configuration set")
    return coordinates, errors


def clean_errors(paths: list[Path]) -> list[str]:
    relatives = [str(path.relative_to(ROOT)) for path in paths]
    tracked = subprocess.run(["git", "diff", "--exit-code", "--", *relatives], cwd=ROOT,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    status = subprocess.run(["git", "status", "--porcelain", "--", *relatives], cwd=ROOT,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True)
    errors = []
    if tracked.returncode != 0:
        errors.append("generated Gradle lock state differs from the checked-in files")
    if status.stdout.strip():
        errors.append("generated Gradle lock state is modified or untracked: " + status.stdout.strip())
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("verify",))
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()
    errors: list[str] = []
    files = lock_files()
    if not files:
        errors.append("no Gradle-generated gradle.lockfile exists; run resolveAndLockAll --write-locks")
    rows = []
    all_coordinates: list[str] = []
    for path in files:
        coordinates, lock_errors = parse_lock(path)
        errors.extend(lock_errors)
        all_coordinates.extend(coordinates)
        rows.append({
            "path": str(path.relative_to(ROOT)).replace("\\", "/"),
            "sha256": sha256(path),
            "coordinateCount": len(coordinates),
        })
    if args.require_clean and files:
        errors.extend(clean_errors(files))
    report = {
        "status": "PASS" if not errors else "FAIL",
        "lockFileCount": len(files),
        "coordinateCount": len(set(all_coordinates)),
        "lockFiles": rows,
        "errors": errors,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if errors:
        print("FAIL Gradle-generated dependency lock state", file=sys.stderr)
        for error in errors:
            print(" - " + error, file=sys.stderr)
        return 1
    print(f"PASS Gradle-generated dependency lock state ({len(files)} files, "
          f"{len(set(all_coordinates))} coordinates)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
