package com.warden.controlledsandbox.runtime.protocol;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Set;

/** Isolated Intent envelope that cannot overwrite Broker control fields. */
public final class RuntimeIntentWireCodec {
    private RuntimeIntentWireCodec() { }

    public static void encode(Bundle target, Intent intent) {
        if (target == null || intent == null) return;
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
        decodeIdentity(intent, source);
        decodeActionAndFlags(intent, source);
        decodeData(intent, source);
        decodeCategories(intent, source);
        decodeExtras(intent, source);
        return intent;
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
        intent.setFlags(source.getInt(RuntimeKeys.ACTIVITY_FLAGS, 0));
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
}
