package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.VirtualCompatibilityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDetectionPolicySnapshot;
import com.warden.controlledsandbox.contract.VirtualGoogleServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Deterministic host-independent compatibility defaults. */
final class VirtualCompatibilityDefaults {
    private VirtualCompatibilityDefaults() { }
    static VirtualCompatibilityProfileSnapshot create(String packageName, int virtualUserId,
            long version, long updatedAtMs) {
        String scope = packageName + "#u" + virtualUserId;
        String advertisingId = uuid(scope + "#adid");
        String appSetId = uuid(scope + "#appset");
        String installationId = uuid(scope + "#installation");
        String attributionId = uuid(scope + "#oem").replace("-", "");
        String suffix = "u" + virtualUserId + "_" + Integer.toUnsignedString(scope.hashCode(), 36);
        VirtualWebViewProfileSnapshot webView = new VirtualWebViewProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "com.android.webview", "virtual",
                suffix, "sandbox_webview_u" + virtualUserId, true, true, false, 4);
        VirtualGoogleServicesProfileSnapshot google = new VirtualGoogleServicesProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, false, advertisingId, true,
                appSetId, Long.toUnsignedString(scope.hashCode() & 0xffffffffL, 16), installationId,
                List.of(), List.of("advertising_id", "app_set_id"));
        VirtualOemProfileSnapshot oem = new VirtualOemProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "AOSP", "controlled-sandbox", attributionId,
                List.of("ro.product.manufacturer", "ro.product.brand", "ro.product.model",
                        "ro.build.fingerprint", "ro.debuggable", "ro.secure"),
                List.of("ControlledSandbox", "ControlledSandbox", "Virtual Device",
                        "controlled/sandbox/virtual:1/CS1/1:user/release-keys", "0", "1"),
                List.of(), List.of("com.miui.securitycenter", "com.huawei.systemmanager",
                        "com.coloros.safecenter", "com.vivo.permissionmanager"));
        VirtualDetectionPolicySnapshot detection = new VirtualDetectionPolicySnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, true, true, true, 4096,
                List.of("/data/user/0/com.warden.controlledsandbox", "/proc/self/root/data/user/0/com.warden.controlledsandbox"),
                List.of("com.warden.controlledsandbox", "sandbox.internal.bridge", "virtual.runtime.internal"),
                List.of("com.warden.controlledsandbox", "sandbox.internal.bridge", "virtual.runtime.internal"));
        return new VirtualCompatibilityProfileSnapshot(version, updatedAtMs, webView, google, oem, detection);
    }
    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
