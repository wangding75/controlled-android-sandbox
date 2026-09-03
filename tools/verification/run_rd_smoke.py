"""Run the real platform S01-S10 smoke suite and persist evidence.

Usage from the repository root::

    python tools/verification/run_rd_smoke.py --instance-name RD测试

For an explicitly verified API33/API34 AVD, use the matching ``--api33`` or
``--api34`` flag with an explicit serial.

The default run performs the required Gradle acceptance commands first.  Use
``--skip-build`` only when those commands were already run and their results
are supplied separately in the task report.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    _ROOT = Path(__file__).resolve().parents[2]
    if str(_ROOT) not in sys.path:
        sys.path.insert(0, str(_ROOT))

from tools.verification.capabilities.smoke import (  # noqa: E402
    GUEST_PACKAGE,
    SmokeContext,
    smoke_executor,
    smoke_specs,
)
from tools.verification.core.models import AttemptResult, ResultState, Testcase  # noqa: E402
from tools.verification.core.runner import run_case  # noqa: E402
from tools.verification.core.policy import RetryPolicy  # noqa: E402
from tools.verification.device.adb import AdbDevice  # noqa: E402
from tools.verification.device.metadata import (  # noqa: E402
    DeviceMetadataError,
    collect_device_metadata,
    resolve_rd_device,
)
from tools.verification.reporting.summary import (  # noqa: E402
    build_summary,
    render_compact_report,
    write_json,
)


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = ROOT / "out" / "verification"


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def _run_gradle(step: str, arguments: list[str], run_dir: Path, timeout_sec: float) -> dict[str, Any]:
    command = [str(ROOT / "gradlew.bat"), *arguments]
    started = time.monotonic()
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
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )
        timed_out = False
        stdout = completed.stdout
        stderr = completed.stderr
        returncode = completed.returncode
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""
        returncode = 124
    log_path = run_dir / "build" / f"{step}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text(
        f"COMMAND: {' '.join(command)}\nRETURN_CODE: {returncode}\n"
        f"TIMED_OUT: {timed_out}\n\n{stdout}\n{stderr}",
        encoding="utf-8",
    )
    return {
        "step": step,
        "command": command,
        "returncode": returncode,
        "timed_out": timed_out,
        "status": "PASS" if returncode == 0 and not timed_out else "FAIL",
        "duration_ms": max(0, int(round((time.monotonic() - started) * 1000))),
        "log": str(log_path.relative_to(ROOT).as_posix()),
    }


def _find_apk(module: str, preferred: str) -> Path | None:
    output = ROOT / module / "build" / "outputs" / "apk" / "debug"
    exact = output / preferred
    if exact.is_file():
        return exact
    candidates = sorted(
        item for item in output.glob("*.apk") if "androidTest" not in item.name
    )
    return candidates[0] if len(candidates) == 1 else None


def _apk_paths(*, include_companion32: bool = True) -> dict[str, Path]:
    requested = {
        "host": ("app", "app-debug.apk"),
        "companion32": ("sandbox-companion32", "sandbox-companion32-debug.apk"),
        "fixture": ("fixture-basic", "fixture-basic-debug.apk"),
        "fixture32": ("fixture-compat32", "fixture-compat32-debug.apk"),
    }
    if not include_companion32:
        requested.pop("companion32")
    paths: dict[str, Path] = {}
    for name, (module, preferred) in requested.items():
        found = _find_apk(module, preferred)
        if found is None:
            raise FileNotFoundError(
                f"debug APK not found for {name}: {ROOT / module / 'build' / 'outputs' / 'apk' / 'debug'}"
            )
        paths[name] = found
    return paths


def _resolve_device(args: argparse.Namespace) -> tuple[dict[str, Any], AdbDevice]:
    if args.serial:
        serial = args.serial.strip()
        if not serial:
            raise DeviceMetadataError("EXPLICIT_SERIAL_EMPTY")
        return {
            "mode": "explicit-serial",
            "serial": serial,
            "instance_name": args.instance_name,
        }, AdbDevice(serial, root=ROOT)
    return resolve_rd_device(args.instance_name, root=ROOT)


def _validate_api_device(metadata: dict[str, Any], expected_api: int) -> None:
    mismatches: list[str] = []
    if metadata.get("api_level") != expected_api:
        mismatches.append(f"api_level={metadata.get('api_level')!r}")
    if metadata.get("abi") != "x86_64":
        mismatches.append(f"abi={metadata.get('abi')!r}")
    if "x86_64" not in (metadata.get("abi_list") or []):
        mismatches.append(f"abi_list={metadata.get('abi_list')!r}")
    if metadata.get("page_size") != 4096:
        mismatches.append(f"page_size={metadata.get('page_size')!r}")
    if mismatches:
        raise DeviceMetadataError(
            f"API{expected_api}_DEVICE_CONTRACT_MISMATCH: " + ", ".join(mismatches)
        )


def _validate_api33_device(metadata: dict[str, Any]) -> None:
    _validate_api_device(metadata, 33)


def _validate_api34_device(metadata: dict[str, Any]) -> None:
    _validate_api_device(metadata, 34)


def _case_device(metadata: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "instance_name", "serial", "manufacturer", "model", "api_level",
        "android_version", "abi", "abi_list", "page_size", "fingerprint",
        "build_fingerprint", "cas_commit",
    )
    return {key: metadata.get(key) for key in keys}


def _blocked_cases(
    specs: list[Any],
    output_dir: Path,
    metadata: dict[str, Any],
    reason: str,
) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for spec in specs:
        case = Testcase(
            spec=spec,
            device=_case_device(metadata),
            api_level=metadata.get("api_level"),
            abi=str(metadata.get("abi") or ""),
            page_size=metadata.get("page_size"),
        )
        case.add_attempt(
            AttemptResult(
                attempt=1,
                result=ResultState.BLOCKED_ENV,
                actual={"blocked_reason": reason},
                artifacts=[],
            )
        )
        payload = case.to_dict()
        write_json(output_dir / "cases" / spec.testcase_id / "testcase.json", payload)
        cases.append(payload)
    return cases


def _hygiene_snapshot() -> dict[str, str]:
    status = _git("status", "--short")
    diff_stat = _git("diff", "--stat")
    tracked_out = _git("ls-files", "out")
    return {
        "status": "CLEAN" if not status else status,
        "diff_stat": diff_stat or "(clean)",
        "tracked_out": tracked_out or "(none)",
    }


def _render_mode(args: argparse.Namespace) -> int:
    run_path = Path(args.render_report).resolve()
    run = json.loads(run_path.read_text(encoding="utf-8"))
    if args.final_head:
        run["final_head"] = args.final_head
    if args.hygiene_status is not None:
        run.setdefault("git_hygiene", {})["status"] = args.hygiene_status
    report_path = Path(args.report_path).resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_compact_report(run), encoding="utf-8")
    return 0


def _finalize_mode(args: argparse.Namespace) -> int:
    """Refresh ignored run metadata after the source commit is created."""

    run_path = Path(args.finalize_run).resolve()
    run = json.loads(run_path.read_text(encoding="utf-8"))
    run["final_head"] = args.final_head or _git("rev-parse", "HEAD")
    run["git_hygiene"] = _hygiene_snapshot()
    write_json(run_path, run)
    write_json(run_path.parent / "summary.json", run["summary"])
    (run_path.parent / "summary.md").write_text(render_compact_report(run), encoding="utf-8")
    if args.report_path:
        report_path = Path(args.report_path).resolve()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(render_compact_report(run), encoding="utf-8")
    return 0


def run(args: argparse.Namespace) -> tuple[int, Path, dict[str, Any]]:
    start_head = _git("rev-parse", "HEAD")
    run_id = args.run_id or dt.datetime.now().strftime("%Y%m%dT%H%M%SZ")
    output_root = Path(args.output_root).resolve()
    run_dir = output_root / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    all_specs = smoke_specs()
    if args.only_case:
        requested = set(args.only_case)
        unknown = requested - {spec.testcase_id for spec in all_specs}
        if unknown:
            raise RuntimeError(f"unknown smoke testcase(s): {sorted(unknown)}")
        specs = [spec for spec in all_specs if spec.testcase_id in requested]
    else:
        specs = all_specs
    run_payload: dict[str, Any] = {
        "run_id": run_id,
        "start_head": start_head,
        "final_head": start_head,
        "branch": _git("branch", "--show-current"),
        "selected_testcases": [spec.testcase_id for spec in specs],
        "started_at": _now(),
        "evidence_root": run_dir.relative_to(ROOT).as_posix()
        if run_dir.is_relative_to(ROOT)
        else str(run_dir),
        "limitations": [
            "The runner executes the shared S01-S10 contract against a resolved or explicitly supplied device; API33 selection is validated from system properties.",
            "The harness records real readiness and screen evidence; it never promotes an accepted/pending launch or a black frame to PASS.",
        ],
    }

    build_steps: dict[str, Any] = {}
    if args.skip_build:
        supplied = {
            "projects": args.gradle_projects_status,
            "assembleDebug": args.assemble_debug_status,
            "unit_tests": args.unit_tests_status,
        }
        for step in ("projects", "assembleDebug", "unit_tests"):
            build_steps[step] = {
                "status": supplied[step] or "NOT_RUN",
                "reason": "--skip-build",
            }
    else:
        build_steps["projects"] = _run_gradle(
            "projects", ["projects"], run_dir, args.build_timeout
        )
        build_steps["assembleDebug"] = _run_gradle(
            "assembleDebug", ["assembleDebug"], run_dir, args.build_timeout
        )
        build_steps["unit_tests"] = _run_gradle(
            "unit_tests", ["test"], run_dir, args.build_timeout
        )
    run_payload["build"] = build_steps
    run_payload["gradle_projects"] = build_steps["projects"]["status"]
    run_payload["assemble_debug"] = build_steps["assembleDebug"]["status"]
    run_payload["unit_tests"] = build_steps["unit_tests"]["status"]

    metadata: dict[str, Any] = {"instance_name": args.instance_name}
    cases: list[dict[str, Any]] = []
    device: AdbDevice | None = None
    context: SmokeContext | None = None
    build_failed = any(
        build_steps[name].get("status") == "FAIL"
        for name in ("projects", "assembleDebug", "unit_tests")
    )
    if build_failed:
        reason = "Gradle acceptance step failed; smoke execution is blocked rather than inferred."
        cases = _blocked_cases(specs, run_dir, metadata, reason)
    else:
        try:
            resolver_snapshot, device = _resolve_device(args)
            platform_lane = ""
            if args.api33 and args.api34:
                raise DeviceMetadataError("API33_AND_API34_FLAGS_ARE_MUTUALLY_EXCLUSIVE")
            if args.api33:
                platform_lane = "API33"
            elif args.api34:
                platform_lane = "API34"
            apk_paths = _apk_paths(include_companion32=not platform_lane)
            metadata = collect_device_metadata(
                device,
                instance_name=args.instance_name,
                resolver_snapshot=resolver_snapshot,
                cas_commit=start_head,
                apk_paths=apk_paths.values(),
            )
            write_json(run_dir / "device-metadata.json", metadata)
            if not metadata.get("metadata_complete"):
                cases = _blocked_cases(
                    specs,
                    run_dir,
                    metadata,
                    f"device metadata incomplete: {metadata.get('missing_fields')}",
                )
            else:
                if platform_lane == "API33":
                    _validate_api_device(metadata, 33)
                elif platform_lane == "API34":
                    _validate_api_device(metadata, 34)
                context = SmokeContext(
                    root=ROOT,
                    device=device,
                    metadata=metadata,
                    apk_paths=apk_paths,
                    setup_omissions=(
                        {
                            "companion32": (
                                f"UNSUPPORTED_PLATFORM: {platform_lane} x86_64 AVD has no 32-bit ABI; "
                                "32-bit compatibility/cross-bitness coverage is deferred to C6-T02"
                            )
                        }
                        if platform_lane
                        else {}
                    ),
                )
                retry_policy = RetryPolicy(
                    max_diagnostic_retries=0 if args.no_diagnostic_retry else 1
                )
                case_device = _case_device(metadata)
                for spec in specs:
                    case = run_case(
                        spec,
                        context=context,
                        output_dir=run_dir,
                        device=case_device,
                        retry_policy=retry_policy,
                        executor=smoke_executor(spec),
                    )
                    cases.append(case.to_dict())
        except (DeviceMetadataError, FileNotFoundError, OSError, RuntimeError) as error:
            reason = f"{error.__class__.__name__}: {error}"
            write_json(run_dir / "environment-blocked.json", {"reason": reason})
            cases = _blocked_cases(specs, run_dir, metadata, reason)
        finally:
            if device is not None:
                try:
                    device.force_stop("com.warden.controlledsandbox.debug")
                    device.force_stop(GUEST_PACKAGE)
                except Exception:
                    pass

    run_payload["device_metadata"] = metadata
    run_payload["testcases"] = cases
    run_payload["summary"] = build_summary(run_payload, cases)
    run_payload["harness_tests"] = args.harness_status
    run_payload["git_hygiene"] = _hygiene_snapshot()
    run_payload["finished_at"] = _now()
    write_json(run_dir / "run.json", run_payload)
    write_json(run_dir / "summary.json", run_payload["summary"])
    (run_dir / "summary.md").write_text(render_compact_report(run_payload), encoding="utf-8")
    overall = run_payload["summary"]["overall"]
    exit_code = 0 if overall in {"PASS", "PASS_WITH_DISCOVERED_PRODUCT_DEFECT"} else 2
    return exit_code, run_dir, run_payload


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default="RD测试")
    parser.add_argument("--serial", default="", help="Use this ADB serial instead of the RD resolver")
    parser.add_argument("--api33", action="store_true", help="Require API 33 x86_64/4096 device contract")
    parser.add_argument("--api34", action="store_true", help="Require API 34 x86_64/4096 device contract")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--no-diagnostic-retry", action="store_true")
    parser.add_argument(
        "--only-case",
        action="append",
        default=[],
        help="Run one or more named smoke cases (repeatable) for targeted comparison",
    )
    parser.add_argument("--build-timeout", type=float, default=900.0)
    parser.add_argument("--harness-status", default="NOT_RECORDED")
    parser.add_argument("--gradle-projects-status", default="")
    parser.add_argument("--assemble-debug-status", default="")
    parser.add_argument("--unit-tests-status", default="")
    parser.add_argument("--render-report", default="")
    parser.add_argument("--finalize-run", default="")
    parser.add_argument("--report-path", default="")
    parser.add_argument("--final-head", default="")
    parser.add_argument("--hygiene-status", default=None)
    return parser


def main() -> int:
    args = _parser().parse_args()
    if args.render_report:
        if not args.report_path:
            raise SystemExit("--report-path is required with --render-report")
        return _render_mode(args)
    if args.finalize_run:
        return _finalize_mode(args)
    code, run_dir, payload = run(args)
    print(json.dumps({
        "run_id": payload["run_id"],
        "run_dir": str(run_dir),
        "overall": payload["summary"]["overall"],
        "smoke_total": payload["summary"]["total"],
        "smoke_pass": payload["summary"]["pass"],
        "smoke_fail": payload["summary"]["fail"],
        "exit_code": code,
    }, ensure_ascii=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
