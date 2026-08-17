package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.warden.controlledsandbox.framework.activity.SavedActivityState;

/**
 * Guest-side codec for the real ActivityThread saved-state contract.
 *
 * <p>Only opaque bytes cross the Broker boundary. This is important for both class-loader
 * correctness and isolation: the Broker must not instantiate a Guest Parcelable while it is
 * persisting or fencing a process generation.</p>
 */
final class SavedStateWireCodec {
    private SavedStateWireCodec() { }

    static byte[] marshall(Parcelable value, String label) {
        if (value == null) return new byte[0];
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            byte[] payload = parcel.marshall();
            if (payload.length > SavedActivityState.MAX_PAYLOAD_BYTES) {
                android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                        label + " exceeds bounded payload: " + payload.length);
                return new byte[0];
            }
            return payload;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", label + " marshal failed", error);
            return new byte[0];
        } finally {
            parcel.recycle();
        }
    }

    static Bundle unmarshallBundle(byte[] payload, ClassLoader classLoader) {
        if (payload == null || payload.length == 0) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(payload, 0, payload.length);
            parcel.setDataPosition(0);
            Bundle value = createFromParcel(Bundle.class, parcel);
            if (value != null && classLoader != null) value.setClassLoader(classLoader);
            return value;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "saved Bundle restore failed", error);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    static PersistableBundle unmarshallPersistableBundle(
            byte[] payload, ClassLoader classLoader) {
        if (payload == null || payload.length == 0) return null;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(payload, 0, payload.length);
            parcel.setDataPosition(0);
            PersistableBundle value = createFromParcel(PersistableBundle.class, parcel);
            return value;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                    "persistable saved Bundle restore failed", error);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    static Bundle merge(Bundle frameworkState, Bundle guestState, ClassLoader classLoader) {
        if (frameworkState == null && guestState == null) return null;
        Bundle merged = frameworkState == null ? new Bundle() : new Bundle(frameworkState);
        if (guestState != null) merged.putAll(guestState);
        if (classLoader != null) merged.setClassLoader(classLoader);
        return merged;
    }

    static PersistableBundle mergePersistable(
            PersistableBundle frameworkState,
            PersistableBundle guestState,
            ClassLoader classLoader) {
        if (frameworkState == null && guestState == null) return null;
        PersistableBundle merged = new PersistableBundle();
        if (frameworkState != null) merged.putAll(frameworkState);
        if (guestState != null) merged.putAll(guestState);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static <T> T createFromParcel(Class<?> type, Parcel parcel) throws Exception {
        java.lang.reflect.Field field = type.getField("CREATOR");
        field.setAccessible(true);
        Parcelable.Creator<?> creator = (Parcelable.Creator<?>) field.get(null);
        return (T) creator.createFromParcel(parcel);
    }
}
