package com.warden.controlledsandbox.runtime.guest;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.TypedValue;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads component metadata from the Guest APK without consulting Host PackageManager state. */
final class GuestManifestMetadata {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private final Map<String, Bundle> providersByAuthority;
    private final Map<String, Bundle> providersByClass;
    private final Bundle applicationMetadata;

    private GuestManifestMetadata(Map<String, Bundle> providersByAuthority,
                                  Map<String, Bundle> providersByClass,
                                  Bundle applicationMetadata) {
        this.providersByAuthority = providersByAuthority;
        this.providersByClass = providersByClass;
        this.applicationMetadata = applicationMetadata == null
                ? null : new Bundle(applicationMetadata);
    }

    static GuestManifestMetadata read(AssetManager assets) throws Exception {
        return read(assets, null);
    }

    static GuestManifestMetadata read(AssetManager assets, Resources resources) throws Exception {
        Map<String, Bundle> byAuthority = new LinkedHashMap<>();
        Map<String, Bundle> byClass = new LinkedHashMap<>();
        Bundle application = new Bundle();
        try (XmlResourceParser parser = assets.openXmlResourceParser("AndroidManifest.xml")) {
            int applicationDepth = -1;
            String authorities = "";
            String className = "";
            String packageName = "";
            int providerDepth = -1;
            Bundle metadata = null;
            for (int event = parser.getEventType(); event != XmlResourceParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event == XmlResourceParser.START_TAG) {
                    String name = parser.getName();
                    if ("manifest".equals(name)) {
                        String declared = parser.getAttributeValue(null, "package");
                        packageName = declared == null ? "" : declared.trim();
                    } else if ("application".equals(name)) {
                        applicationDepth = parser.getDepth();
                    } else if ("provider".equals(name)) {
                        providerDepth = parser.getDepth();
                        authorities = parser.getAttributeValue(ANDROID_NS, "authorities");
                        className = resolveClassName(packageName,
                                parser.getAttributeValue(ANDROID_NS, "name"));
                        metadata = new Bundle();
                    } else if ("meta-data".equals(name) && metadata != null
                            && parser.getDepth() == providerDepth + 1) {
                        putMetadata(metadata, parser, resources);
                    } else if ("meta-data".equals(name) && applicationDepth >= 0
                            && parser.getDepth() == applicationDepth + 1) {
                        putMetadata(application, parser, resources);
                    }
                } else if (event == XmlResourceParser.END_TAG
                        && "provider".equals(parser.getName())
                        && parser.getDepth() == providerDepth) {
                    if (metadata != null) {
                        Bundle copy = new Bundle(metadata);
                        if (!className.isEmpty()) byClass.putIfAbsent(className, copy);
                        if (authorities != null) {
                            for (String authority : authorities.split(";")) {
                                String normalized = authority == null ? "" : authority.trim();
                                if (!normalized.isEmpty()) {
                                    byAuthority.putIfAbsent(normalized, new Bundle(copy));
                                }
                            }
                        }
                    }
                    authorities = "";
                    className = "";
                    providerDepth = -1;
                    metadata = null;
                }
            }
        }
        return new GuestManifestMetadata(byAuthority, byClass,
                application.isEmpty() ? null : application);
    }

    Bundle provider(String authority) {
        Bundle value = providersByAuthority.get(authority == null ? "" : authority.trim());
        return value == null ? null : new Bundle(value);
    }

    Bundle providerForClass(String className) {
        String normalized = className == null ? "" : className.trim();
        Bundle value = providersByClass.get(normalized);
        return value == null ? null : new Bundle(value);
    }

    private static String resolveClassName(String packageName, String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        if (name.isEmpty()) return "";
        if (name.startsWith(".")) return packageName + name;
        if (name.indexOf('.') < 0 && !packageName.isEmpty()) return packageName + "." + name;
        return name;
    }

    Bundle application() {
        return applicationMetadata == null ? null : new Bundle(applicationMetadata);
    }

    private static void putMetadata(Bundle out, XmlResourceParser parser, Resources resources) {
        String key = parser.getAttributeValue(ANDROID_NS, "name");
        if (key == null || key.trim().isEmpty()) return;
        key = key.trim();
        String rawResource = parser.getAttributeValue(ANDROID_NS, "resource");
        String rawValue = parser.getAttributeValue(ANDROID_NS, "value");
        int resource = parser.getAttributeResourceValue(ANDROID_NS, "resource", 0);
        if (resource == 0) resource = resourceId(rawResource);
        if (resource != 0) {
            out.putInt(key, resource);
            return;
        }
        int valueResource = parser.getAttributeResourceValue(ANDROID_NS, "value", 0);
        if (valueResource == 0) valueResource = resourceId(rawValue);
        if (valueResource != 0 && resources != null) {
            TypedValue typed = new TypedValue();
            try {
                resources.getValue(valueResource, typed, true);
                putTypedValue(out, key, typed);
            } catch (Resources.NotFoundException complexResource) {
                // PackageParser preserves references to complex resources (arrays, plurals,
                // maps) as resource IDs.  They are consumed later through Guest Resources;
                // attempting to coerce them to a scalar makes API 36 throw during Guest start.
                out.putInt(key, valueResource);
            }
            return;
        }
        putValue(out, key, rawValue);
    }

    private static int resourceId(String value) {
        if (value == null) return 0;
        String normalized = value.trim();
        String hex;
        if (normalized.startsWith("@0x")) {
            hex = normalized.substring(3);
        } else if (normalized.startsWith("@ref/0x")) {
            hex = normalized.substring(7);
        } else {
            return 0;
        }
        try {
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void putTypedValue(Bundle out, String key, TypedValue value) {
        if (value.type == TypedValue.TYPE_STRING) {
            out.putString(key, value.string == null ? null : value.string.toString());
        } else if (value.type == TypedValue.TYPE_FLOAT) {
            out.putFloat(key, value.getFloat());
        } else if (value.type == TypedValue.TYPE_INT_BOOLEAN) {
            out.putBoolean(key, value.data != 0);
        } else if (value.type >= TypedValue.TYPE_FIRST_INT
                && value.type <= TypedValue.TYPE_LAST_INT) {
            out.putInt(key, value.data);
        } else {
            out.putString(key, value.coerceToString() == null
                    ? null : value.coerceToString().toString());
        }
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
