#!/usr/bin/env python3
"""Fail-closed continuation preflight for the CAS VA PRO task ledger."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
TASK_BOOK = ROOT / "docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_TASK_BOOK_20260821.md"
PROGRESS = ROOT / "docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md"
WORKFLOW = ROOT / "docs/capability/CAPABILITY_CAMPAIGN_WORKFLOW.md"
IDENTITY_POLICY = ROOT / "docs/COMMIT_IDENTITY_POLICY.md"
APPROVED_IDENTITIES = ROOT / "verification/approved-commit-identities.txt"
INSTANCE_NAME = "RD测试"
CANONICAL_IDENTITY = ("OpenAI", "openai@users.noreply.github.com")
ALLOWED_GUARD_FILES = {
    Path("tools/capability/run_rd_campaign.py"),
    Path("scripts/check-native-enforcement-poc.py"),
    # This C2-T03 static checker contains the historical serials only as
    # forbidden-token assertions; it never selects or connects to a device.
    Path("scripts/check-c2-t03-location.py"),
    # This C3-T02 static checker likewise asserts that historical serials are
    # forbidden; the RD runner resolves the device dynamically.
    Path("scripts/check-c3-t02-file-proc-network-fd.py"),
    Path("scripts/check-c3-t04-hostile-isolation.py"),
    Path("scripts/check-c3-t05-seccomp-decision.py"),
    Path("scripts/check-c3-t06-art-xposed-decision.py"),
    # C3 runners list historical serials only as FORBIDDEN_SERIALS. Device
    # selection still goes through resolve_rd_environment / mumu_instance.
    Path("tools/capability/run_c3_t04_rd.py"),
    Path("tools/capability/run_c3_t05_rd.py"),
    Path("tools/capability/run_c3_t06_rd.py"),
    Path("scripts/check-c4-t01-sx-freeze.py"),
    Path("tools/capability/run_c4_t01_rd.py"),
    Path("scripts/check-c4-t02-sx-adapter.py"),
    Path("tools/capability/run_c4_t02_rd.py"),
    Path("scripts/check-c4-t03-sx-migration.py"),
    Path("tools/capability/run_c4_t03_rd.py"),
    Path("scripts/check-c4-t04-cas-only-runtime.py"),
    Path("tools/capability/run_c4_t04_rd.py"),
    Path("scripts/check-c4-t05-sx-business.py"),
    Path("tools/capability/run_c4_t05_rd.py"),
}
HARD_CODED_SERIAL = re.compile(r"127\.0\.0\.1:\d+")
TASK_ROW = re.compile(
    r"^\|\s*(C\d+-T\d+)\s*\|\s*([^|]+?)\s*\|\s*"
    r"(PENDING|IN_PROGRESS|BLOCKED|DONE|NOT_APPLICABLE)\s*\|\s*([^|]+?)\s*\|"
)
HEX = re.compile(r"\b[0-9a-f]{7,40}\b", re.IGNORECASE)
PATH_TOKEN = re.compile(
    r"(?<![\w])((?:docs|artifacts|reports|verification|build)/"
    r"[A-Za-z0-9_.\-\u4e00-\u9fff/]+)"
)


class PreflightError(RuntimeError):
    pass


def read(path: Path) -> str:
    if not path.is_file():
        raise PreflightError(f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, text=True, encoding="utf-8", errors="replace",
        capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise PreflightError(f"git {' '.join(args)} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout.strip()


def parse_header(text: str, label: str) -> str:
    match = re.search(rf"^{re.escape(label)}：`?([^`\n]+)`?\s*$", text, re.MULTILINE)
    if not match:
        raise PreflightError(f"progress ledger is missing header: {label}")
    return match.group(1).strip()


def parse_rows(text: str) -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    for line in text.splitlines():
        match = TASK_ROW.match(line)
        if match:
            task_id, name, status, dependencies = match.groups()
            if task_id in rows:
                raise PreflightError(f"duplicate task row: {task_id}")
            rows[task_id] = {
                "name": name.strip(), "status": status, "dependencies": dependencies.strip()
            }
    if not rows:
        raise PreflightError("progress ledger contains no task rows")
    return rows


def expand_dependencies(value: str) -> list[str]:
    result: list[str] = []
    for raw in value.split(","):
        item = raw.strip()
        if not item:
            continue
        range_match = re.fullmatch(r"(C\d+)-T(\d+)\.\.T(\d+)", item)
        if range_match:
            phase, start, end = range_match.groups()
            result.extend(f"{phase}-T{index:02d}" for index in range(int(start), int(end) + 1))
        else:
            result.append(item)
    return result


def dependency_ready(dependency: str, rows: dict[str, dict[str, str]], last_completed: str) -> bool:
    if dependency == "BOOTSTRAP-DOCS":
        return last_completed == dependency
    if re.fullmatch(r"C\d+", dependency):
        members = [row for task_id, row in rows.items() if task_id.startswith(dependency + "-")]
        return bool(members) and all(row["status"] in {"DONE", "NOT_APPLICABLE"} for row in members)
    return rows.get(dependency, {}).get("status") in {"DONE", "NOT_APPLICABLE"}


def find_ready_task(rows: dict[str, dict[str, str]], last_completed: str) -> str | None:
    for task_id, row in rows.items():
        if row["status"] != "PENDING":
            continue
        dependencies = expand_dependencies(row["dependencies"])
        if all(dependency_ready(item, rows, last_completed) for item in dependencies):
            return task_id
    return None


def receipt_section(text: str, task_id: str) -> str:
    heading = rf"^###\s+{re.escape(task_id)}(?:\s+[^\n:：]+)?(?:：|:).*?$"
    matches = list(re.finditer(heading, text, re.MULTILINE))
    if not matches:
        raise PreflightError(f"receipt not found for {task_id}")
    # Receipts are append-only, so repeated task headings are historical
    # attempts.  Continuation must inspect the latest appended receipt.
    match = matches[-1]
    end = re.search(r"^###\s+|^##\s+", text[match.end():], re.MULTILINE)
    return text[match.end(): match.end() + (end.start() if end else len(text))]


def extract_receipt(text: str, task_id: str) -> dict[str, Any]:
    section = receipt_section(text, task_id)
    status_match = re.search(r"\*\*状态\*\*：([^\n]+)", section)
    if not status_match:
        raise PreflightError(f"receipt {task_id} has no status")
    commits = []
    for line in section.splitlines():
        if "提交 SHA" in line:
            commits.extend(HEX.findall(line))
    commits = list(dict.fromkeys(commits))
    if not commits:
        raise PreflightError(f"receipt {task_id} has no implementation commit")
    missing_commits = [sha for sha in commits if subprocess.run(
        ["git", "cat-file", "-e", f"{sha}^{{commit}}"], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    ).returncode != 0]
    if missing_commits:
        raise PreflightError(f"receipt {task_id} references missing commits: {missing_commits}")
    path_candidates = []
    for token in PATH_TOKEN.findall(section):
        clean = token.rstrip("`.,;:，。；：）)")
        path = ROOT / clean
        if clean not in path_candidates and path.is_file():
            path_candidates.append(clean)
    if not path_candidates and task_id == "BOOTSTRAP-DOCS":
        # The bootstrap receipt predates the formal evidence-path field.  Its
        # task book and ledger are the durable, readable continuation evidence.
        path_candidates = [
            str(TASK_BOOK.relative_to(ROOT)).replace("\\", "/"),
            str(PROGRESS.relative_to(ROOT)).replace("\\", "/"),
        ]
    if not path_candidates:
        raise PreflightError(f"receipt {task_id} has no readable evidence/file path")
    baseline_match = re.search(r"开始基线.*?@\s*`?([0-9a-f]{7,40})", section, re.IGNORECASE)
    if not baseline_match:
        # Receipts may write `**开始基线**：`sha`` without an "@" separator.
        baseline_match = re.search(
            r"开始基线.*?`([0-9a-f]{7,40})`", section, re.IGNORECASE | re.DOTALL
        )
    if not baseline_match:
        raise PreflightError(f"receipt {task_id} has no baseline commit")
    receipt_subject = f"docs(progress): record [{task_id}] receipt"
    receipt_commits = git("log", "--format=%H", "--all", "--fixed-strings", "--grep", receipt_subject)
    receipt_commit = receipt_commits.splitlines()[0] if receipt_commits else ""
    if not receipt_commit:
        raise PreflightError(f"receipt commit not found for {task_id}")
    return {
        "task_id": task_id,
        "status": status_match.group(1).strip(),
        "implementation_commits": commits,
        "receipt_commit": receipt_commit,
        "baseline_commit": baseline_match.group(1),
        "evidence_paths": path_candidates,
    }


def scan_executable_serials() -> dict[str, Any]:
    findings: list[dict[str, str]] = []
    allowlisted: list[dict[str, str]] = []
    for root in (ROOT / "scripts", ROOT / "tools"):
        for path in root.rglob("*"):
            if path.suffix.lower() not in {".py", ".ps1", ".sh"} or not path.is_file():
                continue
            relative = path.relative_to(ROOT)
            if path.name.startswith("test_") or path.name.startswith("test-"):
                continue
            for match in HARD_CODED_SERIAL.finditer(path.read_text(encoding="utf-8", errors="replace")):
                item = {"file": str(relative).replace("\\", "/"), "serial": match.group(0)}
                if relative in ALLOWED_GUARD_FILES:
                    allowlisted.append(item)
                else:
                    findings.append(item)
    return {"unexpected": findings, "allowlisted_guards": allowlisted}


def resolve_device() -> dict[str, Any]:
    sys.path.insert(0, str(ROOT / "scripts"))
    import mumu_instance

    try:
        snapshot = mumu_instance.resolve_instance(INSTANCE_NAME)
    except mumu_instance.ResolutionError as exc:
        raise PreflightError(f"RD_ENVIRONMENT_RESOLUTION_BLOCKED: {exc}") from exc
    required = ("instanceName", "resolvedSerial", "runtimeStatus", "api", "abiList", "bootId")
    missing = [key for key in required if not str(snapshot.get(key) or "").strip()]
    if missing or snapshot.get("instanceName") != INSTANCE_NAME or snapshot.get("runtimeStatus") != "device":
        raise PreflightError(f"invalid RD snapshot: missing={missing}, instance={snapshot.get('instanceName')!r}, "
                             f"state={snapshot.get('runtimeStatus')!r}")
    return snapshot


def run(output: Path) -> dict[str, Any]:
    for path in (TASK_BOOK, PROGRESS, WORKFLOW, IDENTITY_POLICY, APPROVED_IDENTITIES):
        read(path)
    progress = read(PROGRESS)
    rows = parse_rows(progress)
    branch = parse_header(progress, "任务分支")
    next_task = parse_header(progress, "下一任务")
    last_completed = parse_header(progress, "最后完成任务")
    active = [task_id for task_id, row in rows.items() if row["status"] == "IN_PROGRESS"]
    if len(active) > 1:
        raise PreflightError(f"multiple IN_PROGRESS tasks: {active}")
    if active and active[0] != next_task:
        raise PreflightError(f"active task {active[0]} differs from ledger next task {next_task}")
    ready = find_ready_task(rows, last_completed)
    if not active and ready != next_task:
        raise PreflightError(f"ledger next task {next_task} is not first dependency-ready PENDING task {ready}")
    if last_completed not in {"BOOTSTRAP-DOCS"} and last_completed not in rows:
        raise PreflightError(f"unknown last completed task: {last_completed}")
    receipt = extract_receipt(progress, last_completed)
    identity = (git("config", "--local", "--get", "user.name"), git("config", "--local", "--get", "user.email"))
    if identity != CANONICAL_IDENTITY:
        raise PreflightError(f"non-canonical local Git identity: {identity}")
    current_branch = git("branch", "--show-current")
    if current_branch != branch:
        raise PreflightError(f"current branch {current_branch!r} differs from ledger branch {branch!r}")
    remote_branch = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    expected_remote = f"origin/{branch}"
    if remote_branch != expected_remote:
        raise PreflightError(f"upstream {remote_branch!r} differs from {expected_remote!r}")
    local_head = git("rev-parse", "HEAD")
    remote_head = git("ls-remote", "--heads", "origin", branch).split()[0]
    if local_head != remote_head:
        raise PreflightError(f"local/remote HEAD mismatch: {local_head} != {remote_head}")
    scan = scan_executable_serials()
    if scan["unexpected"]:
        raise PreflightError(f"unexpected hard-coded executable serials: {scan['unexpected']}")
    device = resolve_device()
    evidence_dir = ROOT / "verification" / "catch-up" / next_task
    payload: dict[str, Any] = {
        "schema_version": 1,
        "task_id": next_task,
        "status": "PASS",
        "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "ledger": {
            "branch": branch, "next_task": next_task, "active_task": active[0] if active else "",
            "last_completed": last_completed, "first_ready_pending": ready,
            "dependencies": rows[next_task]["dependencies"],
        },
        "last_receipt": receipt,
        "git": {
            "branch": current_branch, "head": local_head, "remote_branch": remote_branch,
            "remote_head": remote_head, "identity": {"name": identity[0], "email": identity[1]},
            "status_short": git("status", "--short"),
        },
        "device": {
            "instance_name": device["instanceName"], "resolved_serial": device["resolvedSerial"],
            "api": device["api"], "abi": device["abiList"], "boot_id": device["bootId"],
            "android_id": device["androidId"], "model": device["model"],
        },
        "static_checks": {
            "required_files": [str(path.relative_to(ROOT)).replace("\\", "/") for path in
                                (TASK_BOOK, PROGRESS, WORKFLOW, IDENTITY_POLICY, APPROVED_IDENTITIES)],
            "unexpected_hard_coded_serials": scan["unexpected"],
            "allowlisted_historical_guards": scan["allowlisted_guards"],
        },
        "evidence_directory": str(evidence_dir.relative_to(ROOT)).replace("\\", "/"),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "verification/catch-up/C0-T01/continuation-preflight.json")
    args = parser.parse_args()
    output = args.output if args.output.is_absolute() else ROOT / args.output
    try:
        payload = run(output)
    except PreflightError as exc:
        print(f"FAIL C0-T01 continuation preflight: {exc}")
        return 1
    print(f"PASS C0-T01 continuation preflight: {payload['evidence_directory']}")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
