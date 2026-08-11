#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def require(path: str, token: str, errors: list[str]) -> None:
    if token not in text(path):
        errors.append(f"{path}: missing {token}")


def main() -> int:
    errors: list[str] = []
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
            "PackageManager processPackageManager = host.getPackageManager();", errors)
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
            "processPackageManager,", errors)
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java",
            'requireHookAvailable("packageManager", "getPackageManager")', errors)
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContext.java",
            "if (!sharedState.systemServices.isKnownService(name)) return null;", errors)
    require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
            "PackageManager packageManager", errors)
    require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
            "PackageManagerHook.install(packageManager, identity,", errors)
    require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextBoundarySelfTest.java",
            "Guest exposes the exact proxied process PackageManager", errors)
    require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/GuestContextBoundarySelfTest.java",
            "unknown system service cannot fall back to Host manager", errors)

    companion_manifest = text("sandbox-companion32/src/main/AndroidManifest.xml")
    for token in ["CompanionRuntimePackageAuthorityBootstrapService", 'android:exported="true"',
                  'android:permission="com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION"',
                  'android:process=":sandbox_server32"']:
        if token not in companion_manifest:
            errors.append(f"Companion manifest missing bootstrap contract: {token}")
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/CompanionRuntimePackageAuthorityBootstrapService.java",
            "RuntimePackageAuthorityCapability.token()", errors)
    require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/CompanionRuntimePackageAuthorityBootstrapService.java",
            "return Process.myPid()", errors)
    require("app/src/main/java/com/warden/controlledsandbox/PackageAuthorityBootstrapConnections.java",
            "installCompanionRuntime", errors)
    require("app/src/main/java/com/warden/controlledsandbox/PackageAuthorityBootstrapConnections.java",
            "checkSignatures", errors)
    require("app/src/main/java/com/warden/controlledsandbox/PackageAuthorityCapabilityRegistry.java",
            "COMPANION_RUNTIME_ROLE_PREFIX", errors)
    require("app/src/testHarness/java/com/warden/controlledsandbox/PackageManagementAuthorizationSelfTest.java",
            "registry.installCompanionRuntime", errors)

    for path in ["app/build.gradle", "sandbox-companion32/build.gradle",
                 "fixture-basic/build.gradle", "fixture-compat32/build.gradle"]:
        require(path, "release-signing.gradle", errors)
    for token in ["CONTROLLED_SANDBOX_RELEASE_STORE_FILE",
                  "CONTROLLED_SANDBOX_RELEASE_KEY_ALIAS",
                  "verifyControlledReleaseSigning"]:
        require("gradle/release-signing.gradle", token, errors)
    for path in ["scripts/reproducible-build.sh", "scripts/reproducible-build.ps1"]:
        require(path, ":fixture-compat32:assembleRelease", errors)
        require(path, ":fixture-basic:verifyControlledReleaseSigning", errors)
        require(path, ":fixture-compat32:verifyControlledReleaseSigning", errors)
        require(path, "release_apk_signing.py", errors)
    require("tools/release_apk_signing.py", "Host and Companion32 signer SHA-256 digests differ", errors)
    require("scripts/test-release-apk-signing.sh", "companion-mismatch", errors)

    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    by_id = {item["id"]: item for item in matrix["capabilities"]}
    if "exact object only after mandatory readiness" not in by_id["framework.package-manager"]["notes"]:
        errors.append("framework.package-manager evidence note is stale")
    if "COMPANION_RUNTIME" not in by_id["runtime.native-abi-routing"]["notes"]:
        errors.append("runtime.native-abi-routing evidence note is stale")
    if "Actual signed Android packaging" not in by_id["native.four-abi-build-architecture"]["notes"]:
        errors.append("native.four-abi-build-architecture evidence note is stale")

    lock_files = [p for p in ROOT.rglob("gradle.lockfile")
                  if not any(part in {".git", ".gradle", "build", "ref"} for part in p.relative_to(ROOT).parts)]
    status = "PASS" if not errors else "FAIL"
    report = {
        "status": status,
        "closedFindings": ["MAIN-R3-P1-01", "MAIN-R3-P1-02", "MAIN-R3-P1-03", "MAIN-R3-P2-01", "MAIN-R3-P3-01"],
        "environmentBlockedFindings": [] if lock_files else ["MAIN-R3-P2-02"],
        "gradleLockFileCount": len(lock_files),
        "errors": errors,
    }
    out = ROOT / "build/verification/main-r3-acceptance.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if errors:
        print("FAIL main R3 acceptance remediation")
        for error in errors:
            print(" - " + error)
        return 1
    print("PASS main R3 source/Host acceptance remediation; Gradle lock remains environment-gated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
