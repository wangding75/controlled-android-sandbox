"""Witness P1-13 split-set update, catalog rollback, and package downgrade on one device."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

from run_p1_13_split_lifecycle import FIXTURE, HOST, SPLIT, adb_path, device, invoke, require, require_status, run


ROOT = Path(__file__).resolve().parents[2]


def installed_version(adb: str, serial: str) -> str:
    value = run([adb, "-s", serial, "shell", "dumpsys", "package", SPLIT])
    require(value, "dumpsys-package")
    for line in value.stdout.splitlines():
        if "versionCode=" in line:
            return line.strip()
    raise RuntimeError("versionCode missing from dumpsys")


def install_set(adb: str, serial: str, base: Path, config: Path, feature: Path,
                downgrade: bool = False) -> str:
    command = [adb, "-s", serial, "install-multiple", "-r"]
    if downgrade:
        command.append("-d")
    command.extend(map(str, (base, config, feature)))
    value = run(command)
    require(value, "install-split-set")
    return value.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--v1-base", required=True)
    parser.add_argument("--v1-config", required=True)
    parser.add_argument("--v1-feature", required=True)
    parser.add_argument("--v2-base", required=True)
    parser.add_argument("--v2-config", required=True)
    parser.add_argument("--v2-feature", required=True)
    args = parser.parse_args()
    files = {key: Path(value).resolve() for key, value in vars(args).items()
             if key.startswith("v") and key != "version"}
    for key, path in files.items():
        if not path.is_file():
            raise RuntimeError(key + " missing: " + str(path))
    adb = adb_path()
    output = ROOT / "out" / "verification" / args.run_id
    output.mkdir(parents=True, exist_ok=False)
    receipt: dict = {"task_id": "P1-13", "run_id": args.run_id, "attempt": 1,
                     "automatic_retry_performed": False, "device": device(adb, args.serial),
                     "cases": []}
    try:
        for path in (ROOT / "app/build/outputs/apk/debug/app-debug.apk",
                     ROOT / "fixture-basic/build/outputs/apk/debug/fixture-basic-debug.apk"):
            require(run([adb, "-s", args.serial, "install", "-r", str(path)]), "install-" + path.name)
        # This fixture package is solely P1-13 test state, so establishing a v1 baseline does not
        # alter a user package.  An absent package is also an acceptable baseline.
        run([adb, "-s", args.serial, "uninstall", SPLIT])
        receipt["cases"].append({"label": "install-v1", "output": install_set(
            adb, args.serial, files["v1_base"], files["v1_config"], files["v1_feature"]),
            "version": installed_version(adb, args.serial), "pass": True})
        receipt["cases"].append({"label": "import-v1", "result": require_status(
            invoke(adb, args.serial, args.run_id, "import-v1", "import-only", SPLIT, 0),
            "IMPORTED", "import-v1"), "pass": True})
        receipt["cases"].append({"label": "install-v2", "output": install_set(
            adb, args.serial, files["v2_base"], files["v2_config"], files["v2_feature"]),
            "version": installed_version(adb, args.serial), "pass": True})
        receipt["cases"].append({"label": "import-v2", "result": require_status(
            invoke(adb, args.serial, args.run_id, "import-v2", "import-only", SPLIT, 0),
            "IMPORTED", "import-v2"), "pass": True})
        receipt["cases"].append({"label": "catalog-rollback", "result": require_status(
            invoke(adb, args.serial, args.run_id, "catalog-rollback", "lifecycle-rollback", SPLIT, 0),
            "ROLLED_BACK", "catalog-rollback"), "pass": True})
        receipt["cases"].append({"label": "downgrade-v1", "output": install_set(
            adb, args.serial, files["v1_base"], files["v1_config"], files["v1_feature"], downgrade=True),
            "version": installed_version(adb, args.serial), "pass": True})
        receipt["cases"].append({"label": "import-after-downgrade", "result": require_status(
            invoke(adb, args.serial, args.run_id, "import-after-downgrade", "import-only", SPLIT, 0),
            "IMPORTED", "import-after-downgrade"), "pass": True})
        paths = run([adb, "-s", args.serial, "shell", "pm", "path", SPLIT])
        require(paths, "pm-path")
        if not all(item in paths.stdout for item in ("base.apk", "split_config.", "split_fixtureSplitFeature.apk")):
            raise RuntimeError("rollback-split-set-incomplete:" + paths.stdout.strip())
        receipt["cases"].append({"label": "rollback-split-paths", "paths": paths.stdout.splitlines(), "pass": True})
        receipt["result"] = "PASS"
    except Exception as error:  # preserve the device/command evidence for a real failure
        receipt["result"] = "FAIL"
        receipt["error"] = type(error).__name__ + ":" + str(error)
    receipt["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    (output / "run.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"run_dir": str(output), "result": receipt["result"]}))
    return 0 if receipt["result"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
