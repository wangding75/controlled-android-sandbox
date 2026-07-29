#!/usr/bin/env python3
"""Validate and collect the locked Host, Fixture, and 32-bit Companion APKs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath


def fail(message: str) -> None:
    raise SystemExit(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_value(root: Path, *args: str) -> str:
    try:
        return subprocess.check_output(
            ["git", *args], cwd=root, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def inspect_apk(path: Path, allowed_abis: set[str], required_libraries: set[str]) -> dict[str, object]:
    observed: dict[str, set[str]] = {}
    seen: set[str] = set()
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                name = info.filename
                if name in seen:
                    fail(f"APK contains duplicate ZIP entry: {path}: {name}")
                seen.add(name)
                pure = PurePosixPath(name)
                if pure.is_absolute() or ".." in pure.parts:
                    fail(f"APK contains unsafe ZIP entry: {path}: {name}")
                parts = pure.parts
                if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                    observed.setdefault(parts[1], set()).add(parts[2])
    except zipfile.BadZipFile as exc:
        fail(f"Invalid APK ZIP: {path}: {exc}")

    actual_abis = set(observed)
    if actual_abis != allowed_abis:
        fail(
            f"APK ABI mismatch for {path}: expected {sorted(allowed_abis)}, "
            f"found {sorted(actual_abis)}"
        )
    for abi in sorted(allowed_abis):
        missing = required_libraries - observed.get(abi, set())
        if missing:
            fail(f"APK {path} ABI {abi} is missing native libraries: {sorted(missing)}")

    return {
        "abis": sorted(actual_abis),
        "nativeLibraries": {abi: sorted(observed[abi]) for abi in sorted(observed)},
        "zipEntryCount": len(seen),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, help="copy validated APKs and write build-manifest.json")
    parser.add_argument("--android-tools", action="store_true", help="require aapt2 package-id and apksigner verification")
    args = parser.parse_args()

    root = args.root.resolve()
    lock_path = root / "build-environment.lock.json"
    lock = json.loads(lock_path.read_text())
    config = lock.get("deviceTestBuild")
    if not isinstance(config, dict) or config.get("schemaVersion") != 1:
        fail("Missing deviceTestBuild schema 1 in build-environment.lock.json")

    output = args.output.resolve() if args.output else None
    if output:
        output.mkdir(parents=True, exist_ok=True)

    aapt2: Path | None = None
    apksigner: Path | None = None
    if args.android_tools:
        sdk_value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        if not sdk_value:
            fail("--android-tools requires ANDROID_SDK_ROOT or ANDROID_HOME")
        tools = root / "build-environment.lock.json"
        build_tools = lock["toolchain"]["android"]["buildTools"]
        suffix = ".exe" if os.name == "nt" else ""
        aapt2 = Path(sdk_value) / "build-tools" / build_tools / f"aapt2{suffix}"
        apksigner = Path(sdk_value) / "build-tools" / build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        if not aapt2.is_file() or not apksigner.is_file():
            fail(f"Missing locked aapt2/apksigner under {Path(sdk_value) / 'build-tools' / build_tools}")

    artifacts: list[dict[str, object]] = []
    for item in config.get("artifacts", []):
        artifact_id = str(item["id"])
        source = root / str(item["apk"])
        if not source.is_file():
            fail(f"Missing locked device-test APK for {artifact_id}: {source}")
        allowed = set(map(str, item["allowedAbis"]))
        required = set(map(str, item["requiredNativeLibraries"]))
        details = inspect_apk(source, allowed, required)
        if aapt2 is not None and apksigner is not None:
            badging = subprocess.run([str(aapt2), "dump", "badging", str(source)], text=True, capture_output=True)
            if badging.returncode != 0:
                fail(f"aapt2 rejected APK {source}: {badging.stderr.strip()}")
            match = re.search(r"package: name='([^']+)'", badging.stdout)
            actual_application_id = match.group(1) if match else ""
            if actual_application_id != item["applicationId"]:
                fail(f"APK applicationId mismatch for {artifact_id}: expected {item['applicationId']}, found {actual_application_id or '<missing>'}")
            signature = subprocess.run([str(apksigner), "verify", "--verbose", str(source)], text=True, capture_output=True)
            if signature.returncode != 0:
                fail(f"apksigner rejected APK {source}: {(signature.stderr or signature.stdout).strip()}")
            details["applicationIdVerified"] = actual_application_id
            details["signatureVerified"] = True
        destination_name = f"{artifact_id}-debug.apk"
        if output:
            destination = output / destination_name
            shutil.copyfile(source, destination)
            if sha256(source) != sha256(destination):
                fail(f"Copied APK checksum mismatch: {destination}")
        artifacts.append(
            {
                "id": artifact_id,
                "module": item["module"],
                "applicationId": item["applicationId"],
                "source": str(source.relative_to(root).as_posix()),
                "collectedName": destination_name,
                "sha256": sha256(source),
                "size": source.stat().st_size,
                **details,
            }
        )

    manifest = {
        "schemaVersion": 1,
        "commit": git_value(root, "rev-parse", "HEAD"),
        "commitShort": git_value(root, "rev-parse", "--short=12", "HEAD"),
        "toolchainLockSha256": sha256(lock_path),
        "variant": config["variant"],
        "artifacts": artifacts,
    }
    if output:
        (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        checksums = "".join(
            f"{item['sha256']}  {item['collectedName']}\n" for item in artifacts
        )
        (output / "SHA256SUMS.txt").write_text(checksums)

    print(
        "PASS locked device-test APK set: "
        + ", ".join(f"{item['id']}={item['size']}B" for item in artifacts)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
