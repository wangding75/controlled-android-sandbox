#!/usr/bin/env python3
"""Run the C3-T03 four-ABI/native-media campaign on dynamically resolved RD测试."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import (  # noqa: E402
    ROOT,
    artifacts_dir,
    git_identity,
    host_os,
    now_iso,
    validate_evidence,
    write_json,
)
from run_p1_00_rd import debug_command  # noqa: E402
from run_rd_campaign import (  # noqa: E402
    CampaignBlocked,
    GUEST_PACKAGE,
    HOST_PACKAGE,
    apk_metadata,
    install_rd_apks,
    resolve_rd_environment,
    run_adb,
)


TASK_ID = "C3-T03"
FIXTURE64 = "com.warden.controlledsandbox.fixture"
FIXTURE32 = "com.warden.controlledsandbox.fixture32"
ACTIVITY = "com.warden.controlledsandbox.fixture.C3T03NativeMediaActivity"
GUEST_RESULT = f"files/instances/u0/{FIXTURE64}/data/files/c3-t03-native-media.json"
DIRECT_RESULT = "files/c3-t03-native-media.json"
STATIC_GATES = (
    "scripts/check-c3-t03-abi-elf.py",
    "scripts/check-native-abi-companion.py",
    "scripts/check-m5-t2-cross-width-runtime.py",
    "scripts/check-c2-t04-camera.py",
)
COMPANION_PROBE = "tools/device/t57_rd_cross_abi_recovery_probe.ps1"


def run_static_gates() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for relative in STATIC_GATES:
        completed = subprocess.run(
            [sys.executable, str(ROOT / relative)],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        rows.append({
            "gate": relative,
            "returncode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
        })
    return rows


def pull_json(
    serial: str,
    package: str,
    relative_file: str,
    output: Path,
    label: str,
    timeout_sec: int = 45,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_sec
    last_text = ""
    while time.time() < deadline:
        pulled = run_adb(serial, ["shell", "run-as", package, "cat", relative_file], check=False)
        last_text = (pulled.stdout or "").strip()
        if last_text.startswith("{"):
            try:
                payload = json.loads(last_text)
            except json.JSONDecodeError:
                payload = None
            if isinstance(payload, dict) and payload.get("taskId") == TASK_ID:
                write_json(output / f"{label}.json", payload)
                return payload
        time.sleep(0.5)
    (output / f"{label}-last.txt").write_text(last_text, encoding="utf-8")
    return None


def capture_logcat(serial: str, output: Path, label: str) -> str:
    logcat = run_adb(
        serial,
        [
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-s",
            "CS_C3_T03_NATIVE_MEDIA:V",
            "CS_COMMAND:V",
            "CS_RUNTIME:V",
            "CS_FIXTURE:V",
            "AndroidRuntime:E",
            "ActivityManager:E",
        ],
        check=False,
    )
    text = logcat.stdout or ""
    (output / f"{label}-logcat.txt").write_text(text, encoding="utf-8")
    return text


def start_direct(serial: str, package: str, output: Path, label: str) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", package], check=False)
    run_adb(serial, ["shell", "run-as", package, "rm", "-f", DIRECT_RESULT], check=False)
    started = run_adb(
        serial,
        [
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{package}/{ACTIVITY}",
            "--es",
            "cas.native.context",
            "DIRECT_FIXTURE",
        ],
        check=False,
    )
    result = pull_json(serial, package, DIRECT_RESULT, output, label)
    logcat = capture_logcat(serial, output, label)
    return {
        "label": label,
        "package": package,
        "start_returncode": started.returncode,
        "start_stdout": started.stdout,
        "start_stderr": started.stderr,
        "result": result,
        "log_marker": "C3_T03_NATIVE_MEDIA_RESULT_END" in logcat,
    }


def start_sandbox(serial: str, output: Path) -> dict[str, Any]:
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    run_adb(serial, ["shell", "am", "force-stop", GUEST_PACKAGE], check=False)
    run_adb(serial, ["shell", "run-as", HOST_PACKAGE, "rm", "-f", GUEST_RESULT], check=False)
    payload = debug_command(
        serial,
        [
            "--es", "command", "c3-t03-native-media",
            "--es", "package", GUEST_PACKAGE,
            "--ei", "user", "0",
            "--ez", "trustNativeGuest", "true",
        ],
        deadline_sec=90,
    )
    result = pull_json(serial, HOST_PACKAGE, GUEST_RESULT, output, "sandbox")
    logcat = capture_logcat(serial, output, "sandbox")
    return {
        "launch": payload,
        "result": result,
        "log_marker": "C3_T03_NATIVE_MEDIA_RESULT_END" in logcat,
    }


def validate_probe(
    label: str,
    payload: dict[str, Any] | None,
    expected_context: str,
    expected_abi: str,
    expected_page_size: int,
    errors: list[str],
) -> dict[str, Any]:
    if not isinstance(payload, dict):
        errors.append(f"{label}: missing native media JSON")
        return {"label": label, "status": "MISSING"}
    status = str(payload.get("status") or "")
    context = str(payload.get("context") or "")
    observed_abi = str(payload.get("compiledAbi") or "")
    observed_page = int(payload.get("nativePageSize") or 0)
    if status != "PASS": errors.append(f"{label}: overall status={status}")
    if context != expected_context: errors.append(f"{label}: context={context!r}")
    if observed_abi != expected_abi:
        errors.append(f"{label}: compiledAbi={observed_abi!r}, expected {expected_abi!r}")
    if observed_page != expected_page_size:
        errors.append(f"{label}: nativePageSize={observed_page}, device={expected_page_size}")
    late = payload.get("lateDlopen") or {}
    surface = payload.get("surface") or {}
    codec = payload.get("codec") or {}
    checks = {
        "lateDlopen": late.get("status"),
        "nativeSurface": surface.get("nativeStatus"),
        "imageCallback": surface.get("imageStatus"),
        "codec": codec.get("status"),
        "cleanup": payload.get("cleanup"),
    }
    for check, observed in checks.items():
        if observed != "PASS": errors.append(f"{label}: {check}={observed!r}")
    return {
        "label": label,
        "status": "PASS" if not any(item.startswith(f"{label}:") for item in errors) else "FAIL",
        "context": context,
        "compiledAbi": observed_abi,
        "nativePageSize": observed_page,
        "checks": checks,
        "result": payload,
    }


def run_companion_probe(instance_name: str, output: Path) -> dict[str, Any]:
    powershell = "powershell" if shutil_which("powershell") else "pwsh"
    probe = ROOT / COMPANION_PROBE
    if not probe.is_file() or not powershell:
        return {
            "status": "BLOCKED",
            "returncode": 1,
            "stderr": "cross-ABI PowerShell probe or PowerShell is unavailable",
        }
    identity = {
        "product": "controlled-android-sandbox",
        "releaseTrain": "m5-t19-1",
        "versionCode": 19,
        "versionName": "0.5.19.1-source",
        "protocol": 1,
        "verification": "static NativeCompanionIdentity contract plus Host requireCompatible during prepare",
    }
    attempts: list[dict[str, Any]] = []
    for attempt in range(1, 3):
        destination = output / ("companion-cross-abi" if attempt == 1
                                else f"companion-cross-abi-retry-{attempt}")
        command = [
            powershell,
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(probe),
            "-InstanceName",
            instance_name,
            "-OutputDirectory",
            str(destination),
        ]
        completed = subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=300,
            check=False,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        prefix = "companion-cross-abi" if attempt == 1 else f"companion-cross-abi-retry-{attempt}"
        (output / f"{prefix}.stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (output / f"{prefix}.stderr.txt").write_text(completed.stderr, encoding="utf-8")
        result_candidates = sorted(destination.glob("*-result.json"))
        result_path = result_candidates[0] if result_candidates else (
            destination / "RD-08-cross-abi-process-death-generation-recovery-result.json"
        )
        try:
            result = (json.loads(result_path.read_text(encoding="utf-8-sig"))
                      if result_path.is_file() else {})
        except (OSError, json.JSONDecodeError) as error:
            result = {"status": "FAIL", "error": f"result-read:{error}"}
        native_status = str(
            ((result.get("prepare") or {}).get("companion") or {}).get("nativeStatus") or ""
        )
        passed = (completed.returncode == 0 and result.get("status") == "PASS"
                  and "bitness=32" in native_status)
        attempt_result = {
            "attempt": attempt,
            "status": "PASS" if passed else "FAIL",
            "returncode": completed.returncode,
            "stdout": completed.stdout,
            "stderr": completed.stderr,
            "result": result,
            "nativeStatus": native_status,
            "resultPath": str(result_path),
        }
        attempts.append(attempt_result)
        if passed:
            return {**attempt_result, "identity": identity, "attempts": attempts,
                    "retryCount": attempt - 1}
        retryable = "GUEST_PREPARED_MARKER_TIMEOUT" in str(result.get("error") or "")
        if not retryable:
            break
    final = attempts[-1]
    return {**final, "identity": identity, "attempts": attempts,
            "retryCount": len(attempts) - 1}


def shutil_which(command: str) -> str | None:
    import shutil

    return shutil.which(command)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", "--instance-name", dest="instance", default="RD测试")
    args = parser.parse_args()
    output = artifacts_dir("catch-up-c3-t03")
    verification = ROOT / "verification/catch-up/C3-T03"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    errors: list[str] = []
    static_rows: list[dict[str, Any]] = []
    environment: dict[str, Any] = {}
    direct_rows: dict[str, Any] = {}
    sandbox_row: dict[str, Any] = {}
    companion: dict[str, Any] = {}
    build = apk_metadata()
    write_json(output / "build-metadata.json", build)
    try:
        static_rows = run_static_gates()
        write_json(output / "static-gates.json", static_rows)
        failed = [row["gate"] for row in static_rows if row["returncode"] != 0]
        if failed:
            raise RuntimeError(f"static gates failed: {failed}")
        environment = resolve_rd_environment(args.instance)
        serial = str(environment["adb_serial"])
        page_output = run_adb(serial, ["shell", "getconf", "PAGE_SIZE"], check=False)
        page_size_text = (page_output.stdout or "").strip()
        try:
            device_page_size = int(page_size_text)
        except ValueError:
            device_page_size = 0
            errors.append(f"device page size is not numeric: {page_size_text!r}")
        environment["page_size"] = device_page_size
        environment["page_size_command"] = "getconf PAGE_SIZE"
        write_json(output / "environment.json", environment)
        write_json(output / "install.json", install_rd_apks(serial))
        direct_rows["direct64"] = start_direct(serial, FIXTURE64, output, "direct64")
        direct_rows["direct32"] = start_direct(serial, FIXTURE32, output, "direct32")
        sandbox_row = start_sandbox(serial, output)
        companion = run_companion_probe(args.instance, output)
        write_json(output / "companion.json", companion)
    except CampaignBlocked as exc:
        errors.append(str(exc))
        write_json(output / "blocked.json", {"code": exc.code, "detail": exc.detail})
    except Exception as exc:
        errors.append(f"campaign exception: {exc}")

    page_size = int(environment.get("page_size") or 0)
    expected_page = page_size
    probe_summaries = {
        "direct64": validate_probe(
            "direct64", (direct_rows.get("direct64") or {}).get("result"),
            "DIRECT_FIXTURE", "x86_64", expected_page, errors,
        ),
        "direct32": validate_probe(
            "direct32", (direct_rows.get("direct32") or {}).get("result"),
            "DIRECT_FIXTURE", "x86", expected_page, errors,
        ),
        "sandbox": validate_probe(
            "sandbox", sandbox_row.get("result"), "IN_SANDBOX", "x86_64", expected_page, errors,
        ),
    }
    static_ok = bool(static_rows) and all(row["returncode"] == 0 for row in static_rows)
    dynamic_ok = all(row.get("status") == "PASS" for row in probe_summaries.values())
    companion_ok = companion.get("status") == "PASS"
    if not companion_ok:
        errors.append(f"companion cross-ABI probe status={companion.get('status')}")
    environment_block = {
        "task_id": TASK_ID,
        "status": "PASS",
        "device_page_size": page_size,
        "page_16kb_dynamic_status": "PASS" if page_size == 16 * 1024 else "ENVIRONMENT_NOT_AVAILABLE",
        "arm32_dynamic_status": "UNVERIFIED_RUNTIME",
        "arm64_dynamic_status": "UNVERIFIED_RUNTIME",
        "static_16kb_alignment_status": "PASS" if static_ok else "UNVERIFIED",
        "reason": (
            "RD测试 reports a non-16KB page size; no approved ARM32/ARM64 or 16KB runtime "
            "is available in this environment. Static alignment is not promoted to dynamic PASS."
        ),
        "non_claims": ["ARM32 dynamic PASS", "ARM64 dynamic PASS", "16KB runtime dynamic PASS"],
    }
    write_json(verification / "c3-t03-environment-block.json", environment_block)
    build_ok = bool(build.get("apks")) and all(row.get("exists") for row in build["apks"])
    rd_ok = static_ok and dynamic_ok and companion_ok and not errors
    failures = [
        {"id": "C3-T03-ACCEPTANCE", "classification": "FAIL", "summary": error}
        for error in errors
    ]
    if page_size != 16 * 1024:
        failures.append({
            "id": "KI-R03-043",
            "classification": "ENVIRONMENT_BLOCKED",
            "summary": f"RD page size is {page_size}; 16KB/ARM dynamic runtime is not available",
            "known_issue_id": "KI-R03-043",
        })
    evidence = {
        "schema_version": 1,
        "campaign_id": TASK_ID,
        "capability": "four ABI ELF/page alignment, Companion32 and native Camera/Media",
        "branch": identity.get("branch") or "unknown",
        "commit": identity.get("commit") or "unknown",
        "tree": identity.get("tree") or "unknown",
        "timestamp": now_iso(),
        "host_os": host_os(),
        "android_environment": json.dumps(environment, ensure_ascii=False, sort_keys=True),
        "device_name": str(environment.get("device_name") or ""),
        "adb_serial": str(environment.get("adb_serial") or ""),
        "api_level": environment.get("api_level"),
        "abi": str(environment.get("abi") or ""),
        "boot_id": str(environment.get("boot_id") or ""),
        "android_id": str(environment.get("android_id") or ""),
        "instance_name": str(environment.get("instance_name") or args.instance),
        "build_result": "PASS" if build_ok else "FAIL",
        "static_result": "PASS" if static_ok else "FAIL",
        "targeted_result": "PASS" if dynamic_ok and companion_ok else "FAIL",
        "rd_result": "PASS" if rd_ok else "FAIL",
        "regression_result": "UNVERIFIED",
        "failures": failures,
        "known_issues": [
            "KI-R03-043 ARM and 16KB dynamic environments unavailable",
            "KI-R03-NATIVE-001 raw syscall/SVC remains outside PLT mediation",
        ],
        "evidence_files": [],
        "maturity_level": "RD_BASELINE",
        "rd_api32_only": True,
        "va_pro_equivalent": "NOT_PROVEN",
        "notes": (
            "Four-ABI ELF static alignment and RD API32 x86/x86_64 native media paths are "
            "reported separately. RD page size is recorded explicitly; ARM and 16KB dynamic "
            "claims remain unverified/environment-blocked."
        ),
    }
    schema_errors = validate_evidence(evidence)
    errors.extend(schema_errors)
    evidence["failures"].extend(
        {"id": "C3-T03-EVIDENCE-SCHEMA", "classification": "FAIL", "summary": error}
        for error in schema_errors
    )
    evidence_path = output / "evidence.json"
    report_path = output / "report.md"
    evidence["evidence_files"] = [
        str(output / "environment.json"),
        str(output / "build-metadata.json"),
        str(output / "static-gates.json"),
        str(output / "direct64.json"),
        str(output / "direct32.json"),
        str(output / "sandbox.json"),
        str(output / "companion.json"),
        str(verification / "c3-t03-abi-report.json"),
        str(verification / "c3-t03-environment-block.json"),
        str(evidence_path),
        str(report_path),
        str(verification / "c3-t03-rd-summary.json"),
        str(verification / "c3-t03-local-verification.json"),
    ]
    write_json(evidence_path, evidence)
    write_json(verification / "c3-t03-rd-summary.json", evidence)
    local = {
        "task_id": TASK_ID,
        "status": "PASS" if not errors else "FAIL",
        "source_commit_before_implementation_commit": identity.get("commit"),
        "source_worktree_status_before_implementation_commit": identity.get("status"),
        "started_at": evidence["timestamp"],
        "finished_at": now_iso(),
        "static_gates": static_rows,
        "build": build,
        "environment": environment,
        "direct": direct_rows,
        "probes": probe_summaries,
        "sandbox": sandbox_row,
        "companion": companion,
        "environment_block": environment_block,
        "errors": errors,
        "non_claims": environment_block["non_claims"],
    }
    write_json(verification / "c3-t03-local-verification.json", local)
    report_lines = [
        "# C3-T03 四 ABI、16KB page 与 native Camera/Media RD Evidence",
        "",
        f"- status: `{evidence['rd_result']}`",
        f"- instance: `{evidence['instance_name']}`",
        f"- resolved serial: `{evidence['adb_serial']}`",
        f"- device: `{evidence['device_name']}` API `{evidence['api_level']}`",
        f"- ABI list: `{evidence['abi']}`",
        f"- page size: `{page_size}` (`{environment_block['page_16kb_dynamic_status']}` for 16KB dynamic)",
        f"- Companion32: `{companion.get('status')}` ({companion.get('nativeStatus', '')})",
        f"- VA Pro equivalent: `{evidence['va_pro_equivalent']}`",
        "",
        "## Native media statuses",
        "",
    ]
    for label, summary in probe_summaries.items():
        report_lines.append(f"- {label}: {summary.get('status')} ABI={summary.get('compiledAbi')} checks={summary.get('checks')}")
    report_lines.extend([
        "",
        "## Boundary",
        "",
        "Static 16KB PT_LOAD alignment is a packaging/linker property. The RD device reported its actual page size; "
        "ARM32, ARM64 and 16KB dynamic PASS are not claimed.",
        "",
    ])
    report_path.write_text("\n".join(report_lines), encoding="utf-8")
    print(json.dumps({
        "status": evidence["rd_result"],
        "output": str(output),
        "verification": str(verification / "c3-t03-rd-summary.json"),
        "serial": evidence["adb_serial"],
        "page_size": page_size,
        "errors": errors,
    }, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
