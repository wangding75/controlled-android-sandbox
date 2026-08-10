package com.warden.controlledsandbox.framework.contract;

import android.content.ComponentName;

/**
 * Bounded framework capability used by the selected platform WebView provider.
 *
 * <p>The provider package is selected by the virtual compatibility profile. Only the
 * renderer service family that Chromium asks PackageManager for is exposed; arbitrary
 * components in either provider package remain hidden from Guest code.</p>
 */
public final class WebViewProviderServiceContract {
    public static final String WEBVIEW_FEATURE = "android.software.webview";
    public static final String AOSP_PROVIDER = "com.android.webview";
    public static final String GOOGLE_PROVIDER = "com.google.android.webview";
    public static final String VARIATIONS_SEED_SERVER =
            "org.chromium.android_webview.services.VariationsSeedServer";
    public static final String COMPONENTS_PROVIDER_SERVICE =
            "org.chromium.android_webview.services.ComponentsProviderService";
    public static final String METRICS_BRIDGE_SERVICE =
            "org.chromium.android_webview.services.MetricsBridgeService";
    public static final String METRICS_UPLOAD_SERVICE =
            "org.chromium.android_webview.services.MetricsUploadService";
    public static final String GOOGLE_MOBILE_SERVICES = "com.google.android.gms";
    public static final String SUBSTRATUM_THEME_ENGINE = "projekt.substratum";

    private static final String SANDBOXED_PROCESS_SERVICE =
            "org.chromium.content.app.SandboxedProcessService";
    private static final int MIN_RENDERER_SLOT = 0;

    private WebViewProviderServiceContract() { }

    public static boolean isProviderPackage(String packageName) {
        return AOSP_PROVIDER.equals(packageName) || GOOGLE_PROVIDER.equals(packageName);
    }

    public static boolean isVariationsSeedServer(String providerPackage,
                                                   ComponentName component) {
        return isProviderComponent(providerPackage, component)
                && VARIATIONS_SEED_SERVER.equals(component.getClassName());
    }

    public static boolean isProviderService(String providerPackage, ComponentName component) {
        return isProviderComponent(providerPackage, component)
                && (VARIATIONS_SEED_SERVER.equals(component.getClassName())
                || COMPONENTS_PROVIDER_SERVICE.equals(component.getClassName())
                || METRICS_BRIDGE_SERVICE.equals(component.getClassName())
                || METRICS_UPLOAD_SERVICE.equals(component.getClassName()));
    }

    public static boolean isRendererService(String providerPackage, ComponentName component) {
        if (!isProviderComponent(providerPackage, component)) return false;
        String className = component.getClassName();
        if (!className.startsWith(SANDBOXED_PROCESS_SERVICE)) return false;
        String suffix = className.substring(SANDBOXED_PROCESS_SERVICE.length());
        if (suffix.isEmpty() || suffix.length() > 2) return false;
        int slot;
        try { slot = Integer.parseInt(suffix); }
        catch (NumberFormatException ignored) { return false; }
        int maxRendererSlot = android.os.Build.VERSION.SDK_INT >= 35 ? 31 : 39;
        return slot >= MIN_RENDERER_SLOT && slot <= maxRendererSlot
                && Integer.toString(slot).equals(suffix);
    }

    /** Optional provider integration is represented as controlled absence, never Host metadata. */
    public static boolean isControlledUnavailableDependency(String providerPackage,
                                                             String requestedPackage) {
        return isProviderPackage(providerPackage)
                && (GOOGLE_MOBILE_SERVICES.equals(requestedPackage)
                || SUBSTRATUM_THEME_ENGINE.equals(requestedPackage));
    }

    private static boolean isProviderComponent(String providerPackage,
                                                ComponentName component) {
        return isProviderPackage(providerPackage)
                && component != null
                && providerPackage.equals(component.getPackageName());
    }
}
