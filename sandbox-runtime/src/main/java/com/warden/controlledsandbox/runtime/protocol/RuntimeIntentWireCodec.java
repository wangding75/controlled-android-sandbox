package com.warden.controlledsandbox.runtime.protocol;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Set;

/** Isolated Intent envelope that cannot overwrite Broker control fields. */
public final class RuntimeIntentWireCodec {
    /** Keep the complete Intent below the Broker route and Binder transaction budgets. */
    private static final int MAX_INTENT_PAYLOAD_BYTES = 256 * 1024;

    private RuntimeIntentWireCodec() { }

    public static void encode(Bundle target, Intent intent) {
        if (target == null || intent == null) return;
        byte[] wirePayload = marshal(intent);
        if (wirePayload.length != 0) {
            target.putByteArray(RuntimeKeys.INTENT_WIRE_PAYLOAD, wirePayload);
        }
        encodeIdentity(target, intent);
        encodeActionAndFlags(target, intent);
        encodeData(target, intent);
        encodeCategories(target, intent);
        Bundle extras = intent.getExtras();
        if (extras != null && !extras.isEmpty()) {
            target.putBundle(RuntimeKeys.INTENT_EXTRAS, new Bundle(extras));
        }
    }

    public static Intent decode(Bundle source) {
        Intent intent = new Intent();
        if (source == null) return intent;
        Intent complete = unmarshal(source.getByteArray(RuntimeKeys.INTENT_WIRE_PAYLOAD));
        if (complete != null) {
            intent = complete;
            // Parcel-created Bundles default to the host loader.  Restore them under the Guest
            // loader before any application Parcelable is touched by the caller.
            Bundle completeExtras = intent.getExtras();
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (completeExtras != null && loader != null) completeExtras.setClassLoader(loader);
        }
        decodeIdentity(intent, source);
        decodeActionAndFlags(intent, source);
        decodeData(intent, source);
        decodeCategories(intent, source);
        decodeExtras(intent, source);
        return intent;
    }

    private static byte[] marshal(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try {
            // The static API surface used by the source gate intentionally omits Parcelable
            // methods from a few framework classes. Reflection keeps the production path
            // available on real Android while preserving that compile-time compatibility.
            Intent.class.getMethod("writeToParcel", Parcel.class, int.class)
                    .invoke(intent, parcel, 0);
            byte[] payload = parcel.marshall();
            if (payload.length > MAX_INTENT_PAYLOAD_BYTES) {
                android.util.Log.w("CS_INTENT_WIRE",
                        "complete Intent payload exceeds bound: " + payload.length);
                return new byte[0];
            }
            return payload;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            // The explicit projection below is the compatibility path for compact/static API
            // adapters and for an Intent containing an unsupported custom Parcelable.
            android.util.Log.w("CS_INTENT_WIRE", "complete Intent marshal failed", error);
            return new byte[0];
        } finally {
            parcel.recycle();
        }
    }

