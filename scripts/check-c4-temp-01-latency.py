#!/usr/bin/env python3
"""Static contract for the C4-TEMP-01 latency optimization.

This gate is intentionally narrow: it verifies that the optimization is broker-issued and
package-neutral, that isolated descriptors keep the full verification path, and that Guest
metadata no longer re-parses ordinary APK archives during prepare.
"""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def fail(message: str) -> None:
    print(f"FAIL C4-TEMP-01 latency contract: {message}")
    raise SystemExit(1)


keys = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/RuntimeKeys.java")
validator = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestRequestValidator.java")
lifecycle = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeGuestLifecycleCoordinator.java")
spec = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java")
env = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java")
client = read("app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java")
benchmark = read("tools/capability/run_c4_temp_01_quark_latency.py")
stub_activity = read("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/StubActivityBase.java")
receiver_transport = read(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/"
    "GuestDynamicReceiverTransport.java"
)

if "PACKAGE_REVISION_VERIFIED_BY_BROKER" not in keys:
    fail("missing broker-issued revision key")
if validator.count("PACKAGE_REVISION_VERIFIED_BY_BROKER") < 2:
    fail("validator must clear and mint the broker-issued proof")
if "out.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER" not in lifecycle:
    fail("validated artifact does not persist revision proof")
if "input.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER" not in lifecycle:
    fail("validated artifact does not restore revision proof")
if "packageRevisionVerifiedByBroker" not in spec:
    fail("GuestPackageSpec does not carry revision proof")
if "!spec.isolatedProcess && spec.packageRevisionVerifiedByBroker" not in env:
    fail("Guest verification shortcut is not restricted to broker-verified ordinary packages")
if "spec.isolatedProcess\n                        ? PackageRevisionSetVerifier.verify(spec.apkDescriptor" not in env:
    fail("isolated descriptor verification path is missing")

universe = re.search(r"private ArrayList<VirtualPackageProjectionSnapshot> packageUniverse\(.*?\n    \}", client, re.S)
if not universe:
    fail("RuntimeClient.packageUniverse method not found")
if "getPackageArchiveInfo" in universe.group(0):
    fail("peer package universe still parses APK archives on every request")
if "new VirtualPackageProjectionSnapshot(state, record.apkPath" not in universe.group(0):
    fail("peer package universe is not built from authority projection")

prepare_window = re.search(r"GuestResourceLoader\.LoadedResources loadedResources.*?GuestContext guestContext", env, re.S)
if not prepare_window:
    fail("Guest prepare metadata window not found")
window = prepare_window.group(0)
if "getPackageArchiveInfo" in window:
    fail("ordinary Guest prepare still parses APK archive metadata")
if "spec.packageState.applicationInfo()" not in window:
    fail("Guest prepare does not use authority ApplicationInfo")
if "127.0.0.1:" in benchmark or "com.ucpro" in benchmark or "com.quark.browser" in benchmark:
    fail("benchmark contains a hard-coded serial or Quark package")
if "resolve_rd_environment" not in benchmark or "resolve-activity" not in benchmark:
    fail("benchmark does not dynamically resolve RD or the physical launch component")
if "ensureFrameworkActivityInstrumentation" not in env:
    fail("Guest runtime has no last-boundary ActivityThread bridge reassertion")
if "reassertFrameworkActivityTransport" not in stub_activity:
    fail("physical Stub route does not enforce the ActivityThread bridge fence")
if "GUEST_INSTRUMENTATION_ROUTE_FENCE_FAILED" not in stub_activity:
    fail("ActivityThread bridge fence failure is not captured as evidence")
if "Intent sticky = registerHost(lease);" not in receiver_transport:
    fail("dynamic receiver registration is still marshaled through Guest main thread")
if "unregisterHost(lease);" not in receiver_transport:
    fail("dynamic receiver teardown does not use the caller-owned thread")
if "session.mainThread.call(() -> registerHost(lease))" in receiver_transport:
    fail("dynamic receiver registration retains the Guest-main-thread lock inversion")
if "session.mainThread.call(() -> unregisterHost(lease))" in receiver_transport:
    fail("dynamic receiver teardown retains the Guest-main-thread lock inversion")

print("PASS C4-TEMP-01 latency contract")
