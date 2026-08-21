#!/usr/bin/env python3
"""C1-T06 RD campaign for package, split, revision, and virtual-user lifecycle."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import uuid
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import ROOT, artifacts_dir, git_identity, host_os, now_iso, write_json
from run_p1_00_rd import debug_command
from run_rd_campaign import HOST_PACKAGE, install_rd_apks, resolve_rd_environment, run_adb

TASK_ID = "C1-T06"
TRUST = ("--ez", "trustNativeGuest", "true")
BASIC_PACKAGE = "com.warden.controlledsandbox.fixture"
LIFECYCLE_PACKAGE = "com.warden.controlledsandbox.fixture.lifecycle"
SPLIT_PACKAGE = "com.warden.controlledsandbox.fixture.split"
LIFECYCLE_V1 = ROOT / "fixture-lifecycle/build/outputs/apk/v1/debug/fixture-lifecycle-v1-debug.apk"
LIFECYCLE_V2 = ROOT / "fixture-lifecycle/build/outputs/apk/v2/debug/fixture-lifecycle-v2-debug.apk"
SPLIT_BASE = ROOT / "fixture-split-base/build/outputs/apk/debug/fixture-split-base-debug.apk"
SPLIT_FEATURE = ROOT / "fixture-split-feature/build/outputs/apk/debug/fixtureSplitFeature-debug.apk"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_metadata() -> dict[str, Any]:
    paths = {
        "lifecycle_v1": LIFECYCLE_V1,
        "lifecycle_v2": LIFECYCLE_V2,
        "split_base": SPLIT_BASE,
        "split_feature": SPLIT_FEATURE,
    }
    return {
        name: {"path": str(path), "exists": path.is_file(),
               "sha256": sha256_file(path) if path.is_file() else "",
               "bytes": path.stat().st_size if path.is_file() else 0}
        for name, path in paths.items()
    }


def command(serial: str, name: str, package: str, user: int = 0,
            extra: list[str] | None = None) -> dict[str, Any]:
    request_id = uuid.uuid4().hex
    extras = ["--es", "command", name, "--es", "package", package,
              "--es", "requestId", request_id,
              "--ei", "user", str(user), *TRUST]
    if extra:
        extras.extend(extra)
    return debug_command(serial, extras, deadline_sec=120)


def require_pass(label: str, result: dict[str, Any]) -> dict[str, Any]:
    if str(result.get("status", "")).upper() != "PASS":
        raise RuntimeError(f"{label} failed: {json.dumps(result, ensure_ascii=False)}")
    return result


def require_expected_failure(label: str, result: dict[str, Any], needle: str) -> dict[str, Any]:
    if str(result.get("status", "")).upper() == "PASS":
        raise RuntimeError(f"{label} unexpectedly passed: {json.dumps(result, ensure_ascii=False)}")
    encoded = json.dumps(result, ensure_ascii=False)
    if needle not in encoded:
        raise RuntimeError(f"{label} had an unexpected failure: {encoded}")
    return result


def install(serial: str, paths: list[Path], *, multiple: bool = False,
            downgrade: bool = False) -> dict[str, Any]:
    quiesce_host(serial)
    for path in paths:
        if not path.is_file():
            raise RuntimeError(f"missing APK: {path}")
    if multiple:
        args = ["install-multiple", "-r", *[str(path) for path in paths]]
    else:
        args = ["install", "-r", "-d", str(paths[0])] if downgrade \
            else ["install", "-r", str(paths[0])]
    result = run_adb(serial, args, check=False)
    row = {"args": args, "returncode": result.returncode,
           "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    if result.returncode != 0:
        raise RuntimeError(f"ADB install failed: {json.dumps(row, ensure_ascii=False)}")
    return row


def uninstall(serial: str, package: str) -> dict[str, Any]:
    quiesce_host(serial)
    result = run_adb(serial, ["uninstall", package], check=False)
    return {"package": package, "returncode": result.returncode,
            "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}


def quiesce_host(serial: str) -> None:
    run_adb(serial, ["shell", "am", "force-stop", HOST_PACKAGE], check=False)
    for _ in range(50):
        process = run_adb(serial, ["shell", "pidof", HOST_PACKAGE], check=False)
        if not process.stdout.strip():
            return
        time.sleep(0.1)


def reset_host_data(serial: str) -> dict[str, Any]:
    result = run_adb(serial, ["shell", "pm", "clear", HOST_PACKAGE], check=False)
    row = {"package": HOST_PACKAGE, "returncode": result.returncode,
           "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    if result.returncode != 0 or result.stdout.strip().lower() != "success":
        raise RuntimeError(f"Host data reset failed: {json.dumps(row, ensure_ascii=False)}")
    return row


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", default="RD测试")
    args = parser.parse_args()

    output = artifacts_dir("catch-up-c1-t06")
    verification = ROOT / "verification/catch-up/C1-T06"
    verification.mkdir(parents=True, exist_ok=True)
    identity = git_identity()
    evidence: dict[str, Any] = {
        "task_id": TASK_ID,
        "started_at": now_iso(),
        "host_os": host_os(),
        "git": identity,
        "artifacts": artifact_metadata(),
        "steps": [],
        "known_issues": ["KI-T57-016", "KI-R03-026", "KI-R03-029"],
        "maturity_level": "RD_BASELINE",
        "va_pro_equivalent": "NOT_PROVEN",
    }
    try:
        environment = resolve_rd_environment(args.instance)
        evidence["environment"] = environment
        if not all(item["exists"] for item in evidence["artifacts"].values()):
            raise RuntimeError("C1-T06 lineage/split APKs are not all built")

        evidence["steps"].append({"name": "reset_host_data",
                                   "result": reset_host_data(environment["adb_serial"])})
        evidence["steps"].append({"name": "install_host_fixture_apks",
                                   "result": install_rd_apks(environment["adb_serial"])})
        serial = environment["adb_serial"]

        evidence["steps"].append({"name": "reset_lifecycle_physical_install",
                                   "result": uninstall(serial, LIFECYCLE_PACKAGE)})
        evidence["steps"].append({"name": "install_lifecycle_v1",
                                   "result": install(serial, [LIFECYCLE_V1])})
        evidence["steps"].append({"name": "lifecycle_v1_import",
                                   "result": require_pass("lifecycle v1 import",
                                                           command(serial, "import-prepare", LIFECYCLE_PACKAGE))})
        clone = require_pass("lifecycle clone", command(serial, "lifecycle-clone", LIFECYCLE_PACKAGE))
        evidence["steps"].append({"name": "lifecycle_clone", "result": clone})
        clone_user = int(clone["result"]["operation"].get("virtualUserId", 1))
        evidence["steps"].append({"name": "lifecycle_v1_launch_user0",
                                   "result": require_pass(
                                       "lifecycle v1 user0 launch",
                                       command(serial, "launch-component", LIFECYCLE_PACKAGE, 0, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.lifecycle.LifecycleActivity",
                                       ]))})
        evidence["steps"].append({"name": "lifecycle_v1_launch_clone",
                                   "result": require_pass(
                                       "lifecycle clone launch",
                                       command(serial, "launch-component", LIFECYCLE_PACKAGE, clone_user, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.lifecycle.LifecycleActivity",
                                       ]))})

        evidence["steps"].append({"name": "install_lifecycle_v2",
                                   "result": install(serial, [LIFECYCLE_V2])})
        evidence["steps"].append({"name": "lifecycle_v2_import",
                                   "result": require_pass("lifecycle v2 import",
                                                           command(serial, "import-prepare", LIFECYCLE_PACKAGE))})
        evidence["steps"].append({"name": "lifecycle_v2_launch",
                                   "result": require_pass(
                                       "lifecycle v2 launch",
                                       command(serial, "launch-component", LIFECYCLE_PACKAGE, 0, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.lifecycle.LifecycleV2Activity",
                                       ]))})
        evidence["steps"].append({"name": "install_lifecycle_v1_for_downgrade_policy",
                                   "result": install(serial, [LIFECYCLE_V1], downgrade=True)})
        evidence["steps"].append({"name": "lifecycle_downgrade_policy",
                                   "result": require_expected_failure(
                                       "lifecycle downgrade policy",
                                       command(serial, "import-prepare", LIFECYCLE_PACKAGE),
                                       "Package downgrade rejected")})
        evidence["steps"].append({"name": "lifecycle_rollback",
                                   "result": require_pass("lifecycle rollback",
                                                           command(serial, "lifecycle-rollback", LIFECYCLE_PACKAGE))})
        evidence["steps"].append({"name": "lifecycle_v1_after_rollback",
                                   "result": require_pass(
                                       "lifecycle v1 after rollback",
                                       command(serial, "launch-virtual-component", LIFECYCLE_PACKAGE, 0, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.lifecycle.LifecycleActivity",
                                       ]))})
        evidence["steps"].append({"name": "lifecycle_reset_identity",
                                   "result": require_pass(
                                       "lifecycle identity reset",
                                       command(serial, "lifecycle-reset-identity", LIFECYCLE_PACKAGE))})

        evidence["steps"].append({"name": "install_split_set",
                                   "result": install(serial, [SPLIT_BASE, SPLIT_FEATURE], multiple=True)})
        evidence["steps"].append({"name": "split_import",
                                   "result": require_pass("split import",
                                                           command(serial, "import-prepare", SPLIT_PACKAGE))})
        evidence["steps"].append({"name": "split_base_launch",
                                   "result": require_pass(
                                       "split base launch",
                                       command(serial, "launch-component", SPLIT_PACKAGE, 0, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.split.SplitBaseActivity",
                                       ]))})
        evidence["steps"].append({"name": "split_feature_launch",
                                   "result": require_pass(
                                       "split feature launch",
                                       command(serial, "launch-component", SPLIT_PACKAGE, 0, [
                                           "--es", "component",
                                           "com.warden.controlledsandbox.fixture.split.feature.FeatureActivity",
                                       ]))})

        evidence["steps"].append({"name": "basic_import",
                                   "result": require_pass("basic import",
                                                           command(serial, "import-prepare", BASIC_PACKAGE))})
        evidence["steps"].append({"name": "package_query_resolve_policy",
                                   "result": require_pass(
                                       "package query/resolve/policy",
                                       command(serial, "package-state-campaign", BASIC_PACKAGE, 0))})
        evidence["steps"].append({"name": "failed_install_session_rollback",
                                   "result": require_pass(
                                       "failed install session rollback",
                                       command(serial, "install-session-failure", BASIC_PACKAGE, 0))})
        evidence["steps"].append({"name": "clear_user0",
                                   "result": require_pass("clear user0", command(serial, "clear", BASIC_PACKAGE, 0))})
        evidence["steps"].append({"name": "clear_user1",
                                   "result": require_pass("clear user1", command(serial, "clear", BASIC_PACKAGE, 1))})
        evidence["steps"].append({"name": "delete_user1",
                                   "result": require_pass("delete user1", command(serial, "delete", BASIC_PACKAGE, 1))})
        evidence["steps"].append({"name": "basic_reinstall",
                                   "result": install_rd_apks(serial)})
        evidence["steps"].append({"name": "basic_reimport_after_reinstall",
                                   "result": require_pass(
                                       "basic reimport",
                                       command(serial, "import-prepare", BASIC_PACKAGE, 0))})
        evidence["steps"].append({"name": "basic_launch_after_reinstall",
                                   "result": require_pass(
                                       "basic launch after reinstall",
                                       command(serial, "launch", BASIC_PACKAGE, 0))})

        logcat = run_adb(serial, ["logcat", "-d", "-v", "threadtime"], check=False)
        (output / "c1-t06-logcat.txt").write_text(logcat.stdout, encoding="utf-8")
        evidence["raw_logcat"] = str(output / "c1-t06-logcat.txt")
        evidence["status"] = "PASS"
        evidence["finished_at"] = now_iso()
    except Exception as error:
        evidence["status"] = "FAIL"
        evidence["error"] = str(error)
        evidence["finished_at"] = now_iso()
        write_json(output / "evidence.json", evidence)
        write_json(verification / "c1-t06-rd-summary.json", evidence)
        print(json.dumps({"status": "FAIL", "output": str(output), "error": str(error)},
                         ensure_ascii=False, indent=2))
        return 1

    write_json(output / "evidence.json", evidence)
    write_json(verification / "c1-t06-rd-summary.json", evidence)
    print(json.dumps({"status": "PASS", "output": str(output),
                      "verification": str(verification / "c1-t06-rd-summary.json"),
                      "serial": evidence["environment"]["adb_serial"],
                      "steps": len(evidence["steps"])}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
