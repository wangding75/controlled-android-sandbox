package com.warden.controlledsandbox.runtime.guest;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.ContentProvider;
import android.os.Build;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * API 31+ bridge for the hidden framework state used by AttributionSource and ContentProvider.
 *
 * <p>The Guest runtime must not let the physical Host package become the caller visible to a
 * Guest Provider.  Framework transport normally installs this state before entering a
 * ContentProvider, while the Guest/Broker transport invokes the Provider object directly and
 * therefore has to bracket the invocation explicitly.</p>
 */
@SuppressLint("BlockedPrivateApi")
final class GuestAttributionSourceBridge {
    private GuestAttributionSourceBridge() { }

    static AttributionSource create(int virtualUid, String packageName) {
        if (Build.VERSION.SDK_INT < 31) return null;
        if (virtualUid < 0 || packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("GUEST_ATTRIBUTION_IDENTITY_INVALID");
        }
        try {
            Class<?> type = Class.forName("android.content.AttributionSource");
            Constructor<?> constructor = type.getDeclaredConstructor(
                    int.class, String.class, String.class);
            constructor.setAccessible(true);
            return (AttributionSource) constructor.newInstance(
                    virtualUid, packageName, null);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("GUEST_ATTRIBUTION_SOURCE_CONSTRUCTION_FAILED", error);
        }
    }

    static Object install(ContentProvider provider, AttributionSource source) {
        if (Build.VERSION.SDK_INT < 31) return null;
        if (provider == null || source == null) {
            throw new IllegalArgumentException("GUEST_ATTRIBUTION_PROVIDER_OR_SOURCE_MISSING");
        }
        try {
            Method setter = ContentProvider.class.getDeclaredMethod(
                    "setCallingAttributionSource", AttributionSource.class);
            setter.setAccessible(true);
            return setter.invoke(provider, new Object[] {source});
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("GUEST_PROVIDER_ATTRIBUTION_INSTALL_FAILED", error);
        }
    }

    static void restore(ContentProvider provider, Object previous) {
        if (Build.VERSION.SDK_INT < 31) return;
        if (provider == null) throw new IllegalArgumentException("provider is required");
        try {
            Method setter = ContentProvider.class.getDeclaredMethod(
                    "setCallingAttributionSource", AttributionSource.class);
            setter.setAccessible(true);
            setter.invoke(provider, new Object[] {previous});
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("GUEST_PROVIDER_ATTRIBUTION_RESTORE_FAILED", error);
        }
    }

    private static void rethrowFatal(Throwable error) {
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
        com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
    }
}
