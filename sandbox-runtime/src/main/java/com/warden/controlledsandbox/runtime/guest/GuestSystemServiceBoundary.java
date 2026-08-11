package com.warden.controlledsandbox.runtime.guest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seals Guest system-service access to the framework hooks that were actually installed.
 *
 * <p>Framework installation needs temporary access to the Host service transport before Guest
 * application code exists. Once installation and readiness validation complete, the boundary is
 * sealed. A known identity-bearing Android manager can then be returned only when its matching
 * hook is installed; a failed hook can never silently fall back to the Host manager.</p>
 */
final class GuestSystemServiceBoundary {
    private static final Map<String, String> HOOK_BY_ANDROID_SERVICE = hookMap();
    private volatile Map<String, Boolean> installedHooks;

    synchronized void seal(Map<String, Boolean> installed) {
        if (installed == null) throw new IllegalArgumentException("installed hooks are required");
        Map<String, Boolean> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(installed));
        if (installedHooks != null && !installedHooks.equals(snapshot)) {
            throw new IllegalStateException("GUEST_SYSTEM_SERVICE_BOUNDARY_ALREADY_SEALED");
        }
        installedHooks = snapshot;
    }

    boolean isKnownService(String androidServiceName) {
        return HOOK_BY_ANDROID_SERVICE.containsKey(androidServiceName);
    }

    void requireAvailable(String androidServiceName) {
        Map<String, Boolean> snapshot = installedHooks;
        String hook = HOOK_BY_ANDROID_SERVICE.get(androidServiceName);
        if (hook == null) {
            throw new SecurityException("GUEST_SYSTEM_SERVICE_NOT_ALLOWLISTED:" + androidServiceName);
        }
        if (snapshot == null) return; // Framework hook installation phase; Guest code is not running.
        if (!Boolean.TRUE.equals(snapshot.get(hook))) {
            throw new SecurityException("GUEST_SYSTEM_SERVICE_HOOK_UNAVAILABLE:"
                    + androidServiceName + ":" + hook);
        }
    }

    void requireHookAvailable(String hookName, String apiName) {
        Map<String, Boolean> snapshot = installedHooks;
        if (snapshot == null) {
            throw new SecurityException("GUEST_FRAMEWORK_API_NOT_READY:" + apiName);
        }
        if (!Boolean.TRUE.equals(snapshot.get(hookName))) {
            throw new SecurityException("GUEST_FRAMEWORK_HOOK_UNAVAILABLE:"
                    + apiName + ":" + hookName);
        }
    }

    boolean sealed() { return installedHooks != null; }

    private static Map<String, String> hookMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("activity", "activityManager");
        result.put("window", "window");
        result.put("input", "inputManager");
        result.put("input_method", "inputMethod");
        result.put("display", "display");
        result.put("appops", "appOps");
        result.put("permission", "permission");
        result.put("notification", "notification");
        result.put("jobscheduler", "jobScheduler");
        result.put("alarm", "alarm");
        result.put("clipboard", "clipboard");
        result.put("account", "account");
        result.put("storage", "storage");
        result.put("camera", "camera");
        result.put("location", "location");
        result.put("phone", "telephony");
        result.put("telephony_subscription_service", "subscription");
        result.put("wifi", "wifi");
        result.put("wifiscanner", "wifiScanner");
        result.put("connectivity", "connectivity");
        result.put("vpn_management", "vpn");
        result.put("user", "userManager");
        result.put("restrictions", "restrictions");
        result.put("launcherapps", "launcherApps");
        result.put("shortcut", "shortcut");
        result.put("appwidget", "appWidget");
        result.put("usagestats", "usageStats");
        result.put("device_policy", "devicePolicy");
        result.put("accessibility", "accessibility");
        result.put("captioning", "captioning");
        result.put("autofill", "autofill");
        result.put("biometric", "biometric");
        result.put("sensor_privacy", "sensorPrivacy");
        result.put("power", "power");
        // KeyguardManager is a framework-owned, read-only capability on the target APIs. Its
        // availability follows the validated power boundary; do not expose it when that boundary
        // failed, and never let an unknown service silently fall back to the Host context.
        result.put("keyguard", "power");
        result.put("vibrator", "vibrator");
        result.put("vibrator_manager", "vibrator");
        result.put("media_session", "mediaSession");
        result.put("media_router", "mediaRouter");
        result.put("dropbox", "dropBox");
        result.put("nfc", "nfc");
        result.put("usb", "usb");
        result.put("print", "print");
        result.put("companiondevice", "companionDevice");
        result.put("media_projection", "mediaProjection");
        result.put("search", "search");
        result.put("storagestats", "storageStats");
        result.put("sensor", "sensorCatalog");
        result.put("audio", "audioCapture");
        result.put("bluetooth", "bluetooth");
        return Collections.unmodifiableMap(result);
    }
}