    @SuppressWarnings("unchecked")
    private static Intent unmarshal(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_INTENT_PAYLOAD_BYTES) {
            return null;
        }
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(payload, 0, payload.length);
            parcel.setDataPosition(0);
            java.lang.reflect.Field field = Intent.class.getField("CREATOR");
            field.setAccessible(true);
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) field.get(null);
            return (Intent) creator.createFromParcel(parcel);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_INTENT_WIRE", "complete Intent restore failed", error);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    private static void encodeIdentity(Bundle target, Intent intent) {
        String packageName = value(intent.getPackage());
        if (!packageName.isEmpty()) target.putString(RuntimeKeys.TARGET_PACKAGE_NAME, packageName);
        ComponentName component = intent.getComponent();
        if (component == null) return;
        target.putString(RuntimeKeys.INTENT_COMPONENT_PACKAGE, value(component.getPackageName()));
        target.putString(RuntimeKeys.INTENT_COMPONENT_CLASS, value(component.getClassName()));
    }

    private static void encodeActionAndFlags(Bundle target, Intent intent) {
        String action = value(intent.getAction());
        if (!action.isEmpty()) {
            target.putString(ComponentOperations.ACTION, action);
            target.putString(RuntimeKeys.ACTIVITY_ACTION, action);
        }
        target.putInt(RuntimeKeys.ACTIVITY_FLAGS, intent.getFlags());
    }

    private static void encodeData(Bundle target, Intent intent) {
        Uri data = intent.getData();
        if (data != null) {
            target.putString(RuntimeKeys.URI, data.toString());
            target.putString(RuntimeKeys.BROADCAST_SCHEME, value(data.getScheme()));
            target.putString(RuntimeKeys.BROADCAST_HOST, value(data.getHost()));
            int port = uriPort(data);
            if (port >= 0) target.putInt(RuntimeKeys.BROADCAST_PORT, port);
            target.putString(RuntimeKeys.BROADCAST_PATH, value(data.getPath()));
        }
        String mimeType = value(intent.getType());
        if (!mimeType.isEmpty()) target.putString(RuntimeKeys.BROADCAST_MIME_TYPE, mimeType);
    }

    private static void encodeCategories(Bundle target, Intent intent) {
        Set<String> categories = intent.getCategories();
        if (categories != null && !categories.isEmpty()) {
            target.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES, new ArrayList<>(categories));
        }
    }

    private static void decodeIdentity(Intent intent, Bundle source) {
        String packageName = value(source.getString(RuntimeKeys.TARGET_PACKAGE_NAME, ""));
        if (!packageName.isEmpty()) intent.setPackage(packageName);
        String componentPackage = value(source.getString(RuntimeKeys.INTENT_COMPONENT_PACKAGE, ""));
        String componentClass = value(source.getString(RuntimeKeys.INTENT_COMPONENT_CLASS, ""));
        if (!componentPackage.isEmpty() && !componentClass.isEmpty()) {
            intent.setComponent(new ComponentName(componentPackage, componentClass));
        }
    }

    private static void decodeActionAndFlags(Intent intent, Bundle source) {
        String action = value(source.getString(ComponentOperations.ACTION, ""));
        if (!action.isEmpty()) intent.setAction(action);
        // Keep flags from the complete Parcel when the caller supplied only the opaque wire
        // payload.  The explicit projection remains authoritative whenever it is present.
        if (source.containsKey(RuntimeKeys.ACTIVITY_FLAGS)) {
            intent.setFlags(source.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0));
        }
    }

    private static void decodeData(Intent intent, Bundle source) {
        String uri = value(source.getString(RuntimeKeys.URI, ""));
        Uri data = uri.isEmpty() ? null : Uri.parse(uri);
        String mimeType = value(source.getString(RuntimeKeys.BROADCAST_MIME_TYPE, ""));
        if (data != null && !mimeType.isEmpty()) intent.setDataAndType(data, mimeType);
        else if (data != null) intent.setData(data);
        else if (!mimeType.isEmpty()) intent.setType(mimeType);
    }

    private static void decodeCategories(Intent intent, Bundle source) {
        ArrayList<String> categories = source.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES);
        if (categories == null) return;
        for (String category : categories) {
            String normalized = value(category);
            if (!normalized.isEmpty()) intent.addCategory(normalized);
        }
    }

    private static void decodeExtras(Intent intent, Bundle source) {
        Bundle extras = source.getBundle(RuntimeKeys.INTENT_EXTRAS);
        if (extras == null) return;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null) extras.setClassLoader(loader);
        intent.putExtras(new Bundle(extras));
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static int uriPort(Uri data) {
        if (data == null) return -1;
        try {
            java.lang.reflect.Method method = data.getClass().getMethod("getPort");
            Object value = method.invoke(data);
            if (value instanceof Number) {
                int port = ((Number) value).intValue();
                if (port >= -1 && port <= 65535) return port;
            }
        } catch (Throwable ignored) {
            // Compact API stubs may not expose Uri.getPort; the URI text is still authoritative.
        }
        try {
            return java.net.URI.create(data.toString()).getPort();
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }
}
