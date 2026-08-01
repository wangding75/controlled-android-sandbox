#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")


def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require("docs/plans/M5_T7_PACKAGE_SURFACE.md", "PackageManager", "PackageInstaller", "Shared Library", "Instrumentation")
require("docs/M5_T7_DEVELOPMENT_REPORT.md", "Source status: PASS", "Device evidence: 0", "fail closed")
require("docs/comparisons/M5_T7_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox", "Device evidence remains 0")
require("README.md", "## M5-T7 package surface source baseline")
require("docs/ROADMAP.md", "## M5-T7 PackageManager, PackageInstaller, Shared Library and Instrumentation baseline")

for name in ("InstallSessionParamsSnapshot", "InstallSessionInfoSnapshot",
             "VirtualSharedLibrarySnapshot", "VirtualInstrumentationSnapshot"):
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable")

require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "createInstallSessionWithParams", "getInstallSessionInfo", "listInstallSessions",
        "setInstallSessionProgress", "retryInstallSession")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/PackageServiceResult.java",
        "InstallSessionInfoSnapshot installSession", "successInstallSessions", "installSessions()")
install_store = require("app/src/main/java/com/warden/controlledsandbox/PackageInstallSessionStore.java",
        "STATE_COMMITTING", "STATE_FAILED", "markFailed", "retry(", "setProgress(",
        "bytesStaged", "failureCodeB64", "STATE_SCHEMA")
schema_match = re.search(r"STATE_SCHEMA\s*=\s*(\d+)", install_store)
if schema_match is None or int(schema_match.group(1)) < 2:
    errors.append("install-session schema must retain M5-T7 fields at schema 2 or newer")
require("app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java",
        "requireSupportedCommitParams", "INSTALL_SESSION_INHERIT_EXISTING_UNSUPPORTED",
        "INSTALL_SESSION_ROLLBACK_UNSUPPORTED", "requireInstallableSharedLibraries")
require("app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java",
        "createInstallSessionWithParams", "listInstallSessions", "retryInstallSession")
require("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
        "InstallSessionInfoSnapshot", "setInstallSessionProgress", "retryInstallSession")

require("sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/BinaryXmlManifestParser.java",
        'case "uses-library"', 'case "uses-native-library"', 'case "uses-sdk-library"',
        'case "uses-static-library"', 'case "instrumentation"')
require("sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/ManifestModel.java",
        "SharedLibraryDependency", "providedSharedLibraries", "Instrumentation")
require("sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/SharedLibraryResolver.java",
        "missingRequired", "missingOptional", "certificate mismatch", "requireSuccessful")
require("app/src/main/java/com/warden/controlledsandbox/VirtualPackageStateBuilder.java",
        "requireInstallableSharedLibraries", "VirtualSharedLibrarySnapshot",
        "VirtualInstrumentationSnapshot", "baseAvailableLibraries")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualPackageMetadata.java",
        "InstrumentationInfo", "queryInstrumentation", "sharedLibraryInfoObjects",
        "resolvedSharedLibraryNames")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/SharedLibraryInfoFactory.java",
        "VersionedPackage", "getDeclaredConstructors", "libraryType")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java",
        'case "getInstrumentationInfo"', 'case "queryInstrumentation"',
        'case "getSystemSharedLibraryNames"', 'case "getSharedLibraries"')

require("app/src/testHarness/java/com/warden/controlledsandbox/PackageInstallSessionStoreSelfTest.java",
        "PackageInstaller-style", "markCommitting", "markFailed", "retry")
require("app/src/testHarness/java/com/warden/controlledsandbox/PackageServiceContractSelfTest.java",
        "install session snapshots lost", "shared library snapshot lost", "instrumentation snapshot lost")
require("sandbox-domain/src/testHarness/java/com/warden/controlledsandbox/domain/SelfTest.java",
        "testSharedLibraryResolution", "required shared library version mismatch fails closed")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/packagemanager/VirtualPackageQuerySelfTest.java",
        "PackageInfo exposes instrumentation", "SharedLibraryInfo objects created")

runner = text("tools/static_android_compile.py")
for test in ("PackageInstallSessionStoreSelfTest", "PackageServiceContractSelfTest", "VirtualPackageQuerySelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

try:
    preflight = json.loads(text("verification/m5-t7-source-preflight.json"))
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T7 source status must be pass")
    if preflight.get("productionStatus") != "wired": errors.append("M5-T7 package surface must be source-wired")
    if preflight.get("androidBuildStatus") != "blocked-toolchain": errors.append("Android build status must remain blocked-toolchain")
    surface = preflight.get("packageSurface", {})
    if surface.get("sharedLibraryKinds") != ["JAVA", "NATIVE", "SDK", "STATIC"]:
        errors.append("shared-library kind contract is incorrect")
    if surface.get("installSessionStates") != ["OPEN", "SEALED", "COMMITTING", "FAILED"]:
        errors.append("install-session state contract is incorrect")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T7 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T7 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    by_id = {item.get("id"): item for item in capabilities}
    if len(capabilities) != 113:
        errors.append(f"M5-T7 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all 113 capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter({"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T7 must not alter unresolved production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter({"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T7 device evidence matrix changed unexpectedly")
    for capability_id in ("framework.package-manager", "package.install-session",
                          "package.shared-library-declarations", "package.install-query-metadata"):
        item = by_id.get(capability_id, {})
        if item.get("sourceStatus") != "complete" or item.get("productionStatus") != "wired":
            errors.append(f"{capability_id} must remain source-complete and production-wired")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t7-package-surface.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T7 gate")

if errors:
    print("FAIL M5-T7 package-surface checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T7 package-surface checks: source wired; Android behavior remains device-deferred")
