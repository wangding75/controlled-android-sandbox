package com.warden.controlledsandbox.compatibility.dingtalk;

import android.content.Context;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;

/**
 * Explicit, default-off DingTalk control-plane orchestration.
 *
 * <p>This class only identifies a supported DingTalk revision and gates profile configuration.
 * Camera, location, identity, Wi-Fi and cell behavior remain in the generic Sandbox contracts;
 * no Android hook is selected from this package.</p>
 */
public final class DingTalkCompatibilityManager {
    public static final String PACKAGE_NAME = "com.alibaba.android.rimet";
    public static final String SUPPORTED_VERSION_NAME = "7.8.10";
    public static final long SUPPORTED_VERSION_CODE = 1178L;
    private static final String PREFS = "dingtalk-compatibility";
    private static final String ENABLED_PREFIX = "enabled.u";

    public Target identify(String packageName, String versionName, long versionCode) {
        boolean packageMatch = PACKAGE_NAME.equals(packageName);
        boolean versionMatch = SUPPORTED_VERSION_NAME.equals(versionName)
                && SUPPORTED_VERSION_CODE == versionCode;
        String reason = packageMatch && versionMatch ? "SUPPORTED_REVISION"
                : packageMatch ? "VERSION_GATE_MISMATCH" : "PACKAGE_GATE_MISMATCH";
        return new Target(packageName == null ? "" : packageName,
                versionName == null ? "" : versionName, versionCode,
                packageMatch && versionMatch, reason);
    }

    public void enable(Context context, Target target, int virtualUserId) {
        requireSupported(target);
        preferences(context).edit().putBoolean(key(target.packageName(), virtualUserId), true).apply();
    }

    public void disable(Context context, String packageName, int virtualUserId) {
        preferences(context).edit().putBoolean(key(packageName, virtualUserId), false).apply();
    }

    public boolean enabled(Context context, String packageName, int virtualUserId) {
        return preferences(context).getBoolean(key(packageName, virtualUserId), false);
    }

    public void requireEnabled(Context context, Target target, int virtualUserId) {
        requireSupported(target);
        if (!enabled(context, target.packageName(), virtualUserId)) {
            throw new IllegalStateException("DINGTALK_COMPATIBILITY_DEFAULT_OFF");
        }
    }

    /** Generic profile pass-through used by the control plane for audit output. */
    public ProfileSet genericProfiles(VirtualDeviceServiceProfileSnapshot device,
            VirtualCameraProfileSnapshot camera, VirtualNetworkServiceProfileSnapshot network,
            VirtualLocationProfileSnapshot location) {
        if (device == null || camera == null || network == null || location == null) {
            throw new IllegalArgumentException("DingTalk generic profiles are required");
        }
        return new ProfileSet(device, camera, network, location);
    }

    private static void requireSupported(Target target) {
        if (target == null || !target.supported()) {
            throw new IllegalArgumentException("DINGTALK_COMPATIBILITY_TARGET_UNSUPPORTED");
        }
    }

    private static android.content.SharedPreferences preferences(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String packageName, int virtualUserId) {
        String safe = packageName == null ? "" : packageName.replaceAll("[^A-Za-z0-9._-]", "_");
        return ENABLED_PREFIX + virtualUserId + "." + safe;
    }

    public record Target(String packageName, String versionName, long versionCode,
                         boolean supported, String reason) { }

    public record ProfileSet(VirtualDeviceServiceProfileSnapshot device,
                             VirtualCameraProfileSnapshot camera,
                             VirtualNetworkServiceProfileSnapshot network,
                             VirtualLocationProfileSnapshot location) { }
}
