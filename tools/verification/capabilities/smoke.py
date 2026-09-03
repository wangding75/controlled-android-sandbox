"""S01-S10 platform capability smoke cases.

The cases use the existing debug command surface and fixtures as black-box
test inputs.  They do not embed product implementation details beyond the
stable command contract and real readiness fields already exposed by CAS.
"""

from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from ..core.assertions import (
    VerificationFailure,
    classify_failure_text,
    require,
    require_command_pass,
    require_launch_readiness,
    require_marker,
)
from ..core.models import FailureClass, ResultState, TestcaseSpec
from ..core.policy import TimeoutKind, TimeoutPolicy
from ..core.runner import AttemptExecution
from ..device.adb import AdbCommandResult, AdbDevice
from ..device.screen import inspect_png


HOST_PACKAGE = "com.warden.controlledsandbox.debug"
HOST_MAIN_COMPONENT = f"{HOST_PACKAGE}/com.warden.controlledsandbox.MainActivity"
DEBUG_COMPONENT = f"{HOST_PACKAGE}/com.warden.controlledsandbox.DebugCommandActivity"
GUEST_PACKAGE = "com.warden.controlledsandbox.fixture"
PEER_GUEST_PACKAGE = "com.warden.controlledsandbox.fixture32"
GUEST_MAIN_COMPONENT = "com.warden.controlledsandbox.fixture.MainActivity"
FRAMEWORK_PROBE_COMPONENT = "com.warden.controlledsandbox.fixture.FrameworkProbeActivity"
GUEST_SERVICE_COMPONENT = "com.warden.controlledsandbox.fixture.FixtureService"
GUEST_PROVIDER_COMPONENT = "com.warden.controlledsandbox.fixture.FixtureProvider"


@dataclass
class SmokeContext:
    root: Path
    device: AdbDevice
    metadata: dict[str, Any]
    timeout_policy: TimeoutPolicy = field(default_factory=TimeoutPolicy)
    apk_paths: dict[str, Path] = field(default_factory=dict)
    setup_installs: list[dict[str, Any]] = field(default_factory=list)
    setup_omissions: dict[str, str] = field(default_factory=dict)


@dataclass
class CommandObservation:
    actual: dict[str, Any]
    artifacts: list[str]
    logcat: str
    screen: dict[str, Any]
    package_revision: str = ""


def smoke_specs() -> list[TestcaseSpec]:
    """The stable, capability-oriented smoke inventory."""

    return [
        TestcaseSpec(
            "S01-host-build-install-launch",
            "package",
            "Build outputs are installable and the Host launcher reaches a visible frame.",
            guest_package="",
            precondition="debug Host and smoke fixture APKs are built",
            operation="install platform-supported Host/fixture APKs; launch Host MainActivity",
            expected={"host_install": True, "host_visible_frame": True},
            timeout_kind=TimeoutKind.INSTALL.value,
        ),
        TestcaseSpec(
            "S02-guest-import-add",
            "package",
            "Import the existing fixture APK and add its virtual user-0 instance.",
            guest_package=GUEST_PACKAGE,
            precondition="Host and fixture APKs are installed on the resolved device",
            operation="DebugCommandActivity import-only",
            expected={"status": "PASS", "operation_status": "IMPORTED"},
            timeout_kind=TimeoutKind.ADD_IMPORT.value,
        ),
        TestcaseSpec(
            "S03-cold-launch-first-frame",
            "activity",
            "Cold launch the Guest and require the real Activity/window/first-frame readiness gate.",
            guest_package=GUEST_PACKAGE,
            precondition="Guest instance exists for virtual user 0",
            operation="force-stop Host; DebugCommandActivity launch",
            expected={"status": "PASS", "operation_status": "LAUNCH_PASS", "first_frame": True},
            timeout_kind=TimeoutKind.COLD_LAUNCH.value,
        ),
        TestcaseSpec(
            "S04-warm-launch-reuse",
            "activity",
            "Warm launch the Guest and verify Activity reuse plus a visible first frame.",
            guest_package=GUEST_PACKAGE,
            precondition="S03 leaves a live Guest Activity session",
            operation="DebugCommandActivity launch without stopping Host",
            expected={"status": "PASS", "operation_status": "LAUNCH_PASS", "activity_reuse": True},
            timeout_kind=TimeoutKind.WARM_LAUNCH.value,
        ),
        TestcaseSpec(
            "S05-service-lifecycle",
            "service",
            "Exercise Guest Service start, bind, foreground promotion/demotion and stop lifecycle.",
            guest_package=GUEST_PACKAGE,
            precondition="Guest instance exists",
            operation="DebugCommandActivity service-lifecycle-suite iterations=1",
            expected={"status": "PASS", "operation_status": "SERVICE_LIFECYCLE_PASS"},
            timeout_kind=TimeoutKind.RECOVERY.value,
        ),
        TestcaseSpec(
            "S06-broadcast-dispatch",
            "receiver",
            "Dispatch a Guest broadcast campaign and verify receiver delivery.",
            guest_package=GUEST_PACKAGE,
            precondition="Guest instance exists",
            operation="DebugCommandActivity broadcast-campaign iterations=1",
            expected={"status": "PASS", "operation_status": "BROADCAST_CAMPAIGN_LAUNCHED"},
            timeout_kind=TimeoutKind.RECOVERY.value,
        ),
        TestcaseSpec(
            "S07-provider-access",
            "provider",
            "Prepare and query the fixture ContentProvider through the virtual route.",
            guest_package=GUEST_PACKAGE,
            precondition="Guest instance exists",
            operation="DebugCommandActivity neighbor-provider",
            expected={"status": "PASS", "provider_status": "OK"},
            timeout_kind=TimeoutKind.RECOVERY.value,
        ),
        TestcaseSpec(
            "S08-pending-intent-path",
            "pending_intent",
            "Run the existing framework probe and require real PendingIntent delivery markers.",
            guest_package=GUEST_PACKAGE,
            precondition="basic and compat32 fixture instances exist",
            operation="import peer; launch FrameworkProbeActivity",
            expected={"status": "PASS", "pending_intent_markers": True},
            timeout_kind=TimeoutKind.FIRST_FRAME.value,
        ),
        TestcaseSpec(
            "S09-package-lifecycle",
            "package",
            "Verify add, launch, clear, re-launch and supported delete/re-add lifecycle operations.",
            guest_package=GUEST_PACKAGE,
            precondition="fixture APK is installed",
            operation="import -> launch -> clear -> launch -> delete -> import",
            expected={"all_steps": "PASS"},
            timeout_kind=TimeoutKind.ADD_IMPORT.value,
        ),
        TestcaseSpec(
            "S10-process-death-recovery",
            "process",
            "Kill the prepared Guest process and require recovery with a real first frame.",
            guest_package=GUEST_PACKAGE,
            precondition="Guest instance exists and a prepared process can be observed",
            operation="hold prepare -> kill Guest PID -> launch/recover",
            expected={"killed_guest_process": True, "recovery_first_frame": True},
            timeout_kind=TimeoutKind.PROCESS_DEATH.value,
        ),
    ]


