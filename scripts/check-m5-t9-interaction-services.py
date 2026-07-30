#!/usr/bin/env python3
from __future__ import annotations

import json
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


require("docs/plans/M5_T9_WINDOW_INPUT_DISPLAY.md", "WindowSession", "ActivityClient",
        "InputMethodManager", "DisplayManager", "BLOCKED", "STATIC", "HOST")
require("docs/M5_T9_DEVELOPMENT_REPORT.md", "Source status: PASS",
        "Production status: PARTIAL", "Device evidence: 0", "read-only VA/NBB")
require("docs/comparisons/M5_T9_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox",
        "Device evidence remains 0", "WindowSession")
require("README.md", "## M5-T9 Window, ActivityClient, Input/IME and Display source baseline")
require("docs/ROADMAP.md", "## M5-T9 Window, ActivityClient, Input/IME and Display baseline")

contracts = (
    "VirtualWindowPolicySnapshot", "VirtualInputMethodProfileSnapshot",
    "VirtualDisplaySnapshot", "VirtualDisplayProfileSnapshot",
    "VirtualInteractionProfileSnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable")

require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualWindowPolicySnapshot.java",
        "MODE_BLOCKED", "MODE_STATIC", "MODE_HOST", "maximumWindows",
        "allowSystemAlertWindows", "allowScreenCaptureControl")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getInteractionProfile", "setInteractionProfile", "resetInteractionProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getInteractionProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onInteractionProfileChanged")

require("app/src/main/java/com/warden/controlledsandbox/VirtualInteractionDefaults.java",
        "VirtualWindowPolicySnapshot", "VirtualInputMethodProfileSnapshot",
        "VirtualDisplayProfileSnapshot")
require("app/src/main/java/com/warden/controlledsandbox/VirtualInteractionStore.java",
        "VirtualSystemServiceStore.Scope", "INTERACTION_PROFILE_VERSION_CONFLICT", "quarantine")
require("app/src/main/java/com/warden/controlledsandbox/VirtualInteractionStoreCodec.java",
        "static final int SCHEMA", 'put("window"', 'put("inputMethod"', 'put("display"')
require("app/src/main/java/com/warden/controlledsandbox/VirtualInteractionStorePersistence.java",
        "AtomicMoveNotSupportedException", "CRC32", "quarantine", "MAX_FILE_BYTES")
require("app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
        "getInteractionProfile", "setInteractionProfile", "resetInteractionProfile",
        "notifyInteractionProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
        "interactionProfile", "setInteractionProfile", "resetInteractionProfile")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "notifyInteractionProfileChanged", "onInteractionProfileChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/GuestInteractionState.java",
        "class WindowState", "class InputMethodState", "class ActivityClientState",
        "class DisplayState", "VIRTUAL_WINDOW_LIMIT_EXCEEDED",
        "VIRTUAL_INPUT_SESSION_LIMIT_EXCEEDED", "VIRTUAL_DISPLAY_LIMIT_EXCEEDED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InteractionObjectRewriter.java",
        "rewriteLayoutParams", "rewriteEditorInfo", "VIRTUAL_SYSTEM_ALERT_WINDOW_DENIED",
        "restore(restores)")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/InteractionServiceInvocationInterceptor.java",
        'case "window"', 'case "windowsession"', 'case "activityclient"',
        'case "inputmethod"', 'case "display"', "VIRTUAL_DISPLAY_CREATE_DENIED",
        "VIRTUAL_DISPLAY_MUTATION_DENIED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkInteractionObjectFactory.java",
        "displayInfo", "populatePoint", "taskDescription")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("activityClient"', 'attempt("window"', 'attempt("inputMethod"', 'attempt("display"')
for hook in ("WindowManagerHook", "ActivityClientHook", "InputMethodManagerHook", "DisplayManagerHook"):
    require(f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
            "ReflectiveServiceHook")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/InteractionProxyReadiness.java",
        "VIRTUAL_INTERACTION_PROXY_REQUIRED", '"window", "activityClient"',
        '"inputMethod"', '"display"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "InteractionProxyReadiness.require")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onInteractionProfileChanged", "getInteractionProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualInteractionStoreSelfTest.java",
        "INTERACTION_PROFILE_VERSION_CONFLICT", "corrupt file quarantined",
        "virtual users receive isolated display defaults")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/InteractionServiceVirtualizationSelfTest.java",
        "window session is wrapped", "window arguments are restored", "activity destruction clears",
        "host input-method catalog is hidden", "display metrics are projected", "HOST display mode passes through")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/InteractionProxyReadinessSelfTest.java",
        "PASS M5-T9 interaction proxy readiness self-test")

runner = text("tools/static_android_compile.py")
for test in ("VirtualInteractionStoreSelfTest", "InteractionServiceVirtualizationSelfTest",
             "InteractionProxyReadinessSelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

for root in ("app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
             "sandbox-native", "sandbox-native-companion"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"product source imports/reference-copies upstream namespace: {path.relative_to(ROOT)}")

try:
    preflight = json.loads(text("verification/m5-t9-source-preflight.json"))
    if preflight.get("stage") != "M5-T9": errors.append("M5-T9 preflight stage is incorrect")
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T9 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T9 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T9 Android build status must remain blocked-toolchain")
    services = preflight.get("interactionServices", {})
    if services.get("modes") != ["BLOCKED", "STATIC", "HOST"]:
        errors.append("M5-T9 mode contract is incorrect")
    if services.get("isolationKey") != ["packageName", "virtualUserId"]:
        errors.append("M5-T9 isolation key is incorrect")
    persistence = services.get("persistence", {})
    for key in ("atomic", "bounded", "crcVerified", "corruptStateQuarantine", "optimisticVersioning"):
        if persistence.get(key) is not True: errors.append(f"M5-T9 persistence evidence missing: {key}")
    for domain in ("window", "activityClient", "inputMethod", "display"):
        item = services.get(domain, {})
        if item.get("source") != "complete-for-stage": errors.append(f"{domain} source status is incorrect")
        if item.get("device") != "not-tested": errors.append(f"{domain} device status must be not-tested")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T9 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T9 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T9 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T9 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T9 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t9-interaction-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T9 gate")

if errors:
    print("FAIL M5-T9 interaction-service checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T9 interaction-service checks: Window/Input/Display source expanded; device limits remain explicit")
