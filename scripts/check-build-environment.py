#!/usr/bin/env python3
"""Validate that build declarations and, optionally, Android tools match the lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def java_major() -> int:
    result = subprocess.run(["java", "-version"], text=True, capture_output=True, check=True)
    text = result.stderr + result.stdout
    match = re.search(r'version "(\d+)', text)
    if not match:
        fail("Cannot parse java -version")
    return int(match.group(1))


def property_map(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            fail(f"Malformed property in {path}: {line}")
        result[key] = value.replace("\\:", ":")
    return result


def require_text(path: Path, expected: str) -> None:
    if expected not in path.read_text():
        fail(f"Missing locked declaration in {path}: {expected}")


def sdk_root(root: Path) -> Path | None:
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    local = root / "local.properties"
    if local.is_file():
        props = property_map(local)
        if "sdk.dir" in props:
            return Path(props["sdk.dir"].replace("\\\\", "\\"))
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android", action="store_true", help="require the exact Android/JDK build toolchain")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root / "build-environment.lock.json").read_text())
    tools = lock["toolchain"]
    java = tools["java"]
    gradle = tools["gradle"]
    android = tools["android"]

    major = java_major()
    if major < java["minimumRuntimeVersion"]:
        fail(f"Java {major} is too old; minimum is {java['minimumRuntimeVersion']}")
    if args.android and major != java["languageVersion"]:
        fail(f"Reproducible Android build requires Java {java['languageVersion']}; found {major}")

    if (root / ".java-version").read_text().strip() != str(java["languageVersion"]):
        fail(".java-version does not match build lock")

    wrapper = property_map(root / "gradle/wrapper/gradle-wrapper.properties")
    if wrapper.get("distributionUrl") != gradle["distribution"]:
        fail("Gradle distribution URL does not match build lock")
    if wrapper.get("distributionSha256Sum") != gradle["distributionSha256"]:
        fail("Gradle distribution checksum does not match build lock")
    wrapper_hash = hashlib.sha256((root / "gradle/wrapper/gradle-wrapper.jar").read_bytes()).hexdigest()
    if wrapper_hash != gradle["compatibilityWrapperJarSha256"]:
        fail("Compatibility Gradle wrapper JAR checksum does not match build lock")

    root_build = root / "build.gradle"
    require_text(root_build, f"version '{android['androidGradlePlugin']}'")
    require_text(root_build, f"controlledCompileSdk = {android['compileSdk']}")
    require_text(root_build, f"controlledTargetSdk = {android['targetSdk']}")
    require_text(root_build, f"controlledMinSdk = {android['minSdk']}")
    require_text(root_build, f"controlledBuildTools = '{android['buildTools']}'")
    require_text(root_build, f"controlledNdkVersion = '{android['ndk']}'")
    require_text(root_build, f"controlledCmakeVersion = '{android['cmake']}'")

    for module in ("app", "fixture-basic", "sandbox-contract", "sandbox-framework", "sandbox-native", "sandbox-runtime"):
        path = root / module / "build.gradle"
        require_text(path, "compileSdk rootProject.ext.controlledCompileSdk")
        require_text(path, "buildToolsVersion rootProject.ext.controlledBuildTools")
    for module in ("fixture-basic", "sandbox-native"):
        require_text(root / module / "build.gradle", "ndkVersion rootProject.ext.controlledNdkVersion")
        require_text(root / module / "build.gradle", "version rootProject.ext.controlledCmakeVersion")

    for gradle_file in root.rglob("*.gradle"):
        if not gradle_file.is_file():
            continue
        text = gradle_file.read_text()
        if re.search(r"(?:version|implementation|api|classpath)[^\n]*['\"][^'\"]*(?:\+|SNAPSHOT|latest)[^'\"]*['\"]", text, re.I):
            fail(f"Dynamic dependency or plugin version found in {gradle_file}")

    attributes = (root / ".gitattributes").read_text()
    if "* text=auto eol=lf" not in attributes:
        fail("Repository line endings are not normalized")

    if args.android:
        sdk = sdk_root(root)
        if sdk is None:
            fail("ANDROID_SDK_ROOT/ANDROID_HOME or local.properties sdk.dir is required")
        required = [
            sdk / "platforms" / f"android-{android['compileSdk']}",
            sdk / "build-tools" / android["buildTools"],
            sdk / "ndk" / android["ndk"],
            sdk / "cmake" / android["cmake"],
        ]
        missing = [str(path) for path in required if not path.exists()]
        if missing:
            fail("Missing locked Android components:\n" + "\n".join(missing))

    mode = "Android" if args.android else "host"
    print(f"PASS {mode} build environment lock check (Java {major})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
