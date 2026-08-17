"""Shared helpers for capability campaign tooling. Read-only with respect to Git."""

from __future__ import annotations

import datetime as dt
import json
import os
import platform
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text(encoding="utf-8-sig"))


def git_value(*arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def git_identity() -> dict[str, str]:
    return {
        "branch": git_value("branch", "--show-current"),
        "commit": git_value("rev-parse", "HEAD"),
        "tree": git_value("rev-parse", "HEAD^{tree}"),
        "parent": git_value("rev-parse", "HEAD^"),
        "status": git_value("status", "--short"),
    }


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def host_os() -> str:
    return f"{platform.system()} {platform.release()} {platform.machine()}"


def artifacts_dir(campaign: str, timestamp: str | None = None) -> Path:
    stamp = timestamp or dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = ROOT / "artifacts" / "capability-audit" / campaign / stamp
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_schema() -> dict[str, Any]:
    return json.loads((Path(__file__).with_name("evidence_schema.json")).read_text(encoding="utf-8"))


def validate_evidence(payload: dict[str, Any]) -> list[str]:
    schema = load_schema()
    required = schema.get("required", [])
    errors: list[str] = []
    for field in required:
        if field not in payload:
            errors.append(f"missing required evidence field: {field}")
    allowed = set(schema.get("properties", {}))
    for key in payload:
        if key not in allowed:
            errors.append(f"unknown evidence field: {key}")
    maturity = payload.get("maturity_level")
    if maturity not in {"RD_BASELINE", "ANDROID_MATRIX", "OEM_COMMERCIAL_MATRIX"}:
        errors.append(f"invalid maturity_level: {maturity!r}")
    if payload.get("va_pro_equivalent") == "PASS" and payload.get("maturity_level") == "RD_BASELINE":
        errors.append("RD_BASELINE evidence must not claim va_pro_equivalent=PASS")
    return errors


def python_command() -> list[str]:
    return [sys.executable]


def expand_command(command: list[str]) -> list[str]:
    if not command:
        return command
    if command[0] == "python":
        return python_command() + command[1:]
    return command


def run_command(
    command: list[str],
    *,
    timeout_sec: int,
    env: dict[str, str] | None = None,
) -> dict[str, Any]:
    merged = os.environ.copy()
    if env:
        merged.update(env)
    # Keep campaign tools from writing bytecode into the source tree.
    merged.setdefault("PYTHONDONTWRITEBYTECODE", "1")
    started = dt.datetime.now(dt.timezone.utc)
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=timeout_sec,
            check=False,
            env=merged,
        )
        return {
            "command": command,
            "returncode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "timed_out": False,
            "started": started.isoformat(),
            "finished": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
    except subprocess.TimeoutExpired as exc:
        return {
            "command": command,
            "returncode": 124,
            "stdout": exc.stdout or "",
            "stderr": (exc.stderr or "") + f"\nTIMEOUT after {timeout_sec}s",
            "timed_out": True,
            "started": started.isoformat(),
            "finished": dt.datetime.now(dt.timezone.utc).isoformat(),
        }
