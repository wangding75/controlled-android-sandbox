package com.warden.controlledsandbox;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable permission-to-capability/AppOps mapping used by Package Service and Runtime. */
final class PermissionCapabilityRegistry {
    static final class Capability {
        final String permission;
        final String appOpName;
        final boolean runtimeControlled;
        Capability(String permission, String appOpName, boolean runtimeControlled) {
            this.permission = permission; this.appOpName = appOpName;
            this.runtimeControlled = runtimeControlled;
        }
    }

    private static final Map<String, Capability> CAPABILITIES = create();
    private PermissionCapabilityRegistry() { }

    static Capability resolve(String permission) {
        Capability known = CAPABILITIES.get(permission);
        return known == null ? new Capability(permission, "", true) : known;
    }

    private static Map<String, Capability> create() {
        Map<String, Capability> out = new LinkedHashMap<>();
        normal(out, "android.permission.INTERNET", "");
        runtime(out, "android.permission.CAMERA", "android:camera");
        runtime(out, "android.permission.RECORD_AUDIO", "android:record_audio");
        runtime(out, "android.permission.ACCESS_COARSE_LOCATION", "android:coarse_location");
        runtime(out, "android.permission.ACCESS_FINE_LOCATION", "android:fine_location");
        runtime(out, "android.permission.ACCESS_BACKGROUND_LOCATION", "android:background_location");
        runtime(out, "android.permission.POST_NOTIFICATIONS", "android:post_notification");
        runtime(out, "android.permission.READ_CONTACTS", "android:read_contacts");
        runtime(out, "android.permission.WRITE_CONTACTS", "android:write_contacts");
        runtime(out, "android.permission.READ_CALENDAR", "android:read_calendar");
        runtime(out, "android.permission.WRITE_CALENDAR", "android:write_calendar");
        runtime(out, "android.permission.READ_PHONE_STATE", "android:read_phone_state");
        runtime(out, "android.permission.CALL_PHONE", "android:call_phone");
        runtime(out, "android.permission.READ_CALL_LOG", "android:read_call_log");
        runtime(out, "android.permission.WRITE_CALL_LOG", "android:write_call_log");
        runtime(out, "android.permission.SEND_SMS", "android:send_sms");
        runtime(out, "android.permission.RECEIVE_SMS", "android:receive_sms");
        runtime(out, "android.permission.READ_SMS", "android:read_sms");
        runtime(out, "android.permission.BODY_SENSORS", "android:body_sensors");
        runtime(out, "android.permission.ACTIVITY_RECOGNITION", "android:activity_recognition");
        runtime(out, "android.permission.BLUETOOTH_SCAN", "android:bluetooth_scan");
        runtime(out, "android.permission.BLUETOOTH_CONNECT", "android:bluetooth_connect");
        runtime(out, "android.permission.READ_MEDIA_IMAGES", "android:read_media_images");
        runtime(out, "android.permission.READ_MEDIA_VIDEO", "android:read_media_video");
        runtime(out, "android.permission.READ_MEDIA_AUDIO", "android:read_media_audio");
        runtime(out, "android.permission.READ_EXTERNAL_STORAGE", "android:read_external_storage");
        runtime(out, "android.permission.WRITE_EXTERNAL_STORAGE", "android:write_external_storage");
        return java.util.Collections.unmodifiableMap(out);
    }
    private static void normal(Map<String, Capability> out, String permission, String op) {
        out.put(permission, new Capability(permission, op, false));
    }
    private static void runtime(Map<String, Capability> out, String permission, String op) {
        out.put(permission, new Capability(permission, op, true));
    }
}
