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

    for module in ("app", "fixture-basic", "fixture-compat32", "sandbox-companion32", "sandbox-contract", "sandbox-framework", "sandbox-native", "sandbox-runtime"):
        path = root / module / "build.gradle"
        text = path.read_text(encoding="utf-8")
        if "compileSdk = rootProject.ext.controlledCompileSdk" not in text and "compileSdk rootProject.ext.controlledCompileSdk" not in text:
            fail(f"Missing locked compileSdk declaration in {path}")
        if "buildToolsVersion rootProject.ext.controlledBuildTools" not in text and "buildToolsVersion = rootProject.ext.controlledBuildTools" not in text:
            fail(f"Missing locked buildToolsVersion declaration in {path}")
    for module in ("fixture-basic", "fixture-compat32", "sandbox-companion32", "sandbox-native"):
        path = root / module / "build.gradle"
        text = path.read_text(encoding="utf-8")
        if "ndkVersion = rootProject.ext.controlledNdkVersion" not in text and "ndkVersion rootProject.ext.controlledNdkVersion" not in text:
            fail(f"Missing locked ndkVersion declaration in {path}")
        if "version = rootProject.ext.controlledCmakeVersion" not in text and "version rootProject.ext.controlledCmakeVersion" not in text:
            fail(f"Missing locked cmake version declaration in {path}")

    expected_sdk_packages = [
        "platform-tools",
        f"platforms;android-{android['compileSdk']}",
        f"build-tools;{android['buildTools']}",
        f"ndk;{android['ndk']}",
        f"cmake;{android['cmake']}",
    ]
    if android.get("sdkPackages") != expected_sdk_packages:
        fail("Android SDK package lock does not match declared toolchain")
    if android.get("hostAbis") != ["arm64-v8a", "x86_64"]:
        fail("Host ABI lock must be arm64-v8a,x86_64")
    if android.get("companionAbis") != ["armeabi-v7a", "x86"]:
        fail("Companion ABI lock must be armeabi-v7a,x86")
    device_build = lock.get("deviceTestBuild", {})
    if device_build.get("schemaVersion") != 1 or device_build.get("variant") != "debug":
        fail("deviceTestBuild schema/variant is missing from build lock")
    if [item.get("id") for item in device_build.get("artifacts", [])] != ["host", "fixture", "companion32"]:
        fail("deviceTestBuild artifact set is not frozen")

    lab_build = lock.get("deviceLabBuild", {})
    if lab_build.get("schemaVersion") != 1 or lab_build.get("variant") != "debug":
        fail("deviceLabBuild schema/variant is missing from build lock")
    lab_artifacts = lab_build.get("artifacts", [])
    if [item.get("id") for item in lab_artifacts] != ["host", "fixture64", "fixture32", "companion32"]:
        fail("deviceLabBuild artifact set/order is not frozen")
    expected_lab = {
        "host": ("app", ["arm64-v8a", "x86_64"], "com.warden.controlledsandbox.debug"),
        "fixture64": ("fixture-basic", ["arm64-v8a", "x86_64"], "com.warden.controlledsandbox.fixture"),
        "fixture32": ("fixture-compat32", ["armeabi-v7a", "x86"], "com.warden.controlledsandbox.fixture32"),
        "companion32": ("sandbox-companion32", ["armeabi-v7a", "x86"], "com.warden.controlledsandbox.companion32.debug"),
    }
    for item in lab_artifacts:
        artifact_id = item.get("id")
        module, abis, application_id = expected_lab[artifact_id]
        if item.get("module") != module or item.get("allowedAbis") != abis:
            fail(f"deviceLabBuild {artifact_id} module/ABI lock mismatch")
        if item.get("applicationId") != application_id:
            fail(f"deviceLabBuild {artifact_id} applicationId lock mismatch")
        if not str(item.get("gradleTask", "")).startswith(f":{module}:"):
            fail(f"deviceLabBuild {artifact_id} Gradle task is not module-scoped")
        if not item.get("requiredNativeLibraries"):
            fail(f"deviceLabBuild {artifact_id} native library requirement is empty")
    if lab_build.get("outputDirectory") != "artifacts/m5-device-lab-build":
        fail("deviceLabBuild output directory is not frozen")

    lab = lock.get("deviceLab", {})
    if lab.get("schemaVersion") != 1:
        fail("deviceLab schemaVersion must be 1")
    if lab.get("requiredDeviceAbis") != ["x86_64", "x86"]:
        fail("deviceLab must require x86_64 and x86")
    if lab.get("formalStabilitySeconds") != 1200:
        fail("deviceLab formal stability gate must be 1200 seconds")
    if int(lab.get("bootTimeoutSeconds", 0)) < 300:
        fail("deviceLab boot timeout is too short")
    expected_lab_packages = ["emulator", str(lab.get("systemImage", ""))]
    if lab.get("sdkPackages") != expected_lab_packages:
        fail("deviceLab SDK packages must be emulator plus the locked system image")
    cli = lab.get("commandLineTools", {})
    if not re.fullmatch(r"\d+", str(cli.get("version", ""))):
        fail("deviceLab command-line tools version is not numeric")
    for platform in ("windows", "linux"):
        package = cli.get(platform, {})
        if not str(package.get("filename", "")).endswith(".zip"):
            fail(f"deviceLab {platform} command-line tools filename is invalid")
        if not str(package.get("url", "")).startswith("https://dl.google.com/android/repository/"):
            fail(f"deviceLab {platform} command-line tools URL is not frozen to dl.google.com")
        if not re.fullmatch(r"[0-9a-f]{64}", str(package.get("sha256", ""))):
            fail(f"deviceLab {platform} command-line tools SHA-256 is invalid")
    if lab.get("packages") != {
        "host": "com.warden.controlledsandbox.debug",
        "fixture64": "com.warden.controlledsandbox.fixture",
        "fixture32": "com.warden.controlledsandbox.fixture32",
        "companion32": "com.warden.controlledsandbox.companion32.debug",
    }:
        fail("deviceLab package contract is not frozen")

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
