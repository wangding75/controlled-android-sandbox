#!/usr/bin/env python3
"""Resolve a MuMu Player instance to its current adb serial.

The instance name is the project-level identity.  The TCP serial is derived from
the selected MuMu VM configuration for the current run and is never used to
select an instance on its own.
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

PROJECT_SCOPE = "Controlled Android Sandbox / 闪现2"
DEFAULT_INSTANCE_NAME = "SX测试"
DEFAULT_ROOTS = (
    Path(r"C:\Program Files\Netease\MuMu Player 12"),
    Path(r"C:\Program Files (x86)\Netease\MuMu Player 12"),
)


class ResolutionError(RuntimeError):
    """Raised when the requested MuMu instance cannot be resolved safely."""


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ResolutionError(f"cannot read MuMu config {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ResolutionError(f"MuMu config must be a JSON object: {path}")
    return value


def _nested(value: dict[str, Any], *keys: str, default: Any = "") -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return default
        current = current.get(key, default)
    return current


def find_mumu_root(explicit: Path | None) -> Path:
    candidates: list[Path] = []
    if explicit is not None:
        candidates.append(explicit)
    env_root = os.environ.get("MUMU_ROOT", "").strip()
    if env_root:
        candidates.append(Path(env_root))
    candidates.extend(DEFAULT_ROOTS)
    for candidate in candidates:
        if (candidate / "vms").is_dir():
            return candidate.resolve()
    searched = ", ".join(str(path) for path in candidates)
    raise ResolutionError(f"MuMu Player vms directory was not found; searched: {searched}")


def discover_instances(root: Path) -> list[dict[str, Any]]:
    vm_root = root / "vms"
    if not vm_root.is_dir():
        raise ResolutionError(f"MuMu vms directory does not exist: {vm_root}")
    instances: list[dict[str, Any]] = []
    for vm_dir in sorted(path for path in vm_root.iterdir() if path.is_dir()):
        config_dir = vm_dir / "configs"
        extra_path = config_dir / "extra_config.json"
        vm_config_path = config_dir / "vm_config.json"
        if not extra_path.is_file() or not vm_config_path.is_file():
            continue
        extra = load_object(extra_path)
        vm_config = load_object(vm_config_path)
        port = _nested(vm_config, "vm", "nat", "port_forward", "adb", "host_port")
        if not str(port).strip():
            continue
        suffix = vm_dir.name.rsplit("-", 1)[-1]
        index: int | str
        try:
            index = int(suffix)
        except ValueError:
            index = suffix
        instances.append(
            {
                "name": str(extra.get("playerName", "")).strip(),
                "index": index,
                "id": str(_nested(vm_config, "vm", "ginstance", default="")),
                "vmName": vm_dir.name,
                "vmDirectory": str(vm_dir.resolve()),
                "extraConfig": str(extra_path.resolve()),
                "vmConfig": str(vm_config_path.resolve()),
                "configuredHostPort": int(port),
                "configuredSerial": f"127.0.0.1:{int(port)}",
                "configuredManufacturer": str(_nested(vm_config, "vm", "phone", "manufacturer", default="")),
                "configuredModel": str(_nested(vm_config, "vm", "phone", "miit", default="")),
            }
        )
    if not instances:
        raise ResolutionError(f"no readable MuMu instances found under {vm_root}")
    return instances


def run_adb(adb: Path, args: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(adb), *args], text=True, capture_output=True, errors="replace", timeout=60
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ResolutionError(f"adb {' '.join(args)} failed ({result.returncode}): {detail}")
    return result


def run_manager(manager: Path, index: int | str) -> dict[str, Any]:
    if not manager.is_file():
        return {}
    result = subprocess.run(
        [str(manager), "info", "--vmindex", str(index)],
        text=False,
        capture_output=True,
        timeout=30,
    )
    if result.returncode != 0:
        return {
            "returnCode": result.returncode,
            "stderr": result.stderr.decode("utf-8", errors="replace").strip(),
        }
    stdout = result.stdout.decode("utf-8", errors="replace")
    try:
        value = json.loads(stdout)
    except json.JSONDecodeError:
        return {"returnCode": result.returncode, "raw": stdout.strip()}
    return value if isinstance(value, dict) else {"value": value}


def resolve_instance(
    instance_name: str = DEFAULT_INSTANCE_NAME,
    *,
    mumu_root: Path | None = None,
    adb: Path | None = None,
    connect: bool = True,
) -> dict[str, Any]:
    root = find_mumu_root(mumu_root)
    instances = discover_instances(root)
    matches = [item for item in instances if item["name"] == instance_name]
    if len(matches) != 1:
        if not matches:
            available = ", ".join(repr(item["name"]) for item in instances)
            raise ResolutionError(f"MuMu instance {instance_name!r} was not found; available: {available}")
        raise ResolutionError(f"MuMu instance name {instance_name!r} matched {len(matches)} configurations")
    selected = matches[0]
    adb_path = (adb or Path(os.environ.get("ADB", "adb"))).resolve() if adb else Path("adb")
    manager = root / "shell" / "MuMuManager.exe"
    manager_info = run_manager(manager, selected["index"])
    if manager_info and str(manager_info.get("name", "")).strip() not in ("", instance_name):
        raise ResolutionError(
            f"MuMu manager name mismatch for index {selected['index']}: "
            f"expected {instance_name!r}, found {manager_info.get('name')!r}"
        )
    host = str(manager_info.get("adb_host_ip", "127.0.0.1")).strip() or "127.0.0.1"
    port_value = manager_info.get("adb_port", selected["configuredHostPort"])
    try:
        port = int(port_value)
    except (TypeError, ValueError) as exc:
        raise ResolutionError(f"MuMu manager returned invalid ADB port: {port_value!r}") from exc
    serial = f"{host}:{port}"
    connect_result: dict[str, Any] = {}
    if connect:
        for attempt in range(1, 4):
            connected = run_adb(adb_path, ["connect", serial], check=False)
            state_probe = run_adb(adb_path, ["-s", serial, "get-state"], check=False)
            connect_result = {
                "attempt": attempt,
                "returnCode": connected.returncode,
                "stdout": connected.stdout.strip(),
                "stderr": connected.stderr.strip(),
                "getState": state_probe.stdout.strip(),
            }
            if state_probe.stdout.strip() == "device":
                break
            run_adb(adb_path, ["disconnect", serial], check=False)
            time.sleep(1)
    devices_result = run_adb(adb_path, ["devices", "-l"])
    state_result = run_adb(adb_path, ["-s", serial, "get-state"], check=False)
    state = state_result.stdout.strip()
    if state != "device":
        raise ResolutionError(
            f"resolved MuMu instance {instance_name!r} to {serial}, but adb get-state was {state!r}"
        )

    def prop(name: str) -> str:
        return run_adb(adb_path, ["-s", serial, "shell", "getprop", name]).stdout.strip()

    android_id = run_adb(
        adb_path, ["-s", serial, "shell", "settings", "get", "secure", "android_id"]
    ).stdout.strip()
    result: dict[str, Any] = {
        "project": PROJECT_SCOPE,
        "instanceName": instance_name,
        "instanceIndex": selected["index"],
        "instanceId": selected["id"],
        "vmName": selected["vmName"],
        "runtimeStatus": state,
        "mumuPlayerState": manager_info.get("player_state", ""),
        "mumuManager": manager_info,
        "resolvedSerial": serial,
        "configuredSerial": selected["configuredSerial"],
        "manufacturer": prop("ro.product.manufacturer"),
        "model": prop("ro.product.model"),
        "release": prop("ro.build.version.release"),
        "api": prop("ro.build.version.sdk"),
        "androidId": android_id,
        "timestamp": now_iso(),
        "mumuRoot": str(root),
        "config": {
            "extraConfig": selected["extraConfig"],
            "vmConfig": selected["vmConfig"],
            "configuredHostPort": selected["configuredHostPort"],
            "runtimeHost": host,
            "runtimeHostPort": port,
            "configuredManufacturer": selected["configuredManufacturer"],
            "configuredModel": selected["configuredModel"],
        },
        "adb": {
            "path": str(adb_path),
            "connect": connect_result,
            "devices": devices_result.stdout,
            "getState": state_result.stdout.strip(),
        },
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-name", default=DEFAULT_INSTANCE_NAME)
    parser.add_argument("--mumu-root", type=Path)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--no-connect", action="store_true")
    args = parser.parse_args()
    try:
        result = resolve_instance(
            args.instance_name,
            mumu_root=args.mumu_root,
            adb=args.adb,
            connect=not args.no_connect,
        )
    except ResolutionError as exc:
        print(f"mumu instance resolution failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