def smoke_executor(spec: TestcaseSpec) -> Callable[[SmokeContext, Path, int], AttemptExecution]:
    executors = {
        "S01-host-build-install-launch": _s01,
        "S02-guest-import-add": _s02,
        "S03-cold-launch-first-frame": _s03,
        "S04-warm-launch-reuse": _s04,
        "S05-service-lifecycle": _s05,
        "S06-broadcast-dispatch": _s06,
        "S07-provider-access": _s07,
        "S08-pending-intent-path": _s08,
        "S09-package-lifecycle": _s09,
        "S10-process-death-recovery": _s10,
    }
    try:
        return executors[spec.testcase_id]
    except KeyError as exc:
        raise KeyError(f"unknown smoke testcase: {spec.testcase_id}") from exc


def _s01(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    installs: list[dict[str, Any]] = []
    for name, path in context.apk_paths.items():
        result = context.device.install(
            path, timeout_sec=context.timeout_policy.seconds(TimeoutKind.INSTALL)
        )
        row = _command_dict(result)
        row.update({"name": name, "path": str(path)})
        installs.append(row)
        require(result.ok, "APK_INSTALL_FAILED", f"{name}: {row}")
    context.setup_installs = installs
    host_teardown = _fence_host_before_start(context.device, force_stop=True)
    context.device.clear_logcat()
    started = context.device.start_activity(
        HOST_MAIN_COMPONENT,
        flags="0x10000000",
        timeout_sec=context.timeout_policy.seconds(TimeoutKind.COLD_LAUNCH),
    )
    evidence = _capture(context, attempt_dir, "host")
    require(started.ok, "HOST_LAUNCH_FAILED", _command_dict(started))
    require(
        evidence["screen"].get("non_black") is True,
        "BLACK_SCREEN_OR_SCREENSHOT_UNAVAILABLE",
        f"Host screenshot={evidence['screen']}",
    )
    require(
        HOST_PACKAGE in evidence["window_dump"],
        "HOST_WINDOW_NOT_VISIBLE",
        "Host package was not present in the current window dump",
    )
    actual = {
        "installs": installs,
        "omitted_apks": dict(context.setup_omissions),
        "host_start": _command_dict(started),
        "host_teardown": host_teardown,
        **evidence,
    }
    return AttemptExecution(ResultState.PASS, actual, package_revision=_hash_for(context, "host"),
                            artifacts=evidence["artifacts"])


def _s02(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="import-only",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.ADD_IMPORT,
        force_stop_host=True,
    )
    require_command_pass(
        observation.actual["debug_result"], "IMPORTED", artifacts=observation.artifacts
    )
    return AttemptExecution(
        ResultState.PASS,
        observation.actual,
        package_revision=_package_revision(observation.actual["debug_result"])
        or _hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s03(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="launch",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.COLD_LAUNCH,
        force_stop_host=True,
    )
    require_launch_readiness(
        observation.actual["debug_result"],
        observation.screen,
        observation.logcat,
        expected_component=GUEST_MAIN_COMPONENT,
        artifacts=observation.artifacts,
    )
    return AttemptExecution(
        ResultState.PASS,
        observation.actual,
        package_revision=_package_revision(observation.actual["debug_result"])
        or _hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s04(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="launch",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.WARM_LAUNCH,
        force_stop_host=False,
        # Keep the debug controller in its own task.  CLEAR_TASK plus the shared Host
        # affinity can otherwise select and clear the live Guest Stub task on API32.
        start_flags="0x18200000",
    )
    require_launch_readiness(
        observation.actual["debug_result"],
        observation.screen,
        observation.logcat,
        expected_component=GUEST_MAIN_COMPONENT,
        artifacts=observation.artifacts,
    )
    timeline = _launch_operation(observation.actual["debug_result"]).get("launchTimeline") or []
    reuse_seen = "NEW_INTENT" in str(timeline) or any(
        marker in observation.logcat
        for marker in ("GUEST_ACTIVITY_NEW_INTENT", "GUEST_LAUNCH_TASK_REUSE", "DELIVERED_NEW_INTENT")
    )
    require(
        reuse_seen,
        "ACTIVITY_REUSE_NOT_OBSERVED",
        "warm launch had no NEW_INTENT/task-reuse witness",
        artifacts=observation.artifacts,
    )
    return AttemptExecution(
        ResultState.PASS,
        {**observation.actual, "activity_reuse": reuse_seen},
        package_revision=_package_revision(observation.actual["debug_result"])
        or _hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s05(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="service-lifecycle-suite",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.RECOVERY,
        extras={
            "serviceComponent": GUEST_SERVICE_COMPONENT,
            "serviceProcess": GUEST_PACKAGE,
            "iterations": 1,
        },
        force_stop_host=True,
    )
    require_command_pass(
        observation.actual["debug_result"],
        "SERVICE_LIFECYCLE_PASS",
        artifacts=observation.artifacts,
    )
    cycles = observation.actual["debug_result"].get("serviceLifecycleCycles") or []
    require(
        bool(cycles),
        "SERVICE_LIFECYCLE_CYCLE_MISSING",
        "service command returned no cycle",
        artifacts=observation.artifacts,
    )
    return AttemptExecution(
        ResultState.PASS,
        observation.actual,
        package_revision=_hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s06(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="broadcast-campaign",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.RECOVERY,
        extras={"iterations": 1},
        force_stop_host=True,
    )
    require_command_pass(
        observation.actual["debug_result"],
        "BROADCAST_CAMPAIGN_LAUNCHED",
        artifacts=observation.artifacts,
    )
    require(
        "C1_T03_BROADCAST_PASS" in observation.logcat
        and "C1_T03_ORDERED_RESULT_PASS" in observation.logcat,
        "BROADCAST_DISPATCH_MARKER_MISSING",
        "broadcast command returned but the fixture's ordered/dynamic pass markers were absent",
        artifacts=observation.artifacts,
    )
    return AttemptExecution(
        ResultState.PASS,
        observation.actual,
        package_revision=_hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s07(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="neighbor-provider",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.RECOVERY,
        force_stop_host=True,
    )
    require_command_pass(
        observation.actual["debug_result"], artifacts=observation.artifacts
    )
    provider = observation.actual["debug_result"].get("providerQuery") or {}
    require(
        provider.get("status") in {"OK", "CURSOR_READY"},
        "PROVIDER_QUERY_NOT_OK",
        str(provider),
        artifacts=observation.artifacts,
    )
    require_marker(
        observation.logcat,
        "PROVIDER_QUERY",
        detail="provider query marker missing",
        artifacts=observation.artifacts,
    )
    return AttemptExecution(
        ResultState.PASS,
        observation.actual,
        package_revision=_hash_for(context, "fixture"),
        artifacts=observation.artifacts,
    )


def _s08(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    peer = _invoke_debug(
        context,
        attempt_dir / "peer-import",
        command="import-only",
        package=PEER_GUEST_PACKAGE,
        timeout_kind=TimeoutKind.ADD_IMPORT,
        force_stop_host=True,
    )
    require_command_pass(peer.actual["debug_result"], "IMPORTED", artifacts=peer.artifacts)
    observation = _invoke_debug(
        context,
        attempt_dir,
        command="launch-component",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.RECOVERY,
        extras={"component": FRAMEWORK_PROBE_COMPONENT},
        force_stop_host=True,
    )
    markers = (
        "FRAMEWORK_PROBE_PENDING_INTENT_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_BINDER_PASS",
        "FRAMEWORK_PROBE_PENDING_INTENT_CALLBACK_PASS",
        "FRAMEWORK_PROBE_CROSS_PENDING_INTENT_PASS",
    )
    logcat = _wait_for_markers(
        context.device, markers, context.timeout_policy.seconds(TimeoutKind.FIRST_FRAME)
    )
    debug_result = observation.actual["debug_result"]
    require_command_pass(debug_result, artifacts=observation.artifacts)
    operation = debug_result.get("operation") or {}
    require(
        operation.get("status") in {"LAUNCH_ACCEPTED", "LAUNCH_PASS"},
        "LAUNCH_OPERATION_NOT_ACCEPTED",
        f"launch-component operation={operation}",
        artifacts=observation.artifacts,
    )
    require(
        operation.get("componentClass") == FRAMEWORK_PROBE_COMPONENT,
        "LAUNCH_TARGET_MISMATCH",
        f"expected component={FRAMEWORK_PROBE_COMPONENT!r} operation={operation}",
        artifacts=observation.artifacts,
    )
    for marker in markers:
        require_marker(logcat, marker, artifacts=observation.artifacts)
    refreshed = _capture(context, attempt_dir / "post-markers", "framework-probe")
    actual = {
        "peer_import": peer.actual,
        "probe": observation.actual,
        "pending_intent_markers": list(markers),
        "post_marker_capture": refreshed,
    }
    artifacts = list(dict.fromkeys(peer.artifacts + observation.artifacts + refreshed["artifacts"]))
    return AttemptExecution(
        ResultState.PASS,
        actual,
        package_revision=_hash_for(context, "fixture"),
        artifacts=artifacts,
    )


def _s09(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    steps: list[dict[str, Any]] = []
    artifacts: list[str] = []

    def run_step(name: str, command: str, *, kind: TimeoutKind, force_stop: bool = True,
                 expected_operation: str = "", extras: dict[str, Any] | None = None) -> dict[str, Any]:
        observation = _invoke_debug(
            context,
            attempt_dir / name,
            command=command,
            package=GUEST_PACKAGE,
            timeout_kind=kind,
            extras=extras,
            force_stop_host=force_stop,
        )
        require_command_pass(
            observation.actual["debug_result"],
            expected_operation,
            artifacts=observation.artifacts,
        )
        steps.append({"name": name, "actual": observation.actual})
        artifacts.extend(observation.artifacts)
        return observation.actual

    run_step("add", "import-only", kind=TimeoutKind.ADD_IMPORT, expected_operation="IMPORTED")
    launch = run_step("launch", "launch", kind=TimeoutKind.COLD_LAUNCH)
    require_launch_readiness(
        launch["debug_result"],
        launch["screen"],
        launch["logcat"],
        expected_component=GUEST_MAIN_COMPONENT,
        artifacts=launch.get("artifacts", []),
    )
    run_step("clear", "clear", kind=TimeoutKind.RECOVERY, expected_operation="CLEARED")
    relaunch = run_step("relaunch", "launch", kind=TimeoutKind.COLD_LAUNCH)
    require_launch_readiness(
        relaunch["debug_result"],
        relaunch["screen"],
        relaunch["logcat"],
        expected_component=GUEST_MAIN_COMPONENT,
        artifacts=relaunch.get("artifacts", []),
    )
    run_step("delete", "delete", kind=TimeoutKind.RECOVERY, expected_operation="DELETED")
    run_step("re-add", "import-only", kind=TimeoutKind.ADD_IMPORT, expected_operation="IMPORTED")
    return AttemptExecution(
        ResultState.PASS,
        {"steps": steps, "step_count": len(steps)},
        package_revision=_hash_for(context, "fixture"),
        artifacts=list(dict.fromkeys(artifacts)),
    )


def _s10(context: SmokeContext, attempt_dir: Path, _attempt: int) -> AttemptExecution:
    device = context.device
    hold_dir = attempt_dir / "hold"
    hold_dir.mkdir(parents=True, exist_ok=True)
    host_teardown = _fence_host_before_start(device, force_stop=True)
    device.run_as_remove(HOST_PACKAGE, "files/debug-command-result.json")
    device.clear_logcat()
    request_id = _request_id("s10-hold")
    started = device.start_activity(
        DEBUG_COMPONENT,
        extras=_extras(
            "hold-prepare",
            GUEST_PACKAGE,
            0,
            request_id,
            {"trustNativeGuest": True, "holdMs": 30_000},
        ),
        timeout_sec=30.0,
    )
    require(started.ok, "HOLD_PREPARE_START_FAILED", _command_dict(started))
    pid, hold_generation, hold_session, hold_log = _wait_for_guest_prepared(
        device, GUEST_PACKAGE, context.timeout_policy.seconds(TimeoutKind.PROCESS_DEATH)
    )
    (hold_dir / "hold-logcat.txt").write_text(hold_log, encoding="utf-8")
    (hold_dir / "host-teardown.json").write_text(
        json.dumps(host_teardown, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    process_before = device.processes()
    (hold_dir / "process-before.txt").write_text(process_before, encoding="utf-8")
    direct_kill = device.kill_pid(pid)
    (hold_dir / "kill.json").write_text(
        json.dumps(_command_dict(direct_kill), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    fallback_force_stop: AdbCommandResult | None = None
    kill_mode = "direct_pid"
    if not direct_kill.ok:
        # Android's shell UID cannot normally signal an application UID.  A
        # Host force-stop is the platform-authorized equivalent and still
        # proves concrete process death because the observed Guest PID must
        # disappear before recovery is attempted.
        fallback_force_stop = device.force_stop(HOST_PACKAGE)
        (hold_dir / "force-stop-fallback.json").write_text(
            json.dumps(_command_dict(fallback_force_stop), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        require(
            fallback_force_stop.ok,
            "GUEST_PROCESS_KILL_FAILED",
            {"direct": _command_dict(direct_kill), "fallback": _command_dict(fallback_force_stop)},
            classification=FailureClass.ENVIRONMENT,
        )
        kill_mode = "host_force_stop_fallback"
    exited = device.wait_for_pid_exit(pid, context.timeout_policy.seconds(TimeoutKind.PROCESS_DEATH))
    process_after = device.processes()
    (hold_dir / "process-after.txt").write_text(process_after, encoding="utf-8")
    require(exited, "GUEST_PROCESS_DID_NOT_EXIT", f"pid={pid}")

    recovery = _invoke_debug(
        context,
        attempt_dir / "recovery",
        command="launch",
        package=GUEST_PACKAGE,
        timeout_kind=TimeoutKind.RECOVERY,
        force_stop_host=False,
    )
    require_launch_readiness(
        recovery.actual["debug_result"],
        recovery.screen,
        recovery.logcat,
        expected_component=GUEST_MAIN_COMPONENT,
        artifacts=recovery.artifacts,
    )
    recovery_generation = _generation(recovery.actual["debug_result"])
    recovery_pid = _pid(recovery.actual["debug_result"])
    recovery_session = _session(recovery.actual["debug_result"])
    require(
        recovery_pid is not None and recovery_pid > 0 and recovery_pid != pid,
        "RECOVERY_PROCESS_NOT_REPLACED",
        f"held_pid={pid} recovery_pid={recovery_pid}",
        artifacts=recovery.artifacts,
    )
    require(
        bool(recovery_session) and recovery_session != hold_session,
        "RECOVERY_SESSION_NOT_REPLACED",
        f"held_session={hold_session!r} recovery_session={recovery_session!r}",
        artifacts=recovery.artifacts,
    )
    hold_artifacts = [
        _relative(context.root, hold_dir / "hold-logcat.txt"),
        _relative(context.root, hold_dir / "host-teardown.json"),
        _relative(context.root, hold_dir / "process-before.txt"),
        _relative(context.root, hold_dir / "process-after.txt"),
        _relative(context.root, hold_dir / "kill.json"),
    ]
    fallback_path = hold_dir / "force-stop-fallback.json"
    if fallback_path.is_file():
        hold_artifacts.append(_relative(context.root, fallback_path))
    actual = {
        "hold_start": _command_dict(started),
        "guest_pid": pid,
        "kill_mode": kill_mode,
        "direct_kill": _command_dict(direct_kill),
        "fallback_force_stop": (
            _command_dict(fallback_force_stop) if fallback_force_stop is not None else None
        ),
        "hold_generation": hold_generation,
        "hold_session": hold_session,
        "pid_exited": exited,
        "recovery": recovery.actual,
        "recovery_pid": recovery_pid,
        "recovery_generation": recovery_generation,
        "recovery_session": recovery_session,
        "generation_advanced": (
            hold_generation is not None
            and recovery_generation is not None
            and recovery_generation > hold_generation
        ),
    }
    return AttemptExecution(
        ResultState.PASS,
        actual,
        package_revision=_hash_for(context, "fixture"),
        artifacts=list(dict.fromkeys(hold_artifacts + recovery.artifacts)),
    )


def _invoke_debug(
    context: SmokeContext,
    attempt_dir: Path,
    *,
    command: str,
    package: str,
    timeout_kind: TimeoutKind,
    extras: dict[str, Any] | None = None,
    force_stop_host: bool,
    start_flags: str = "0x10008000",
) -> CommandObservation:
    device = context.device
    attempt_dir.mkdir(parents=True, exist_ok=True)
    host_teardown = _fence_host_before_start(device, force_stop=force_stop_host)
    device.run_as_remove(HOST_PACKAGE, "files/debug-command-result.json")
    device.clear_logcat()
    request_id = _request_id(command)
    start = device.start_activity(
        DEBUG_COMPONENT,
        extras=_extras(command, package, 0, request_id, {"trustNativeGuest": True, **(extras or {})}),
        flags=start_flags,
        timeout_sec=30.0,
    )
    require(start.ok, "DEBUG_ACTIVITY_START_FAILED", _command_dict(start))
    deadline = time.monotonic() + context.timeout_policy.seconds(timeout_kind)
    debug_result: dict[str, Any] | None = None
    last_text = ""
    while time.monotonic() < deadline:
        if device.run_as_exists(HOST_PACKAGE, "files/debug-command-result.json"):
            text = device.run_as_read(HOST_PACKAGE, "files/debug-command-result.json").strip()
            last_text = text
            if text.startswith("{"):
                try:
                    candidate = json.loads(text)
                except json.JSONDecodeError:
                    candidate = None
                if isinstance(candidate, dict) and _matches_request(candidate, command, package, request_id):
                    debug_result = candidate
                    break
        time.sleep(0.2)
    if debug_result is None:
        timeout_artifacts: list[str] = []
        timeout_evidence: dict[str, Any] = {"logcat": ""}
        try:
            timeout_evidence = _capture(context, attempt_dir, f"{command}-timeout")
            timeout_artifacts = timeout_evidence["artifacts"]
        except Exception as capture_error:  # preserve the primary timeout diagnosis
            timeout_artifacts = []
            last_text = f"{last_text} capture={capture_error}"
        classification = _timeout_classification(
            str(timeout_evidence.get("logcat") or ""), package
        )
        error = VerificationFailure(
            "DEBUG_RESULT_TIMEOUT",
            f"command={command} package={package} request={request_id} last={last_text[:300]!r}",
            classification,
        )
        error.artifacts = timeout_artifacts
        raise error
    local_result = attempt_dir / "debug-command-result.json"
    local_result.write_text(json.dumps(debug_result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    teardown_path = attempt_dir / "host-teardown.json"
    teardown_path.write_text(
        json.dumps(host_teardown, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    evidence = _capture(context, attempt_dir, command)
    actual = {
        "request_id": request_id,
        "command_start": _command_dict(start),
        "host_teardown": host_teardown,
        "debug_result": debug_result,
        **evidence,
    }
    artifacts = list(dict.fromkeys(
        [_relative(context.root, local_result), _relative(context.root, teardown_path)]
        + evidence["artifacts"]
    ))
    return CommandObservation(
        actual=actual,
        artifacts=artifacts,
        logcat=evidence["logcat"],
        screen=evidence["screen"],
        package_revision=_package_revision(debug_result),
    )


def _fence_host_before_start(
    device: AdbDevice, *, force_stop: bool, timeout_sec: float = 15.0
) -> dict[str, Any]:
    """Fence Host process and ATMS/WM teardown before dispatching a new command."""
    stop_result: AdbCommandResult | None = None
    if force_stop:
        stop_result = device.force_stop(HOST_PACKAGE)
        require(
            stop_result.ok,
            "HOST_FORCE_STOP_FAILED",
            _command_dict(stop_result),
            classification=FailureClass.ENVIRONMENT,
        )
        stopped = device.wait_for_package_stopped(HOST_PACKAGE, timeout_sec)
        require(
            stopped,
            "HOST_PROCESS_STOP_TIMEOUT",
            f"package={HOST_PACKAGE}",
            classification=FailureClass.HARNESS_DEFECT,
        )
    teardown = _wait_for_host_activity_teardown(
        device, timeout_sec, include_guest_activities=force_stop
    )
    return {
        "force_stop": _command_dict(stop_result) if stop_result is not None else None,
        "process_stopped": force_stop,
        "activity_teardown": teardown,
    }


def _host_activity_teardown_state(
    device: AdbDevice, *, include_guest_activities: bool
) -> dict[str, Any]:
    activities = device.shell(["dumpsys", "activity", "activities"], timeout_sec=60.0)
    windows = device.shell(["dumpsys", "window", "windows"], timeout_sec=60.0)

    def activity_line(line: str) -> bool:
        return HOST_PACKAGE in line and (
            include_guest_activities or "DebugCommandActivity" in line
        ) and not line.lstrip().startswith("mLastPausedActivity:") and any(
            marker in line
            for marker in ("ActivityRecord{", "cmp=", "mActivityComponent=", "packageName=")
        )

    def window_line(line: str) -> bool:
        return HOST_PACKAGE in line and (
            include_guest_activities or "DebugCommandActivity" in line
        ) and ("Window{" in line or "package=" in line)

    activity_rows = [line.strip() for line in activities.text().splitlines() if activity_line(line)]
    window_rows = [line.strip() for line in windows.text().splitlines() if window_line(line)]
    # dumpsys activity can retain a terminal ActivityRecord briefly after AMS has removed its
    # window. Treat only an all-isExiting record set with no matching window as complete; live
    # records and any window still keep the teardown fence closed, and the raw rows remain in
    # evidence for auditability.
    exiting_records = [row for row in activity_rows if "ActivityRecord{" in row]
    ignored_exiting_activity_rows: list[str] = []
    if (not window_rows and exiting_records
            and all("isExiting" in row for row in exiting_records)):
        ignored_exiting_activity_rows = list(activity_rows)
        activity_rows = []
    return {
        "activity_present": bool(activity_rows),
        "window_present": bool(window_rows),
        "activity_rows": activity_rows[-8:],
        "window_rows": window_rows[-8:],
        "ignored_exiting_activity_rows": ignored_exiting_activity_rows[-8:],
        "activity_returncode": activities.returncode,
        "window_returncode": windows.returncode,
    }


def _wait_for_host_activity_teardown(
    device: AdbDevice, timeout_sec: float, *, include_guest_activities: bool
) -> dict[str, Any]:
    deadline = time.monotonic() + max(0.0, timeout_sec)
    last: dict[str, Any] = {}
    while True:
        last = _host_activity_teardown_state(
            device, include_guest_activities=include_guest_activities
        )
        if not last["activity_present"] and not last["window_present"]:
            return last
        remaining = deadline - time.monotonic()
        if remaining <= 0.0:
            raise VerificationFailure(
                "HOST_ACTIVITY_TEARDOWN_TIMEOUT",
                json.dumps({"timeoutSec": timeout_sec, **last}, ensure_ascii=False),
                FailureClass.HARNESS_DEFECT,
            )
        time.sleep(min(0.1, remaining))


def _capture(context: SmokeContext, attempt_dir: Path, label: str) -> dict[str, Any]:
    device = context.device
    attempt_dir.mkdir(parents=True, exist_ok=True)
    logcat = device.logcat(timeout_sec=60.0)
    window_dump = device.dump("window", "windows", timeout_sec=60.0)
    activity_dump = device.dump("activity", "activities", timeout_sec=60.0)
    processes = device.processes(timeout_sec=30.0)
    log_path = attempt_dir / f"{label}-logcat.txt"
    window_path = attempt_dir / f"{label}-window-dump.txt"
    activity_path = attempt_dir / f"{label}-activity-dump.txt"
    process_path = attempt_dir / f"{label}-processes.txt"
    log_path.write_text(logcat, encoding="utf-8")
    window_path.write_text(window_dump, encoding="utf-8")
    activity_path.write_text(activity_dump, encoding="utf-8")
    process_path.write_text(processes, encoding="utf-8")
    screen_path = attempt_dir / f"{label}-screenshot.png"
    screenshot = device.capture_screenshot(screen_path, timeout_sec=30.0)
    screen = inspect_png(screen_path) if screenshot.ok and screen_path.is_file() else {
        "valid_png": False,
        "non_black": False,
        "error": _command_dict(screenshot),
    }
    artifacts = [
        _relative(context.root, path)
        for path in (log_path, window_path, activity_path, process_path)
    ]
    if screen_path.is_file():
        artifacts.append(_relative(context.root, screen_path))
    return {
        "logcat": logcat,
        "window_dump": window_dump,
        "activity_dump": activity_dump,
        "processes": processes,
        "screen": screen,
        "artifacts": artifacts,
    }


def _timeout_classification(logcat: str, package: str) -> FailureClass:
    """Keep a command timeout environmental unless package-scoped crash evidence exists."""

    package_marker = any(
        candidate and candidate in logcat for candidate in (package, HOST_PACKAGE)
    )
    has_product_crash = package_marker and any(
        marker in logcat for marker in ("FATAL EXCEPTION", "Fatal signal", "ANR in")
    )
    return classify_failure_text(logcat) if has_product_crash else FailureClass.ENVIRONMENT


def _wait_for_markers(device: AdbDevice, markers: tuple[str, ...], timeout_sec: float) -> str:
    deadline = time.monotonic() + timeout_sec
    current = ""
    while time.monotonic() < deadline:
        current = device.logcat(timeout_sec=60.0)
        if all(marker in current for marker in markers):
            return current
        time.sleep(0.2)
    return current


def _wait_for_guest_prepared(
    device: AdbDevice, package: str, timeout_sec: float
) -> tuple[int, int | None, str, str]:
    deadline = time.monotonic() + timeout_sec
    current = ""
    pattern = re.compile(r"GUEST_PREPARED .*package=" + re.escape(package) + r" .*physicalPid=(\d+)")
    generation_pattern = re.compile(r"GUEST_PREPARED .*generation=(\d+)")
    session_pattern = re.compile(r"GUEST_PREPARED .*session=([^\s]+)")
    while time.monotonic() < deadline:
        current = device.logcat(timeout_sec=60.0)
        match = pattern.search(current)
        if match:
            generation_match = generation_pattern.search(current[match.start():])
            generation = int(generation_match.group(1)) if generation_match else None
            session_match = session_pattern.search(current[match.start():])
            session = session_match.group(1) if session_match else ""
            return int(match.group(1)), generation, session, current
        if "FAIL hold-prepare" in current or "GUEST_PREPARE_FAILED" in current:
            break
        time.sleep(0.2)
    raise VerificationFailure(
        "GUEST_PREPARE_NOT_OBSERVED",
        f"package={package} logcat_tail={current[-500:]!r}",
        FailureClass.PRODUCT_DEFECT,
    )


def _extras(
    command: str,
    package: str,
    user: int,
    request_id: str,
    values: dict[str, Any],
) -> list[tuple[str, str, str]]:
    result: list[tuple[str, str, str]] = [
        ("es", "command", command),
        ("es", "package", package),
        ("ei", "user", str(user)),
        ("es", "requestId", request_id),
    ]
    for key, value in values.items():
        if isinstance(value, bool):
            result.append(("ez", key, "true" if value else "false"))
        elif isinstance(value, int):
            # DebugCommandActivity reads holdMs as a long.  Passing it as an
            # int makes Bundle#getLong return the default on Android.
            kind = "el" if key in {"holdMs", "staleGeneration"} else "ei"
            result.append((kind, key, str(value)))
        else:
            result.append(("es", key, str(value)))
    return result


def _matches_request(value: dict[str, Any], command: str, package: str, request_id: str) -> bool:
    return (
        value.get("command") == command
        and value.get("package") == package
        and value.get("requestId") == request_id
    )


def _launch_operation(value: dict[str, Any]) -> dict[str, Any]:
    operation = value.get("operation")
    return operation if isinstance(operation, dict) else {}


def _package_revision(value: Any) -> str:
    if isinstance(value, dict):
        for key in ("packageRevision", "recordRevision", "revisionAfter", "apkSha256", "sha256"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate:
                return candidate
        for child in value.values():
            revision = _package_revision(child)
            if revision:
                return revision
    elif isinstance(value, list):
        for child in value:
            revision = _package_revision(child)
            if revision:
                return revision
    return ""


def _generation(value: Any) -> int | None:
    if isinstance(value, dict):
        for key in ("generation", "recoveryGeneration"):
            candidate = value.get(key)
            if isinstance(candidate, int):
                return candidate
            if isinstance(candidate, str) and candidate.isdigit():
                return int(candidate)
        for child in value.values():
            generation = _generation(child)
            if generation is not None:
                return generation
    elif isinstance(value, list):
        for child in value:
            generation = _generation(child)
            if generation is not None:
                return generation
    return None


def _pid(value: Any) -> int | None:
    if isinstance(value, dict):
        for key in ("platformPid", "physicalPid", "pid"):
            candidate = value.get(key)
            if isinstance(candidate, int) and candidate > 0:
                return candidate
            if isinstance(candidate, str) and candidate.isdigit() and int(candidate) > 0:
                return int(candidate)
        for child in value.values():
            pid = _pid(child)
            if pid is not None:
                return pid
    elif isinstance(value, list):
        for child in value:
            pid = _pid(child)
            if pid is not None:
                return pid
    return None


def _session(value: Any) -> str:
    if isinstance(value, dict):
        for key in ("sessionId", "session"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate:
                return candidate
        for child in value.values():
            session = _session(child)
            if session:
                return session
    elif isinstance(value, list):
        for child in value:
            session = _session(child)
            if session:
                return session
    return ""


def _hash_for(context: SmokeContext, name: str) -> str:
    values = context.metadata.get("apk_hashes") or {}
    for path, digest in values.items():
        if name in Path(path).name and digest:
            return str(digest)
    return ""


def _request_id(prefix: str) -> str:
    return f"c6-t01a-{prefix}-{uuid.uuid4().hex}"


def _command_dict(result: AdbCommandResult) -> dict[str, Any]:
    return {
        "command": result.command,
        "returncode": result.returncode,
        "stdout": result.text()[:2000],
        "stderr": result.text("stderr")[:2000],
        "duration_ms": result.duration_ms,
        "timed_out": result.timed_out,
    }


def _relative(root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())
