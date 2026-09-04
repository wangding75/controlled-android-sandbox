package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualDeviceIdentitySnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Reversible process-local projection of android.os.Build identity fields. */
public final class BuildIdentityHook implements AutoCloseable {
    private final List<RestoredField> fields;

    private BuildIdentityHook(List<RestoredField> fields) { this.fields = fields; }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        // Android 17 exposes the Build identity surface as static-final constants that are
        // treated as immutable by ART.  Reflection writes here are both unsupported and can
        // poison the process if the runtime changes its constant-folding policy.  The guest
        // identity contract is already projected through Context/ApplicationInfo, package
        // queries, identifier services, and the framework service proxies, so this optional
        // process-global projection is deliberately disabled on API37+.
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            android.util.Log.i("CS_BUILD_IDENTITY",
                    "BUILD_STATIC_FINAL_PROJECTION_DISABLED api="
                            + android.os.Build.VERSION.SDK_INT);
            return () -> { };
        }
        VirtualDeviceIdentitySnapshot profile = identity.virtualServices().deviceServiceProfile().identity();
        VirtualOemProfileSnapshot oem = null;
        try { oem = identity.virtualServices().compatibilityProfile().oem(); } catch (IllegalStateException ignored) { }
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())
                && (oem == null || VirtualLocationProfileSnapshot.MODE_HOST.equals(oem.mode()))) return () -> { };
        Class<?> build = Class.forName("android.os.Build");
        boolean blocked = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile.mode());
        List<RestoredField> changed = new ArrayList<>();
        try {
            setIfPresent(build, "BRAND", blocked ? "" : profile.brand(), changed);
            setIfPresent(build, "MANUFACTURER", blocked ? "" : profile.manufacturer(), changed);
            setIfPresent(build, "MODEL", blocked ? "" : profile.model(), changed);
            setIfPresent(build, "DEVICE", blocked ? "" : profile.device(), changed);
            setIfPresent(build, "PRODUCT", blocked ? "" : profile.product(), changed);
            setIfPresent(build, "FINGERPRINT", blocked ? "" : profile.fingerprint(), changed);
            setIfPresent(build, "BOARD", blocked ? "" : profile.board(), changed);
            setIfPresent(build, "HARDWARE", blocked ? "" : profile.hardware(), changed);
            setIfPresent(build, "SERIAL", blocked ? "" : profile.serial(), changed);
            if (oem != null && !VirtualLocationProfileSnapshot.MODE_HOST.equals(oem.mode())) {
                boolean oemBlocked = VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(oem.mode());
                setIfPresent(build, "DISPLAY", oemBlocked ? "" : value(oem, "ro.build.display.id", "controlled-sandbox"), changed);
                setIfPresent(build, "ID", oemBlocked ? "" : value(oem, "ro.build.id", "CS1"), changed);
                setIfPresent(build, "TYPE", oemBlocked ? "user" : value(oem, "ro.build.type", "user"), changed);
                setIfPresent(build, "TAGS", oemBlocked ? "release-keys" : value(oem, "ro.build.tags", "release-keys"), changed);
                setIfPresent(build, "USER", oemBlocked ? "android-build" : value(oem, "ro.build.user", "sandbox"), changed);
                setIfPresent(build, "HOST", oemBlocked ? "localhost" : value(oem, "ro.build.host", "sandbox-builder"), changed);
            }
            if (changed.isEmpty()) {
                throw new IllegalStateException("No writable android.os.Build identity fields");
            }
            return new BuildIdentityHook(changed);
        } catch (Throwable error) {
            restoreAll(changed);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("Cannot project android.os.Build identity", error);
        }
    }

    private static String value(VirtualOemProfileSnapshot profile, String key, String fallback) {
        String value = profile.property(key); return value == null || value.isEmpty() ? fallback : value;
    }

    private static void setIfPresent(Class<?> owner, String name, String value,
            List<RestoredField> changed) {
        try {
            Field field = ReflectiveServiceHook.findField(owner, name);
            field.setAccessible(true);
            Object original = field.get(null);
            field.set(null, value);
            changed.add(new RestoredField(field, original));
        } catch (NoSuchFieldException ignored) {
            // Field availability varies by API level.
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot project Build." + name, error);
        }
    }

    @Override public void close() {
        restoreAll(fields);
        fields.clear();
    }

    private static void restoreAll(List<RestoredField> changed) {
        for (int index = changed.size() - 1; index >= 0; index--) changed.get(index).restore();
    }

    private record RestoredField(Field field, Object original) {
        void restore() { try { field.set(null, original); } catch (Throwable ignored) { } }
    }
}
