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


require(
    "docs/plans/M5_T13_POLICY_ACCESSIBILITY_AUTOFILL_BIOMETRIC_POWER.md",
    "Device policy",
    "Accessibility",
    "Autofill",
    "Biometric and fingerprint",
    "Sensor privacy",
    "Power, WakeLock and Vibrator",
    "BLOCKED",
    "STATIC",
    "HOST",
)
require(
    "docs/M5_T13_DEVELOPMENT_REPORT.md",
    "Source status: PASS",
    "Production status: PARTIAL",
    "Device evidence: 0",
    "read-only VA/NBB",
)
require(
    "docs/comparisons/M5_T13_VA_NBB_COMPARISON.md",
    "VirtualApp",
    "NewBlackbox",
    "Device evidence remains 0",
)
require(
    "README.md",
    "## M5-T13 policy, accessibility, autofill, biometric, privacy and power source baseline",
)
require(
    "docs/ROADMAP.md",
    "## M5-T13 policy, accessibility, autofill, biometric, privacy and power baseline",
)

contracts = (
    "VirtualPolicyServicesProfileSnapshot",
    "VirtualDevicePolicyProfileSnapshot",
    "VirtualAccessibilityProfileSnapshot",
    "VirtualAutofillProfileSnapshot",
    "VirtualBiometricProfileSnapshot",
    "VirtualSensorPrivacyProfileSnapshot",
    "VirtualPowerProfileSnapshot",
)
for name in contracts:
    require(
        f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
        f"parcelable {name};",
    )
    require(
        f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
        "Parcelable",
    )

require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualDevicePolicyProfileSnapshot.java",
    "profileOwner",
    "deviceOwner",
    "cameraDisabled",
    "screenCaptureDisabled",
    "passwordQuality",
    "minimumPasswordLength",
    "maximumFailedPasswords",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualAccessibilityProfileSnapshot.java",
    "touchExplorationEnabled",
    "highTextContrastEnabled",
    "allowEventDispatch",
    "maximumClients",
    "recommendedTimeoutMs",
    "enabledServices",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualAutofillProfileSnapshot.java",
    "serviceComponent",
    "saveEnabled",
    "augmentedAutofillEnabled",
    "maximumSessions",
    "sessionTimeoutMs",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualBiometricProfileSnapshot.java",
    "hardwareDetected",
    "enrolled",
    "authenticatorTypes",
    "allowAuthentication",
    "deviceCredentialAllowed",
    "outcome",
    "maximumSessions",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualSensorPrivacyProfileSnapshot.java",
    "allSensorsPrivacyEnabled",
    "cameraPrivacyEnabled",
    "microphonePrivacyEnabled",
    "allowChanges",
    "maximumListeners",
)
require(
    "sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualPowerProfileSnapshot.java",
    "interactive",
    "powerSaveMode",
    "deviceIdleMode",
    "batteryOptimizationsIgnored",
    "maximumWakeLocks",
    "maximumWakeLockDurationMs",
    "maximumVibrations",
    "maximumVibrationDurationMs",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
    "getPolicyServicesProfile",
    "setPolicyServicesProfile",
    "resetPolicyServicesProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
    "getPolicyServicesProfile",
)
require(
    "sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
    "onPolicyServicesProfileChanged",
)

require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPolicyServicesDefaults.java",
    "VirtualPolicyServicesProfileSnapshot",
    "MODE_STATIC",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPolicyServicesStore.java",
    "POLICY_SERVICES_PROFILE_VERSION_CONFLICT",
    "deleteScopeBestEffort",
    "scope limit exceeded",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualPolicyServicesStorePersistence.java",
    "CRC32",
    ".corrupt",
    "ATOMIC_MOVE",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
    "getPolicyServicesProfile",
    "setPolicyServicesProfile",
    "resetPolicyServicesProfile",
    "notifyPolicyServicesProfileChanged",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java",
    "policyServicesProfile",
    "setPolicyServicesProfile",
    "resetPolicyServicesProfile",
)
require(
    "app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
    "onPolicyServicesProfileChanged",
)

