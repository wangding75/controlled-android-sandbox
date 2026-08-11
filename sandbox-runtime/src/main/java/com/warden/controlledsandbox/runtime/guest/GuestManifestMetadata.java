package com.warden.controlledsandbox.runtime.guest;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads component metadata from the Guest APK without consulting Host PackageManager state. */
final class GuestManifestMetadata {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private final Map<String, Bundle> providersByAuthority;

    private GuestManifestMetadata(Map<String, Bundle> providersByAuthority) {
        this.providersByAuthority = providersByAuthority;
    }

    static GuestManifestMetadata read(AssetManager assets) throws Exception {
        Map<String, Bundle> values = new LinkedHashMap<>();
        try (XmlResourceParser parser = assets.openXmlResourceParser("AndroidManifest.xml")) {
            String authorities = "";
            int providerDepth = -1;
            Bundle metadata = null;
            for (int event = parser.getEventType(); event != XmlResourceParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event == XmlResourceParser.START_TAG) {
                    String name = parser.getName();
                    if ("provider".equals(name)) {
                        providerDepth = parser.getDepth();
                        authorities = parser.getAttributeValue(ANDROID_NS, "authorities");
                        metadata = new Bundle();
                    } else if ("meta-data".equals(name) && metadata != null
                            && parser.getDepth() == providerDepth + 1) {
                        String key = parser.getAttributeValue(ANDROID_NS, "name");
                        if (key != null && !key.trim().isEmpty()) {
                            int resource = parser.getAttributeResourceValue(ANDROID_NS, "resource", 0);
                            if (resource != 0) {
                                metadata.putInt(key, resource);
                            } else {
                                putValue(metadata, key, parser.getAttributeValue(ANDROID_NS, "value"));
                            }
                        }
                    }
                } else if (event == XmlResourceParser.END_TAG
                        && "provider".equals(parser.getName())
                        && parser.getDepth() == providerDepth) {
                    if (authorities != null && metadata != null) {
                        for (String authority : authorities.split(";")) {
                            String normalized = authority == null ? "" : authority.trim();
                            if (!normalized.isEmpty()) values.put(normalized, new Bundle(metadata));
                        }
                    }
                    authorities = "";
                    providerDepth = -1;
                    metadata = null;
                }
            }
        }
        return new GuestManifestMetadata(values);
    }

    Bundle provider(String authority) {
        Bundle value = providersByAuthority.get(authority == null ? "" : authority.trim());
        return value == null ? null : new Bundle(value);
    }

    private static void putValue(Bundle out, String key, String value) {
        if (value == null) return;
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized)) {
            out.putBoolean(key, Boolean.parseBoolean(normalized));
            return;
        }
        try {
            out.putInt(key, normalized.startsWith("0x")
                    ? (int) Long.parseLong(normalized.substring(2), 16)
                    : Integer.parseInt(normalized));
            return;
        } catch (NumberFormatException ignored) { }
        out.putString(key, value);
    }
}
