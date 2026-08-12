#!/usr/bin/env python3
"""Static T54 guard for generic Android/OEM compatibility boundaries."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def require(path, *tokens):
    value = ROOT / path
    if not value.is_file():
        errors.append(f"missing {path}")
        return
    text = value.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            errors.append(f"{path} missing {token}")

require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/ActivityTaskFrameworkInterceptor.java",
        "getTaskForActivity", "onlyRoot", "virtualActivityToken")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/ActivityClientHook.java",
        "ActivityClient", "INTERFACE_SINGLETON")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        "activityClient", "jobScheduler")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java",
        "firstLong", "getActivityInfo")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestContextComponentRouter.java",
        "Context.RECEIVER_EXPORTED", "RuntimeKeys.RECEIVER_EXPORTED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/JobSchedulerHook.java",
        "IJobScheduler")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/provider/BrokerProviderRuntime.java",
        "CALLER_PACKAGE_NAME", "CALLER_VIRTUAL_USER_ID", "callerUser")
require("docs/oem-compat/SX_REAL_DEVICE_COMPAT_BASELINE.md",
        "HYPEROS_REGRESSION_CASE_01", "REAL_DEVICE_VERIFICATION_PENDING",
        "ARCHITECTURE_NOT_APPLICABLE")

if errors:
    print("FAIL T54 Android/OEM compatibility guard")
    for error in errors:
        print(" - " + error)
    sys.exit(1)
print("PASS T54 Android/OEM compatibility guard")
