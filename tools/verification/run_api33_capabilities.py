"""Run the API33/API34/API35/API36/API37 extension capability suite and persist compact local evidence.

The suite deliberately reuses the same DebugCommandActivity surface and fixture
components as the S01-S10 contract.  It records complete device evidence under
``out/verification`` while keeping the committed matrix small and fail-closed.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    _ROOT = Path(__file__).resolve().parents[2]
    if str(_ROOT) not in sys.path:
        sys.path.insert(0, str(_ROOT))

from tools.verification.capabilities.smoke import (  # noqa: E402
    FRAMEWORK_PROBE_COMPONENT,
    GUEST_PACKAGE,
    SmokeContext,
    _capture,
    _invoke_debug,
    _wait_for_markers,
)
from tools.verification.core.policy import TimeoutKind  # noqa: E402
from tools.verification.device.adb import AdbDevice  # noqa: E402
from tools.verification.device.metadata import (  # noqa: E402
    DeviceMetadataError,
    collect_device_metadata,
)
from tools.verification.run_rd_smoke import (  # noqa: E402
    _apk_paths,
    _git,
    _wait_for_android_services,
    _validate_api_device,
)


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = ROOT / "out" / "verification"
FIXTURE32 = "com.warden.controlledsandbox.fixture32"
SPLIT_PACKAGE = "com.warden.controlledsandbox.fixture.split"
SPLIT_BASE_COMPONENT = "com.warden.controlledsandbox.fixture.split.SplitBaseActivity"
SPLIT_FEATURE_COMPONENT = "com.warden.controlledsandbox.fixture.split.feature.FeatureActivity"
SPLIT_BASE_APK = ROOT / "fixture-split-base" / "build" / "outputs" / "apk" / "debug" / "fixture-split-base-debug.apk"
SPLIT_FEATURE_APK = ROOT / "fixture-split-feature" / "build" / "outputs" / "apk" / "debug" / "fixtureSplitFeature-debug.apk"


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _install_required(device: AdbDevice, apk_paths: dict[str, Path]) -> list[dict[str, Any]]:
    installs: list[dict[str, Any]] = []
    # API33/API34/API35/API36/API37 x86_64 lanes intentionally omit the 32-bit Companion.  The
    # compat32 fixture includes an x86_64 variant only so package/PMS/cross-package
    # identity can still be tested; cross-bitness remains C6-T02 scope.
    for name in ("host", "fixture", "fixture32"):
        apk = apk_paths[name]
        result = device.install(apk, timeout_sec=120.0)
        row = {
            "name": name,
            "path": str(apk),
            "returncode": result.returncode,
            "ok": result.ok,
            "stderr": result.text("stderr")[-300:],
        }
        installs.append(row)
        if not result.ok:
            raise RuntimeError(f"APK_INSTALL_FAILED:{name}:{row}")
    # API37's PackageUpdateActivity can finish asynchronously after `adb install` has returned.
    # If the first explicit DebugCommandActivity launch races that finish, SystemUI resumes the
    # old no-extras intent and the command worker records `package extra is required`.  Fence the
    # package-update surface before dispatching the first capability command.
    deadline = time.monotonic() + 30.0
    quiet_since: float | None = None
    last_activity_dump = ""
    while time.monotonic() < deadline:
        activity_dump = device.shell(
            ["dumpsys", "activity", "activities"], timeout_sec=60.0
        )
        last_activity_dump = activity_dump.text()
        package_update_active = "PackageUpdateActivity" in last_activity_dump
        if activity_dump.ok and not package_update_active:
            quiet_since = quiet_since or time.monotonic()
            if time.monotonic() - quiet_since >= 1.0:
                return installs
        else:
            quiet_since = None
        time.sleep(0.2)
    raise RuntimeError(
        "PACKAGE_UPDATE_ACTIVITY_NOT_IDLE: "
        + last_activity_dump[-1200:].replace("\n", " ")
    )
    return installs


def _launch_component(
    context: SmokeContext,
    case_dir: Path,
    component: str,
    extras: dict[str, Any] | None = None,
    package: str = GUEST_PACKAGE,
) -> tuple[dict[str, Any], str, list[str]]:
    observation = _invoke_debug(
        context,
        case_dir,
        command="launch-component",
        package=package,
        timeout_kind=TimeoutKind.RECOVERY,
        extras={"component": component, **(extras or {})},
        force_stop_host=True,
    )
    result = observation.actual["debug_result"]
    operation = result.get("operation") or {}
    if result.get("status") != "PASS" or operation.get("status") != "LAUNCH_PASS":
        raise RuntimeError(
            "LAUNCH_COMPONENT_NOT_PASS:" + json.dumps(
                {"status": result.get("status"), "operation": operation},
                ensure_ascii=False,
            )
        )
    return result, observation.logcat, list(observation.artifacts)


def _wait_for_all(device: AdbDevice, markers: tuple[str, ...], timeout: float) -> str:
    return _wait_for_markers(device, markers, timeout)


def _merge_case_logcat(*snapshots: str) -> str:
    """Keep both the marker-wait window and the post-marker crash window for one case."""

    return "\n".join(snapshot for snapshot in snapshots if snapshot)


def _check_case(
    context: SmokeContext,
    run_dir: Path,
    *,
    case_id: str,
    component: str | None,
    required: tuple[str, ...] = (),
    any_required: tuple[str, ...] = (),
    forbidden: tuple[str, ...] = (),
    extras: dict[str, Any] | None = None,
    wait_seconds: float = 60.0,
) -> dict[str, Any]:
    case_dir = run_dir / "cases" / case_id
    started = time.monotonic()
    artifacts: list[str] = []
    logcat = ""
    post_marker_logcat = ""
    result: dict[str, Any] = {}
    errors: list[str] = []
    try:
        if component is None:
            raise RuntimeError("COMPONENT_NOT_CONFIGURED")
        # Bound forbidden-marker checks to this case.  The device log buffer is shared across
        # launches, so reading it without a pre-launch clear can attribute an earlier guest
        # crash to a later, otherwise clean capability result.
        context.device.clear_logcat()
        result, _initial_log, artifacts = _launch_component(context, case_dir, component, extras)
        marker_logcat = _wait_for_all(context.device, required, wait_seconds)
        # A guest crash can happen after the last required marker.  Capture a second bounded
        # snapshot before teardown so forbidden-marker checks remain fail-closed and the evidence
        # explains the result instead of relying on the initial launch snapshot.
        post_marker_logcat = context.device.logcat(timeout_sec=60.0)
        logcat = _merge_case_logcat(marker_logcat, post_marker_logcat)
        missing = [marker for marker in required if marker not in logcat]
        if any_required and not any(marker in logcat for marker in any_required):
            missing.append("ANY_OF:" + "|".join(any_required))
        forbidden_seen = [marker for marker in forbidden if marker in logcat]
        errors.extend("MISSING:" + marker for marker in missing)
        errors.extend("FORBIDDEN:" + marker for marker in forbidden_seen)
        status = "PASS" if not errors else "FAIL"
    except Exception as error:  # preserve the result and continue with later capabilities
        status = "FAIL"
        errors.append(f"{error.__class__.__name__}:{error}")
        try:
            post_marker_logcat = context.device.logcat(timeout_sec=60.0)
            logcat = _merge_case_logcat(logcat, post_marker_logcat)
        except Exception as log_error:
            errors.append(f"LOGCAT_CAPTURE_FAILED:{log_error}")
    if post_marker_logcat:
        case_dir.mkdir(parents=True, exist_ok=True)
        post_marker_path = case_dir / "post-marker-logcat.txt"
        post_marker_path.write_text(post_marker_logcat, encoding="utf-8")
        artifacts.append(post_marker_path.relative_to(ROOT).as_posix())
    if not artifacts:
        try:
            captured = _capture(context, case_dir / "failure", case_id)
            artifacts = captured["artifacts"]
            if not logcat:
                logcat = captured["logcat"]
        except Exception as capture_error:
            errors.append(f"CAPTURE_FAILED:{capture_error}")
    evidence = {
        "case_id": case_id,
        "component": component,
        "status": status,
        "required_markers": list(required),
        "any_required_markers": list(any_required),
        "forbidden_markers": list(forbidden),
        "observed_markers": [marker for marker in (*required, *any_required) if marker in logcat],
        "errors": errors,
        "debug_status": result.get("status", ""),
        "operation_status": (result.get("operation") or {}).get("status", ""),
        "artifacts": artifacts,
        "duration_ms": max(0, int(round((time.monotonic() - started) * 1000))),
    }
    case_dir.mkdir(parents=True, exist_ok=True)
    (case_dir / "capability.json").write_text(
        json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return evidence


def _framework_case(context: SmokeContext, run_dir: Path) -> dict[str, Any]:
    required = (
        "FRAMEWORK_PROBE_PROVIDER_BULK_PASS",
        "FRAMEWORK_PROBE_PROVIDER_BATCH_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS",
        "FRAMEWORK_PROBE_JOB_READBACK_PASS",
        "FRAMEWORK_PROBE_ALARM_CLOCK_READBACK_PASS",
        "FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_REQUESTED",
        "FRAMEWORK_PROBE_RECEIVER_FRAMEWORK_PASS",
        "FRAMEWORK_PROBE_ORDERED_RECEIVER_DELIVERED",
        "FRAMEWORK_PROBE_ORDERED_RECEIVER_FRAMEWORK_PASS",
        "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_DELIVERED",
        "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FINISHED",
        "FRAMEWORK_PROBE_ORDERED_ASYNC_RECEIVER_FRAMEWORK_PASS",
        "GUEST_RECEIVER_FRAMEWORK_DELIVERED",
        "GUEST_RECEIVER_FRAMEWORK_REGISTERED",
        "GUEST_BROADCAST status=BROADCAST_DELIVERED",
        "FRAMEWORK_PROBE_DYNAMIC_RECEIVER_FRAMEWORK_PASS",
        "FRAMEWORK_PROBE_SERVICE_BIND_PASS",
        "FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS",
        "FRAMEWORK_PROBE_PACKAGE_IDENTITY_PASS",
        "FRAMEWORK_PROBE_COMPONENT_METADATA_PASS",
        "FRAMEWORK_PROBE_PACKAGE_CONTEXT_PASS",
        "FRAMEWORK_PROBE_CROSS_PROVIDER_PASS",
        "FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_DELIVERED",
        "FRAMEWORK_PROBE_CROSS_PROVIDER_OBSERVER_PASS",
        "FRAMEWORK_PROBE_ACTIVITY_CONTRACT_PASS",
        "GUEST_ACTIVITY_PERSISTABLE_CREATE",
        "FRAMEWORK_PROBE_CROSS_ACTIVITY_PASS",
        "FRAMEWORK_PROBE_CROSS_SERVICE_BIND_PASS",
        "FRAMEWORK_PROBE_CROSS_PENDING_INTENT_PASS",
        "FRAMEWORK_PROBE_REMOTE_ROUTE_REQUESTED",
        "FRAMEWORK_PROBE_REMOTE_STOP_PASS",
        "FRAMEWORK_PROBE_CROSS_STOP_PASS",
        "FRAMEWORK_PROBE_PASS",
        "VIRTUAL_PENDING_INTENT_DELIVERY status=BROADCAST_DELIVERED",
    )
    forbidden = (
        "FRAMEWORK_PROBE_TASK_REUSE_FAIL",
        "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_FAIL",
        "VIRTUAL_PENDING_INTENT_DELIVERY status=FAILED",
        "FATAL EXCEPTION",
        "ANR in",
        "NOTIFICATION_PERMISSION_DENIAL_BYPASSED",
        "VIRTUAL_PACKAGE_UNIVERSE_MISMATCH",
        "ATTRIBUTION_SOURCE_IDENTITY_MISMATCH",
    )

    case = _check_case(
        context,
        run_dir,
        case_id="CAP-FRAMEWORK-TRANSPORT-IDENTITY",
        component=FRAMEWORK_PROBE_COMPONENT,
        required=required,
        any_required=(
            "FRAMEWORK_PROBE_NOTIFICATION_PERMISSION_DENIED_EXPECTED",
            "FRAMEWORK_PROBE_NOTIFICATION_READBACK_PASS",
        ),
        forbidden=forbidden,
        wait_seconds=75.0,
    )
    return case


def _split_case(context: SmokeContext, run_dir: Path, expected_api: int) -> dict[str, Any]:
    """Exercise the existing installed split fixture through the virtual import path."""

    case_id = "CAP-SPLIT-APK-CLASSLOADER"
    case_dir = run_dir / "cases" / case_id
    started = time.monotonic()
    artifacts: list[str] = []
    errors: list[str] = []
    evidence: dict[str, Any] = {
        "case_id": case_id,
        "component": SPLIT_BASE_COMPONENT,
        "feature_component": SPLIT_FEATURE_COMPONENT,
        "status": "FAIL",
        "required_markers": [
            "CS_SPLIT_FIXTURE: BASE_CREATE featureClassLoaded=true",
            "CS_SPLIT_FIXTURE: FEATURE_CREATE classLoaded=true",
        ],
        "observed_markers": [],
        "errors": errors,
        "artifacts": artifacts,
    }
    try:
        if not SPLIT_BASE_APK.is_file() or not SPLIT_FEATURE_APK.is_file():
            raise FileNotFoundError(
                f"split fixture APKs are missing: {SPLIT_BASE_APK}, {SPLIT_FEATURE_APK}"
            )
        install = context.device.run(
            ["install-multiple", "-r", str(SPLIT_BASE_APK), str(SPLIT_FEATURE_APK)],
            timeout_sec=120.0,
        )
        if not install.ok:
            raise RuntimeError(
                f"SPLIT_APK_INSTALL_FAILED:returncode={install.returncode}:"
                f"{install.text('stderr')[-500:]}"
            )
        physical_paths = context.device.shell_text(["pm", "path", SPLIT_PACKAGE])
        split_paths = [line for line in physical_paths.splitlines() if line.strip()]
        if len(split_paths) < 2:
            raise RuntimeError(f"SPLIT_PHYSICAL_INSTALL_INCOMPLETE:{physical_paths!r}")

        imported = _invoke_debug(
            context,
            case_dir / "import",
            command="import-only",
            package=SPLIT_PACKAGE,
            timeout_kind=TimeoutKind.ADD_IMPORT,
            force_stop_host=True,
        )
        import_result = imported.actual["debug_result"]
        if import_result.get("status") != "PASS" or (
            import_result.get("operation") or {}
        ).get("status") != "IMPORTED":
            raise RuntimeError(f"SPLIT_IMPORT_NOT_PASS:{import_result}")

        base_result, base_logcat, base_artifacts = _launch_component(
            context, case_dir / "base", SPLIT_BASE_COMPONENT, package=SPLIT_PACKAGE
        )
        if "CS_SPLIT_FIXTURE: BASE_CREATE featureClassLoaded=true" not in base_logcat:
            raise RuntimeError("SPLIT_BASE_FEATURE_CLASS_NOT_LOADED")

        feature_result, feature_logcat, feature_artifacts = _launch_component(
            context, case_dir / "feature", SPLIT_FEATURE_COMPONENT, package=SPLIT_PACKAGE
        )
        if "CS_SPLIT_FIXTURE: FEATURE_CREATE classLoaded=true" not in feature_logcat:
            raise RuntimeError("SPLIT_FEATURE_ACTIVITY_NOT_CREATED")

        artifacts.extend(imported.artifacts)
        artifacts.extend(base_artifacts)
        artifacts.extend(feature_artifacts)
        evidence.update({
            "status": "PASS",
            "physical_split_paths": split_paths,
            "import_result": import_result,
            "base_result": base_result,
            "feature_result": feature_result,
            "observed_markers": [
                "CS_SPLIT_FIXTURE: BASE_CREATE featureClassLoaded=true",
                "CS_SPLIT_FIXTURE: FEATURE_CREATE classLoaded=true",
            ],
            "api": expected_api,
        })
    except Exception as error:
        errors.append(f"{error.__class__.__name__}:{error}")
        try:
            captured = _capture(context, case_dir / "failure", case_id)
            artifacts.extend(captured["artifacts"])
            evidence["failure_capture"] = captured
        except Exception as capture_error:
            errors.append(f"CAPTURE_FAILED:{capture_error}")
    evidence["artifacts"] = list(dict.fromkeys(artifacts))
    evidence["duration_ms"] = max(0, int(round((time.monotonic() - started) * 1000)))
    case_dir.mkdir(parents=True, exist_ok=True)
    (case_dir / "capability.json").write_text(
        json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return evidence


def _write_matrix(run_dir: Path, payload: dict[str, Any]) -> None:
    (run_dir / "capability-matrix.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def run(args: argparse.Namespace) -> tuple[int, Path, dict[str, Any]]:
    start_head = _git("rev-parse", "HEAD")
    run_id = args.run_id or dt.datetime.now().strftime("%Y%m%dT%H%M%SZ")
    run_dir = (Path(args.output_root).resolve() / run_id)
    run_dir.mkdir(parents=True, exist_ok=True)
    if sum(bool(value) for value in (args.api34, args.api35, args.api36, args.api37)) > 1:
        raise DeviceMetadataError("API34_API35_API36_API37_FLAGS_ARE_MUTUALLY_EXCLUSIVE")
    expected_api = 37 if args.api37 else 36 if args.api36 else 35 if args.api35 else 34 if args.api34 else 33
    device = AdbDevice(args.serial, root=ROOT)
    _wait_for_android_services(device)
    apk_paths = _apk_paths(include_companion32=False)
    metadata = collect_device_metadata(
        device,
        instance_name=args.instance_name,
        resolver_snapshot={"mode": "explicit-serial", "serial": args.serial},
        cas_commit=start_head,
        apk_paths=apk_paths.values(),
    )
    _validate_api_device(metadata, expected_api)
    (run_dir / "device-metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    installs = _install_required(device, apk_paths)
    context = SmokeContext(
        root=ROOT,
        device=device,
        metadata=metadata,
        apk_paths=apk_paths,
        setup_installs=installs,
        setup_omissions={
            "companion32": (
                f"UNSUPPORTED_PLATFORM: API{expected_api} x86_64 AVD has no 32-bit ABI; "
                "cross-bitness is deferred to C6-T02"
            )
        },
    )

    # Import both virtual packages up front.  The peer is required by the package-universe,
    # cross-provider, cross-service and cross-PendingIntent identity checks.
    setup: list[dict[str, Any]] = []
    for package in (GUEST_PACKAGE, FIXTURE32):
        observation = _invoke_debug(
            context,
            run_dir / "setup" / package.rsplit(".", 1)[-1],
            command="import-only",
            package=package,
            timeout_kind=TimeoutKind.ADD_IMPORT,
            force_stop_host=True,
        )
        result = observation.actual["debug_result"]
        setup.append({
            "package": package,
            "status": result.get("status"),
            "operation_status": (result.get("operation") or {}).get("status"),
            "artifacts": observation.artifacts,
        })
        if result.get("status") != "PASS" or (result.get("operation") or {}).get("status") != "IMPORTED":
            raise RuntimeError(f"IMPORT_FAILED:{package}:{result}")

    cases: list[dict[str, Any]] = []
    cases.append(
        _check_case(
            context,
            run_dir,
            case_id="CAP-PMS-PERMISSION-APPOPS-ATTRIBUTION",
            component="com.warden.controlledsandbox.fixture.PmsPermissionAttributionProbeActivity",
            required=("C2_T02_PROBE_PASS",),
            forbidden=(
                "C2_T02_PROBE_FAIL",
                "PMS_HOST_APPLICATION_VISIBLE",
                "APPOPS_HOST_PACKAGE_VISIBLE",
                "ATTRIBUTION_SOURCE_IDENTITY_MISMATCH",
                "CALLBACK_PACKAGE_IDENTITY_MISMATCH",
            ),
            wait_seconds=45.0,
        )
    )
    cases.append(_framework_case(context, run_dir))
    cases.append(
        _check_case(
            context,
            run_dir,
            case_id="CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS",
            component="com.warden.controlledsandbox.fixture.C2T05SchedulingInteractionActivity",
            extras={"c2t05Mode": "full", "c2t05Loops": 1},
            required=(
                "C2_T05_LOOP_PASS loop=1",
                "C2_T05_INTERACTION_PASS",
                "C2_T05_NOTIFICATION_PASS loop=1",
                "C2_T05_ALARM_PASS loop=1",
                "C2_T05_JOB_CALLBACK_PASS loop=1",
                "C2_T05_FGS_STOP_PASS loop=1",
                "C2_T05_WINDOW_TOKEN_PASS",
                "C2_T05_DISPLAY_CONTEXT_PASS",
                "C2_T05_IME_PASS",
                "C2_T05_CAMPAIGN_PASS loops=1",
            ),
            any_required=(
                "C2_T05_NOTIFICATION_PERMISSION_DENIED_EXPECTED",
                "C2_T05_NOTIFICATION_RETURN loop=1",
            ),
            forbidden=("C2_T05_CAMPAIGN_FAIL", "NOTIFICATION_PERMISSION_DENIAL_BYPASSED"),
            wait_seconds=75.0,
        )
    )
    cases.append(
        _check_case(
            context,
            run_dir,
            case_id="CAP-NETWORK-MEDIA-DNS-VPN",
            component="com.warden.controlledsandbox.fixture.C2T06DeviceNetworkMediaActivity",
            extras={"c2t06Mode": "full", "c2t06Loops": 1},
            required=("C2_T06_LOOP_PASS loop=1", "C2_T06_CAMPAIGN_PASS loops=1"),
            forbidden=("C2_T06_CAMPAIGN_FAIL", "FATAL EXCEPTION", "ANR in"),
            wait_seconds=75.0,
        )
    )
    cases.append(
        _check_case(
            context,
            run_dir,
            case_id="CAP-ENVIRONMENT-SHORTCUT-LAUNCHER",
            component="com.warden.controlledsandbox.fixture.C2T07ApplicationEnvironmentActivity",
            extras={"c2t07Mode": "full", "c2t07Loops": 1, "c2t07User": 0},
            required=(
                "C2_T07_LOOP_PASS loop=1",
                "C2_T07_SHORTCUT_RETURN loop=1",
                "C2_T07_HOST_IDENTITY_GUARDED status=PASS",
                "C2_T07_CAMPAIGN_PASS loops=1",
            ),
            forbidden=(
                "C2_T07_CAMPAIGN_FAIL",
                "Shortcut package name mismatch",
                "C2_T07_SHORTCUT_RETURN status=NOT_APPLICABLE",
            ),
            wait_seconds=75.0,
        )
    )
    cases.append(_split_case(context, run_dir, expected_api))
    # There is no AppWidget provider/dynamic host fixture in the current API suite.  Keep
    # this explicit rather than treating AppWidgetManager's static readback as full coverage.
    widget_case = {
        "case_id": "CAP-APPWIDGET-DYNAMIC",
        "component": None,
        "status": "SKIP",
        "reason": f"NOT_COVERED_BY_API{expected_api}_DYNAMIC_SUITE",
        "required_markers": [],
        "observed_markers": [],
        "errors": [],
        "artifacts": [],
        "duration_ms": 0,
    }
    (run_dir / "cases" / widget_case["case_id"] / "capability.json").parent.mkdir(
        parents=True, exist_ok=True
    )
    (run_dir / "cases" / widget_case["case_id"] / "capability.json").write_text(
        json.dumps(widget_case, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    cases.append(widget_case)

    # MainActivity is the existing WebView smoke and FixtureApplication emits the JNI load
    # marker before Activity creation.  This verifies initialization/class-loader/native load
    # without introducing an ABI/cross-bitness assertion into the API33/API34/API35 task.
    cases.append(
        _check_case(
        context,
        run_dir,
        case_id="CAP-WEBVIEW-CLASSLOADER-NATIVE",
        component="com.warden.controlledsandbox.fixture.MainActivity",
        required=("NATIVE_LOAD JNI_LOADED",),
        forbidden=("FATAL EXCEPTION", "ANR in", "JNI_UNAVAILABLE"),
        wait_seconds=30.0,
        )
    )

    summary = {
        "total": len(cases),
        "pass": sum(item["status"] == "PASS" for item in cases),
        "fail": sum(item["status"] == "FAIL" for item in cases),
        "skip": sum(item["status"] == "SKIP" for item in cases),
    }
    payload = {
        "run_id": run_id,
        "start_head": start_head,
        "final_head": start_head,
        "started_at": _now(),
        "device_metadata": metadata,
        "setup_installs": installs,
        "setup_imports": setup,
        "capabilities": cases,
        "summary": summary,
        "limitations": [
            f"API{expected_api} device contract is validated from system properties, not AVD name.",
            "AppWidget dynamic host/provider fixture is not present and is explicit SKIP.",
            f"32-bit Companion/cross-bitness is deferred to C6-T02; fixture32 x86_64 is used for identity routing on API{expected_api}.",
            f"API{expected_api} NOT_EXPORTED dynamic receiver is exercised by a same-Guest send; adb-shell external delivery is not treated as equivalent.",
        ],
        "evidence_root": run_dir.relative_to(ROOT).as_posix(),
        "finished_at": _now(),
    }
    _write_matrix(run_dir, payload)
    return (0 if summary["fail"] == 0 else 2), run_dir, payload


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--instance-name", default="C6_T01B_API33_GoogleApis_x86_64")
    parser.add_argument("--api34", action="store_true", help="Require API 34 x86_64/4096 device contract")
    parser.add_argument("--api35", action="store_true", help="Require API 35 x86_64/4096 device contract")
    parser.add_argument("--api36", action="store_true", help="Require API 36 x86_64/4096 device contract")
    parser.add_argument("--api37", action="store_true", help="Require API 37 x86_64/4096 device contract")
    parser.add_argument("--run-id", default="")
    parser.add_argument("--output-root", default=str(DEFAULT_OUTPUT_ROOT))
    return parser


def main() -> int:
    args = _parser().parse_args()
    try:
        code, run_dir, payload = run(args)
    except (DeviceMetadataError, FileNotFoundError, OSError, RuntimeError) as error:
        print(json.dumps({"status": "BLOCKED", "error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps({
        "run_id": payload["run_id"],
        "run_dir": str(run_dir),
        "total": payload["summary"]["total"],
        "pass": payload["summary"]["pass"],
        "fail": payload["summary"]["fail"],
        "skip": payload["summary"]["skip"],
        "exit_code": code,
    }, ensure_ascii=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
