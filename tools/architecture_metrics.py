from __future__ import annotations

from pathlib import Path
import subprocess


def production_java_paths(root: Path) -> list[Path]:
    paths: list[Path] = []
    for module in root.iterdir():
        source_root = module / "src" / "main"
        if module.is_dir() and source_root.is_dir():
            paths.extend(source_root.rglob("*.java"))
    return sorted(paths)


def live_large_classes(root: Path, threshold: int = 500) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for path in production_java_paths(root):
        count = len(path.read_text(encoding="utf-8", errors="ignore").splitlines())
        if count > threshold:
            rows.append({"path": path.relative_to(root).as_posix(), "lines": count})
    return sorted(rows, key=lambda item: (-int(item["lines"]), str(item["path"])))


def commit_large_classes(root: Path, commit: str, threshold: int = 500) -> list[dict[str, object]]:
    names = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", commit],
        cwd=root, text=True, encoding="utf-8", errors="replace",
        capture_output=True, check=True,
    ).stdout.splitlines()
    rows: list[dict[str, object]] = []
    for name in names:
        parts = Path(name).parts
        if len(parts) < 5 or parts[1:3] != ("src", "main") or not name.endswith(".java"):
            continue
        content = subprocess.run(
            ["git", "show", f"{commit}:{name}"],
            cwd=root, text=True, encoding="utf-8", errors="replace",
            capture_output=True, check=True,
        ).stdout
        count = len(content.splitlines())
        if count > threshold:
            rows.append({"path": name, "lines": count})
    return sorted(rows, key=lambda item: (-int(item["lines"]), str(item["path"])))
