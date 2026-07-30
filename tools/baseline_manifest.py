#!/usr/bin/env python3
"""Write or verify the immutable uploaded-baseline content manifest."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
from pathlib import Path

TAG = "baseline-b3-t5a-upload"
BRANCH = "baseline/b3-t5a-upload"
REMOTE_BRANCH = "refs/remotes/origin/baseline/b3-t5a-upload"
OUTPUT = "verification/BASELINE_SHA256SUMS.txt"


def git(root: Path, *args: str, binary: bool = False):
    result = subprocess.check_output(["git", *args], cwd=root)
    return result if binary else result.decode().strip()



def resolve_optional(root: Path, *references: str) -> tuple[str, str] | tuple[None, None]:
    for reference in references:
        result = subprocess.run(["git", "rev-parse", "--verify", f"{reference}^{{commit}}"],
                                cwd=root, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL)
        if result.returncode == 0:
            return reference, result.stdout.strip()
    return None, None


def entries(root: Path) -> list[tuple[str, str]]:
    raw = git(root, "ls-tree", "-r", "-z", "--name-only", TAG, binary=True)
    paths = [item.decode() for item in raw.split(b"\0") if item]
    result=[]
    for path in sorted(paths):
        content = git(root, "show", f"{TAG}:{path}", binary=True)
        result.append((hashlib.sha256(content).hexdigest(), path))
    return result


def main() -> int:
    parser=argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    if not (root / ".git").exists():
        output=root/OUTPUT
        record=root/"verification/baseline-import.json"
        if not output.is_file() or not record.is_file():
            raise SystemExit("Source archive has no Git metadata and baseline evidence files are missing")
        line_count=sum(1 for line in output.read_text().splitlines() if line.strip())
        if line_count != 387:
            raise SystemExit(f"Frozen baseline manifest entry count mismatch: {line_count}")
        print("PASS frozen baseline evidence manifest present (source archive mode, 387 files)")
        return 0
    tag_commit=git(root,"rev-parse",f"{TAG}^{{commit}}")
    branch_reference, branch_commit = resolve_optional(root, BRANCH, REMOTE_BRANCH)
    if branch_commit is not None and tag_commit != branch_commit:
        raise SystemExit(f"Frozen baseline branch/tag mismatch ({branch_reference}): "
                         f"{branch_commit} != {tag_commit}")
    lines=[f"{digest}  {path}" for digest,path in entries(root)]
    output=root/OUTPUT
    rendered="\n".join(lines)+"\n"
    if args.write:
        output.write_text(rendered)
        print(f"PASS wrote frozen baseline manifest: {output}")
        return 0
    if not output.is_file():
        raise SystemExit(f"Missing frozen baseline manifest: {output}")
    if output.read_text() != rendered:
        raise SystemExit("Frozen baseline manifest does not match baseline tag")
    reference_note = branch_reference or "tag-only"
    print(f"PASS frozen baseline integrity ({tag_commit[:12]}, {len(lines)} files, {reference_note})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
