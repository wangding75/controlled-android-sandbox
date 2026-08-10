package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.WebViewProviderServiceContract;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualBluetoothProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSensorProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWifiProfileSnapshot;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.framework.packagemanager.PackageManagerInvocationHandlerTestAccess;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side WebView, Google/OEM identity and identifier projection tests. */
public final class CompatibilityVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity("STATIC");
        FakeWebViewDelegate delegate = new FakeWebViewDelegate();
        WebViewApi webView = proxy(WebViewApi.class, delegate, identity, "webviewupdate");

        FakePackageInfo info = webView.getCurrentWebViewPackage();
        require(
                info != null
                        && info.packageName.equals("com.android.webview")
                        && delegate.calls == 0,
                "WebView provider projected");
        require(
                webView.isMultiProcessEnabled()
                        && webView.isWebViewPackage("com.android.webview"),
                "WebView policy projected");
        require(webView.getValidWebViewPackages().length == 1,
                "WebView package array projected");

        FakeProviderResponse response = webView.waitForAndGetProvider();
        require(
                response != null
                        && response.status == 0
                        && response.packageInfo != null
                        && response.packageInfo.packageName.equals("com.android.webview"),
                "WebView provider response projected");
        boolean denied = false;
        try {
            webView.enableMultiProcess();
        } catch (SecurityException expected) {
            denied = true;
        }
        require(denied, "WebView mutation denied");

        PackageManagerApi packageManager = packageManager(
                feature -> false, identity("STATIC"));
        require(packageManager.hasSystemFeature(WebViewProviderServiceContract.WEBVIEW_FEATURE),
                "STATIC WebView profile exposes FEATURE_WEBVIEW");
        packageManager = packageManager(feature -> true, identity("BLOCKED"));
        require(!packageManager.hasSystemFeature(WebViewProviderServiceContract.WEBVIEW_FEATURE),
                "BLOCKED WebView profile hides FEATURE_WEBVIEW");
        packageManager = packageManager(feature -> true, identity("HOST"));
        require(packageManager.hasSystemFeature(WebViewProviderServiceContract.WEBVIEW_FEATURE),
                "HOST WebView delegates FEATURE_WEBVIEW");

        DeviceIdApi identifiers = proxy(
                DeviceIdApi.class, new FakeDeviceDelegate(), identity, "deviceidentifiers");
        require(identifiers.getSerialForPackage("guest.pkg").equals("SERIAL123"),
                "device serial projected");
        require(
                identifiers.getAdvertisingId()
                        .equals("11111111-2222-3333-4444-555555555555"),
                "advertising id projected");

        GmsApi gms = proxy(GmsApi.class, new FakeGmsDelegate(), identity, "gms");
        require(
                gms.isGooglePlayServicesAvailable() && gms.getAccountTypes().length == 1,
                "GMS availability and account types projected");

        OemApi oem = proxy(OemApi.class, new FakeOemDelegate(), identity, "oemidentifier");
        require(
                oem.getOAID().equals("oem-attribution") && oem.isSupported(),
                "OEM attribution projected");

        GuestIdentity hostIdentity = identity("HOST");
        FakeWebViewDelegate hostDelegate = new FakeWebViewDelegate();
        proxy(WebViewApi.class, hostDelegate, hostIdentity, "webviewupdate")
                .getCurrentWebViewPackage();
        require(hostDelegate.calls == 1, "HOST WebView passes through");

        System.out.println("PASS M5-T12 compatibility virtualization self-test");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> type,
            T delegate,
            GuestIdentity identity,
            String serviceName) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                new SystemServiceInvocationHandler(delegate, identity, serviceName));
    }

    private static GuestIdentity identity(String mode) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
        app.uid = 12001;

        VirtualDeviceIdentitySnapshot deviceIdentity = new VirtualDeviceIdentitySnapshot(
                "STATIC",
                "0123456789abcdef",
                "SERIAL123",
                "11111111-2222-3333-4444-555555555555",
                true,
                "installation",
                "Sandbox",
                "Sandbox",
                "Virtual",
                "sandbox",
                "sandbox",
                "fp",
                "board",
                "hardware");
        VirtualDeviceServiceProfileSnapshot deviceServices =
                new VirtualDeviceServiceProfileSnapshot(
                        1L,
                        0L,
                        new VirtualLocationProfileSnapshot(
                                "BLOCKED",
                                "",
                                false,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                false,
                                0,
                                0,
                                ""),
                        deviceIdentity,
                        new VirtualTelephonyProfileSnapshot(
                                "BLOCKED", -1, -1, false, false, false, List.of()),
                        new VirtualWifiProfileSnapshot(
                                "BLOCKED",
                                false,
                                "",
                                "",
                                "",
                                0,
                                -1,
                                0,
                                -127,
                                0,
                                false,
                                false,
                                List.of()),
                        new VirtualBluetoothProfileSnapshot(
                                "BLOCKED", false, 10, "", "", false, List.of(), List.of()),
                        new VirtualSensorProfileSnapshot("BLOCKED", 1, List.of()));

        VirtualCompatibilityProfileSnapshot compatibility =
                new VirtualCompatibilityProfileSnapshot(
                        1L,
                        0L,
                        new VirtualWebViewProfileSnapshot(
                                mode,
                                "com.android.webview",
                                "virtual",
                                "suffix",
                                "renderer",
                                true,
                                true,
                                false,
                                4),
                        new VirtualGoogleServicesProfileSnapshot(
                                mode,
                                true,
                                "11111111-2222-3333-4444-555555555555",
                                true,
                                "app-set",
                                "0f0e0d0c0b0a0908",
                                "installation",
                                List.of("com.google"),
                                List.of("maps")),
                        new VirtualOemProfileSnapshot(
                                mode,
                                "AOSP",
                                "sandbox",
                                "oem-attribution",
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        new VirtualDetectionPolicySnapshot(
                                mode,
                                true,
                                true,
                                true,
                                true,
                                true,
                                100,
                                List.of(),
                                List.of(),
                                List.of()));

        return new GuestIdentity(
                "guest.pkg",
                12001,
                app,
                Set.of(),
                "host.pkg",
                10001,
                new VirtualPackageMetadata("guest.pkg", "", app, List.of()),
                "guest.pkg",
                0,
                1,
                new VirtualPermissionPolicy(Set.of(), Map.of()),
                new SandboxAppOpsPolicy(Map.of()),
                event -> { },
                new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(deviceServices, null, null, null, compatibility),
                "rev");
    }

    @SuppressWarnings("unchecked")
    private static PackageManagerApi packageManager(
            PackageManagerApi delegate, GuestIdentity identity) {
        return (PackageManagerApi) Proxy.newProxyInstance(
                PackageManagerApi.class.getClassLoader(),
                new Class<?>[] {PackageManagerApi.class},
                PackageManagerInvocationHandlerTestAccess.create(delegate, identity));
    }

    interface WebViewApi {
        FakePackageInfo getCurrentWebViewPackage();
        FakeProviderResponse waitForAndGetProvider();
        boolean isMultiProcessEnabled();
        boolean isWebViewPackage(String packageName);
        FakePackageInfo[] getValidWebViewPackages();
        void enableMultiProcess();
    }

    interface DeviceIdApi {
        String getSerialForPackage(String packageName);
        String getAdvertisingId();
    }

    interface GmsApi {
        boolean isGooglePlayServicesAvailable();
        String[] getAccountTypes();
    }

    interface OemApi {
        String getOAID();
        boolean isSupported();
    }

    public interface PackageManagerApi {
        boolean hasSystemFeature(String feature);
    }

    public static final class FakePackageInfo {
        public String packageName;
        public String versionName;
    }

    public static final class FakeProviderResponse {
        public FakePackageInfo packageInfo;
        public int status = -1;
    }

    static final class FakeWebViewDelegate implements WebViewApi {
        int calls;

        @Override
        public FakePackageInfo getCurrentWebViewPackage() {
            calls++;
            return new FakePackageInfo();
        }

        @Override
        public FakeProviderResponse waitForAndGetProvider() {
            calls++;
            return new FakeProviderResponse();
        }

        @Override
        public boolean isMultiProcessEnabled() {
            calls++;
            return false;
        }

        @Override
        public boolean isWebViewPackage(String packageName) {
            calls++;
            return false;
        }

        @Override
        public FakePackageInfo[] getValidWebViewPackages() {
            calls++;
            return new FakePackageInfo[0];
        }

        @Override
        public void enableMultiProcess() {
            calls++;
        }
    }

    static final class FakeDeviceDelegate implements DeviceIdApi {
        @Override
        public String getSerialForPackage(String packageName) {
            return "host";
        }

        @Override
        public String getAdvertisingId() {
            return "host";
        }
    }

    static final class FakeGmsDelegate implements GmsApi {
        @Override
        public boolean isGooglePlayServicesAvailable() {
            return false;
        }

        @Override
        public String[] getAccountTypes() {
            return new String[0];
        }
    }

    static final class FakeOemDelegate implements OemApi {
        @Override
        public String getOAID() {
            return "host";
        }

        @Override
        public boolean isSupported() {
            return false;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
