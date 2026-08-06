#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def source(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"missing required file: {relative}")
        return ""
    return path.read_text(encoding="utf-8")


def require(relative: str, *tokens: str) -> str:
    text = source(relative)
    for token in tokens:
        if token not in text:
            errors.append(f"{relative} missing evidence: {token}")
    return text


dispatcher = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestMainThreadDispatcher.java",
    "Future<T> brokerCall = brokerWorkers.submit(action)",
    "PendingTask<?> task = pollReentrant(handler, remaining)",
    "ConcurrentMap<Handler, BlockingQueue<PendingTask<?>>> handlerPending",
    "ThreadLocal<Handler> activeHandler",
    "activeHandler.get() == handler",
    "AtomicBoolean claimed",
    "if (!claimed.compareAndSet(false, true)) return",
    "finally {\n            Thread.currentThread().setContextClassLoader(previous);",
)
component_runtime = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestComponentRuntime.java",
    "session.mainThread.call(() -> invokeOperation(request, operation))",
    "return withGuestClassLoader(() -> invokeOperation(request, operation));",
    "session.context.dynamicReceivers.scheduler(receiverId)",
    "session.mainThread.callOnHandler",
    "synchronized (providerLock)",
)
if "synchronized Bundle invoke(" in component_runtime:
    errors.append("GuestComponentRuntime still holds a monitor across Guest callbacks")

environment = require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
    "mainThread.run(application::onCreate)",
    "this.mainThread = context.mainThread",
)
if "synchronized boolean onVirtualJobStart" in environment or "synchronized boolean onVirtualJobStop" in environment:
    errors.append("Guest virtual Job callbacks still wait under the Session monitor")

require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/GuestActivityController.java",
    "ClassLoader previous = Thread.currentThread().getContextClassLoader()",
    "Thread.currentThread().setContextClassLoader(previous)",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestJobServiceBridge.java",
    "private final java.util.Set<Integer> starting",
    "mainThread.call",
    "starting.remove(guestJobId)",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestDynamicReceiverRegistry.java",
    "Handler scheduler",
    "Handler scheduler(String id)",
)

require(
    "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/BinaryXmlManifestParser.java",
    'case "path-permission" -> addProviderPathPermission',
    'case "grant-uri-permission" -> addProviderGrantRule',
    'element.stringAttr("readPermission")',
    'element.stringAttr("writePermission")',
    'element.boolAttr("grantUriPermissions", false)',
)
require(
    "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/provider/ProviderAuthorityRegistry.java",
    "requiredReadPermission(String uri)",
    "requiredWritePermission(String uri)",
    "allowsUriGrant(String uri)",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/ProviderManifestAuthorityResolver.java",
    "PROVIDER_PACKAGE_STATE_REQUIRED",
    "VirtualPackageStateSnapshot",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/BrokerProviderRuntime.java",
    "declaredPermissionsAllow",
    "allBatchUrisGrantable",
    "PROVIDER_PERMISSION_DENIED",
)
require(
    "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/ProviderDeclaredPermissionAuthorizer.java",
    "effectiveGranted()",
    "matchesCaller",
    "brokerState.prepared",
)
require(
    "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/provider/AndroidSimpleGlobMatcher.java",
    "Bounded matcher for Android PatternMatcher.PATTERN_SIMPLE_GLOB semantics",
)
require(
    "sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/AndroidSimpleGlobMatcher.java",
    "Bounded matcher for Android PatternMatcher.PATTERN_SIMPLE_GLOB semantics",
)

runner = require("tools/static_android_compile.py", "ProviderPathPatternSelfTest",
                 "ReceiverPathPatternSelfTest", "BrokerProviderRuntimeSelfTest")
receipt_path = ROOT / "build/static-android-compile/verification/static-android-test-execution.json"
receipt = {}
if not receipt_path.is_file():
    errors.append("static Android test execution receipt is missing")
else:
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    if not receipt.get("completed") or int(receipt.get("executedTestCount", 0)) < 120:
        errors.append("static Android test receipt does not contain the complete hardened suite")

report = {
    "task": "PRE-DEVICE-RUNTIME-HARDENING",
    "status": "PASS" if not errors else "FAIL",
    "checks": {
        "guestMainThreadLifecycle": True,
        "synchronousBinderReentry": True,
        "threadContextClassLoaderRestoration": True,
        "customReceiverScheduler": True,
        "jobDuplicateStartAndMonitorBoundary": True,
        "providerReadWritePathPermission": True,
        "providerUriGrantPolicy": True,
        "androidSimpleGlob": True,
    },
    "staticTestCount": receipt.get("executedTestCount", 0),
    "errors": errors,
}
out = ROOT / "build/verification/pre-device-runtime-hardening.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

if errors:
    print("FAIL pre-device runtime hardening")
    for error in errors:
        print(" - " + error)
    raise SystemExit(1)
print(f"PASS pre-device runtime hardening (staticTests={report['staticTestCount']})")
