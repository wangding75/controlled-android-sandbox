#!/usr/bin/env python3
"""Create a deterministic source ZIP from the repository worktree."""

from __future__ import annotations

import argparse
import os
import stat
import subprocess
import time
import zipfile
from pathlib import Path, PurePosixPath

MIN_ZIP_EPOCH = 315532800  # 1980-01-01T00:00:00Z


def run(root: Path, *args: str) -> str:
    return subprocess.check_output(args, cwd=root, text=True).strip()


def source_date_epoch(root: Path) -> int:
    raw = os.environ.get("SOURCE_DATE_EPOCH")
    if raw:
        value = int(raw)
    else:
        value = int(run(root, "git", "log", "-1", "--format=%ct"))
    return max(value, MIN_ZIP_EPOCH)


def repository_files(root: Path) -> list[str]:
    raw = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
    )
    paths = [item.decode("utf-8") for item in raw.split(b"\0") if item]
    return sorted(path for path in paths if (root / path).is_file() or (root / path).is_symlink())


def zip_info(name: str, epoch: int, mode: int) -> zipfile.ZipInfo:
    stamp = time.gmtime(epoch)[:6]
    info = zipfile.ZipInfo(name, stamp)
    info.create_system = 3
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (mode & 0xFFFF) << 16
    return info


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prefix", default="controlled-sandbox/")
    parser.add_argument(
        "--exclude-prefix",
        action="append",
        default=[],
        help="Repository-relative path prefix to omit; may be supplied more than once.",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    output = args.output.resolve()
    prefix = args.prefix.strip("/") + "/"
    epoch = source_date_epoch(root)
    excluded = tuple(prefix.strip("/") + "/" for prefix in args.exclude_prefix if prefix.strip("/"))
    files = [
        relative
        for relative in repository_files(root)
        if not any(relative == prefix[:-1] or relative.startswith(prefix) for prefix in excluded)
    ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)

    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for relative in files:
            source = root / relative
            archive_name = str(PurePosixPath(prefix) / PurePosixPath(relative))
            metadata = source.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                data = os.readlink(source).encode("utf-8")
                mode = stat.S_IFLNK | 0o777
            else:
                data = source.read_bytes()
                mode = stat.S_IFREG | (0o755 if metadata.st_mode & stat.S_IXUSR else 0o644)
            archive.writestr(zip_info(archive_name, epoch, mode), data)

    print(f"PASS reproducible source ZIP: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
