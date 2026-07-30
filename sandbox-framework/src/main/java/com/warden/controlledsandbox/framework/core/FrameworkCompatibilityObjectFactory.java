package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import com.warden.controlledsandbox.contract.VirtualWebViewProfileSnapshot;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Builds WebView framework response objects without retaining Host provider identity. */
final class FrameworkCompatibilityObjectFactory {
    private FrameworkCompatibilityObjectFactory() { }

    static Object webViewValue(Class<?> type, VirtualWebViewProfileSnapshot profile) {
        if (type == null || type == Object.class || type == PackageInfo.class) return webViewPackageInfo(profile);
        if (type == String.class) return profile.providerPackage();
        Object value = instantiate(type);
        if (value == null) return null;
        setIfPresent(value, "packageName", profile.providerPackage());
        setIfPresent(value, "versionName", profile.providerVersion());
        setNumericIfPresent(value, "status", 0);
        Field packageInfo = field(type, "packageInfo");
        if (packageInfo != null) {
            Object nested = packageInfo.getType() == PackageInfo.class
                    ? webViewPackageInfo(profile) : webViewValue(packageInfo.getType(), profile);
            set(value, packageInfo, nested);
        }
        Field applicationInfo = field(type, "applicationInfo");
        if (applicationInfo != null && ApplicationInfo.class.isAssignableFrom(applicationInfo.getType())) {
            ApplicationInfo app = new ApplicationInfo();
            app.packageName = profile.providerPackage();
            set(value, applicationInfo, app);
        }
        return value;
    }

    static PackageInfo webViewPackageInfo(VirtualWebViewProfileSnapshot profile) {
        PackageInfo info = new PackageInfo();
        info.packageName = profile.providerPackage();
        info.versionName = profile.providerVersion();
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = profile.providerPackage();
        return info;
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void setIfPresent(Object value, String name, Object fieldValue) {
        Field field = field(value.getClass(), name);
        if (field != null) set(value, field, fieldValue);
    }

    private static void setNumericIfPresent(Object value, String name, int fieldValue) {
        Field field = field(value.getClass(), name);
        if (field == null) return;
        Class<?> type = field.getType();
        if (type == int.class || type == Integer.class) set(value, field, fieldValue);
        else if (type == long.class || type == Long.class) set(value, field, (long) fieldValue);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = ReflectiveServiceHook.findField(type, name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void set(Object value, Field field, Object fieldValue) {
        try { field.set(value, fieldValue); } catch (IllegalAccessException | RuntimeException ignored) { }
    }
}
