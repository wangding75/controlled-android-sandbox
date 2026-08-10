package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.WebViewProviderServiceContract;

import android.content.ComponentName;

/** Host contract tests for the exact platform WebView service capability. */
public final class WebViewProviderServiceContractSelfTest {
    private WebViewProviderServiceContractSelfTest() { }

    public static void main(String[] args) {
        ComponentName seed = new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                WebViewProviderServiceContract.VARIATIONS_SEED_SERVER);
        ComponentName components = new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                WebViewProviderServiceContract.COMPONENTS_PROVIDER_SERVICE);
        ComponentName metrics = new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                WebViewProviderServiceContract.METRICS_BRIDGE_SERVICE);
        ComponentName renderer0 = new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                "org.chromium.content.app.SandboxedProcessService0");
        ComponentName renderer31 = new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                "org.chromium.content.app.SandboxedProcessService31");
        check(WebViewProviderServiceContract.isProviderService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER, seed),
                "Variations Seed service contract");
        check(WebViewProviderServiceContract.isProviderService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER, components),
                "Components provider service contract");
        check(WebViewProviderServiceContract.isProviderService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER, metrics),
                "Metrics bridge service contract");
        check(WebViewProviderServiceContract.isRendererService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER, renderer0)
                        && WebViewProviderServiceContract.isRendererService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER, renderer31),
                "renderer slot bounds");
        check(!WebViewProviderServiceContract.isProviderService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER,
                        new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                                "org.chromium.android_webview.services.OtherService")),
                "unlisted provider service escaped");
        check(!WebViewProviderServiceContract.isRendererService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER,
                        new ComponentName(WebViewProviderServiceContract.GOOGLE_PROVIDER,
                                "org.chromium.content.app.SandboxedProcessService40")),
                "renderer slot overflow escaped");
        check(!WebViewProviderServiceContract.isProviderService(
                        WebViewProviderServiceContract.GOOGLE_PROVIDER,
                        new ComponentName("com.google.android.gms",
                                WebViewProviderServiceContract.COMPONENTS_PROVIDER_SERVICE)),
                "package spoofing escaped provider contract");
        check(WebViewProviderServiceContract.isControlledUnavailableDependency(
                WebViewProviderServiceContract.GOOGLE_PROVIDER,
                WebViewProviderServiceContract.GOOGLE_MOBILE_SERVICES),
                "optional dependency controlled absence");
        check(WebViewProviderServiceContract.isControlledUnavailableDependency(
                WebViewProviderServiceContract.AOSP_PROVIDER,
                WebViewProviderServiceContract.GOOGLE_MOBILE_SERVICES),
                "AOSP optional dependency controlled absence");
        System.out.println("PASS WebView provider service contract self-test");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