require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/PolicyServicesInvocationInterceptor.java",
    'case "devicepolicy"',
    'case "accessibility"',
    'case "autofill"',
    'case "biometric", "fingerprint"',
    'case "sensorprivacy"',
    'case "power", "vibrator"',
    "VIRTUAL_DEVICE_POLICY_MUTATION_DENIED",
    "VIRTUAL_AUTOFILL_SESSION_LIMIT_EXCEEDED",
    "VIRTUAL_BIOMETRIC_CALLBACK_ADAPTER_REQUIRED",
    "VIRTUAL_WAKE_LOCK_LIMIT_EXCEEDED",
    "VIRTUAL_VIBRATION_LIMIT_EXCEEDED",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/SystemServiceInvocationHandler.java",
    "PolicyServicesInvocationInterceptor",
)
require(
    "sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
    'attempt("devicePolicy"',
    'attempt("accessibility"',
    'attempt("autofill"',
    'attempt("biometric"',
    'attempt("sensorPrivacy"',
    'attempt("power"',
    'attempt("vibrator"',
)
for hook in (
    "DevicePolicyManagerServiceHook",
    "AccessibilityManagerServiceHook",
    "AutofillManagerServiceHook",
    "BiometricServiceHook",
    "SensorPrivacyServiceHook",
    "PowerManagerServiceHook",
    "VibratorServiceHook",
):
    require(
        f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
        "install",
    )

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/PolicyServicesProxyReadiness.java",
    "VIRTUAL_DEVICE_POLICY_PROXY_REQUIRED",
    "VIRTUAL_ACCESSIBILITY_PROXY_REQUIRED",
    "VIRTUAL_AUTOFILL_PROXY_REQUIRED",
    "VIRTUAL_BIOMETRIC_PROXY_REQUIRED",
    "VIRTUAL_SENSOR_PRIVACY_PROXY_REQUIRED",
    "VIRTUAL_POWER_PROXY_REQUIRED",
    "VIRTUAL_VIBRATOR_PROXY_REQUIRED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "PolicyServicesProxyReadiness.require",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
    "onPolicyServicesProfileChanged",
    "getPolicyServicesProfile",
)

require(
    "app/src/testHarness/java/com/warden/controlledsandbox/VirtualPolicyServicesStoreSelfTest.java",
    "per-user policy scope isolation",
    "defaults fail closed",
    "optimistic policy update",
    "stale policy update rejected",
    "policy profile persisted",
    "corrupt policy store quarantined",
    "PASS M5-T13 policy-services profile store self-test",
)
require(
    "sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/PolicyServicesVirtualizationSelfTest.java",
    "device policy projected",
    "device policy mutation denied",
    "accessibility state projected",
    "finished Autofill session releases quota",
    "biometric authentication fails closed at callback adapter boundary",
    "camera privacy projected",
    "power mutation denied",
    "vibration denied",
    "HOST policy passes through",
    "PASS M5-T13 policy-services virtualization self-test",
)
require(
    "sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/PolicyServicesProxyReadinessSelfTest.java",
    "missing accessibility proxy blocks",
    "missing vibrator proxy blocks",
    "PASS M5-T13 policy-services proxy readiness self-test",
)

runner = text("tools/static_android_compile.py")
for class_name in (
    "com.warden.controlledsandbox.VirtualPolicyServicesStoreSelfTest",
    "com.warden.controlledsandbox.framework.core.PolicyServicesVirtualizationSelfTest",
    "com.warden.controlledsandbox.runtime.guest.PolicyServicesProxyReadinessSelfTest",
):
    if runner.count(f"'{class_name}'") != 1:
        errors.append(f"static Android compiler must execute {class_name} exactly once")

# Product modules must not import or embed the upstream implementation namespaces.
for root in (
    "app",
    "sandbox-contract",
    "sandbox-domain",
    "sandbox-framework",
    "sandbox-runtime",
    "sandbox-native",
    "sandbox-native-companion",
):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"upstream implementation namespace in product source: {path.relative_to(ROOT)}")

# Reference trees are frozen at the M5-T12 base for this iteration.
try:
    import subprocess

    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "97af65a", "--", "ref/upstream"],
        cwd=ROOT,
        text=True,
    ).splitlines()
    if changed:
        errors.append("M5-T13 modified read-only reference files: " + ", ".join(changed))
except Exception as exc:
    errors.append(f"cannot verify read-only references: {exc}")

try:
    preflight = json.loads(text("verification/m5-t13-source-preflight.json"))
    if preflight.get("sourceStatus") != "PASS":
        errors.append("M5-T13 preflight sourceStatus must be PASS")
    if preflight.get("productionStatus") != "PARTIAL":
        errors.append("M5-T13 preflight productionStatus must be PARTIAL")
    if preflight.get("deviceEvidenceCount") != 0:
        errors.append("M5-T13 preflight must not invent device evidence")
    if preflight.get("evidence", {}).get("typedParcelableContracts") != 7:
        errors.append("M5-T13 preflight must record seven typed contracts")
except Exception as exc:
    errors.append(f"invalid M5-T13 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T13 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
        {"wired": 110, "partial": 2, "not-applicable": 1}
    ):
        errors.append("M5-T13 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
        {"not-tested": 109, "not-applicable": 3, "blocked": 1}
    ):
        errors.append("M5-T13 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t13-policy-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T13 gate")

if errors:
    print("FAIL M5-T13 policy-services checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)

print(
    "PASS M5-T13 policy/accessibility/autofill/biometric/privacy/power checks: "
    "source expanded; Android/SystemUI/hardware/device limits remain explicit"
)
