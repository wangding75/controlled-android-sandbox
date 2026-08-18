#!/usr/bin/env python3
"""Build the T57-R03-P4-R05 independent review pack. No parity verdict."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACK_DIR = ROOT / "_delivery" / "T57-R03-P4-independent-review"
ZIP_PATH = ROOT / "_delivery" / "controlled-android-sandbox_T57-R03_P4_R05_independent_review_pack.zip"

INCLUDE_DIRS = (
    "app", "sandbox-sdk", "sandbox-contract", "sandbox-domain", "sandbox-framework",
    "sandbox-runtime", "sandbox-native", "sandbox-companion32", "fixture-basic",
    "fixture-compat32", "fixture-activity-scale", "fixture-lifecycle",
    "tools", "scripts", "docs", "verification", "gradle",
)
INCLUDE_FILES = (
    "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
    "gradle.properties", "gradlew", "gradlew.bat", "README.md", "AGENTS.md",
)
EXCLUDE_PARTS = (
    ".git", "build", ".gradle", ".cxx", ".idea", "node_modules", "caches",
    "keystores", ".keystore", "local.properties", "_delivery",
)


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, text=True, encoding="utf-8",
                            errors="replace", capture_output=True, check=False)
    return result.stdout


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def should_skip(path: Path) -> bool:
    parts = set(path.parts)
    return any(part in EXCLUDE_PARTS for part in parts) or path.suffix in {".keystore", ".jks"}


def copy_tree() -> None:
    if PACK_DIR.exists():
        shutil.rmtree(PACK_DIR)
    source = PACK_DIR / "source"
    source.mkdir(parents=True)
    for name in INCLUDE_DIRS:
        src = ROOT / name
        if src.is_dir():
            shutil.copytree(src, source / name, ignore=shutil.ignore_patterns(
                "build", ".gradle", ".cxx", "*.keystore", "*.jks", "local.properties"))
    for name in INCLUDE_FILES:
        src = ROOT / name
        if src.is_file():
            shutil.copy2(src, source / name)


def write_git(head: str, tree: str) -> None:
    git_dir = PACK_DIR / "git"
    git_dir.mkdir(parents=True, exist_ok=True)
    (git_dir / "log-all.txt").write_text(git("log", "--all", "--oneline"), encoding="utf-8")
    (git_dir / "log-full.txt").write_text(git("log", "--all"), encoding="utf-8")
    (git_dir / "status.txt").write_text(git("status", "--short"), encoding="utf-8")
    (git_dir / "tracked.txt").write_text(git("ls-files"), encoding="utf-8")
    bundle = git_dir / "t57-r03.bundle"
    subprocess.run(["git", "bundle", "create", str(bundle), "--all"], cwd=ROOT, check=False)
    (git_dir / "HEAD").write_text(head + "\n", encoding="utf-8")
    (git_dir / "TREE").write_text(tree + "\n", encoding="utf-8")


def write_reports() -> None:
    reports = PACK_DIR / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    review = ROOT / "docs" / "review"
    if review.is_dir():
        shutil.copytree(review, reports / "review", dirs_exist_ok=True)
    capability = ROOT / "docs" / "capability"
    if capability.is_dir():
        shutil.copytree(capability, PACK_DIR / "capability", dirs_exist_ok=True)
    artifacts = ROOT / "artifacts" / "capability-audit"
    if artifacts.is_dir():
        shutil.copytree(artifacts, PACK_DIR / "device-evidence" / "capability-audit",
                        dirs_exist_ok=True)


def write_inventory() -> dict:
    files = []
    for path in PACK_DIR.rglob("*"):
        if path.is_file():
            rel = path.relative_to(PACK_DIR).as_posix()
            files.append({
                "path": rel,
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            })
    inventory = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "file_count": len(files),
        "files": files,
    }
    (PACK_DIR / "manifest").mkdir(exist_ok=True)
    (PACK_DIR / "manifest" / "REVIEW_PACK_SHA256.txt").write_text(
        "\n".join(f"{row['sha256']}  {row['path']}" for row in files) + "\n",
        encoding="utf-8",
    )
    return inventory


def main() -> int:
    head = git("rev-parse", "HEAD").strip()
    tree = git("rev-parse", "HEAD^{tree}").strip()
    copy_tree()
    write_git(head, tree)
    write_reports()
    inventory = write_inventory()
    manifest = {
        "project": "controlled-android-sandbox",
        "taskbook": "T57-R03-P4-FIX01",
        "branch": git("branch", "--show-current").strip(),
        "HEAD": head,
        "TREE": tree,
        "generated_at": inventory["generated_at"],
        "source_file_count": inventory["file_count"],
        "result": "REVIEW_PACK_READY",
        "va_pro_verdict": "NOT_ISSUED_BY_AGENT",
        "exclusions": list(EXCLUDE_PARTS),
    }
    (PACK_DIR / "manifest" / "REVIEW_PACK_MANIFEST.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8")
    if ZIP_PATH.exists():
        ZIP_PATH.unlink()
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in PACK_DIR.rglob("*"):
            if path.is_file():
                archive.write(path, path.relative_to(PACK_DIR.parent).as_posix())
    print(json.dumps({
        "pack_dir": str(PACK_DIR),
        "zip": str(ZIP_PATH),
        "sha256": sha256_file(ZIP_PATH),
        "files": inventory["file_count"],
        "HEAD": head,
        "TREE": tree,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
