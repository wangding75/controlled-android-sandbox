package com.warden.controlledsandbox.framework.core;

import android.util.Log;

/** One platform-wide hidden-API access point for the audited framework compatibility layer. */
final class HiddenApiAccess {
    private static final String TAG = "CS_HIDDEN_API";
    private static boolean attempted;
    private static String result = "NOT_ATTEMPTED";

    private HiddenApiAccess() { }

    static void ensureExemptions() {
        synchronized (HiddenApiAccess.class) {
            if (attempted) {
                return;
            }
            attempted = true;
            // Production GuestRuntimeEnvironment installs the exact, native ART bridge before
            // any framework hook is entered. Keeping this Java-side class as a marker avoids a
            // reflective VMRuntime fallback that would either be filtered on API35 or request a
            // process-wide "L" exemption on older releases.
            result = "NATIVE_BRIDGE_REQUIRED";
            Log.i(TAG, "hidden-api access delegated to native platform bridge");
        }
    }

    static String status() {
        synchronized (HiddenApiAccess.class) { return result; }
    }
}
