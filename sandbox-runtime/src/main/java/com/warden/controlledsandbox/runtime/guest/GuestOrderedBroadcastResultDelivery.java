package com.warden.controlledsandbox.runtime.guest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Delivers final ordered-broadcast state through the platform BroadcastReceiver API surface. */
final class GuestOrderedBroadcastResultDelivery {
    private GuestOrderedBroadcastResultDelivery() { }

    static void deliver(Context context, Intent intent, BroadcastReceiver receiver,
            int resultCode, String resultData, Bundle resultExtras, boolean aborted) {
        if (receiver == null) return;
        try {
            Class<?> pendingType = Class.forName("android.content.BroadcastReceiver$PendingResult");
            Object pending = createPendingResult(pendingType, context.getPackageName(), resultCode,
                    resultData, resultExtras);
            Method setPending = BroadcastReceiver.class.getMethod("setPendingResult", pendingType);
            setPending.invoke(receiver, pending);
            if (aborted) receiver.abortBroadcast();
            receiver.onReceive(context, intent);
            Object current = BroadcastReceiver.class.getMethod("getPendingResult").invoke(receiver);
            if (current != null) {
                try { current.getClass().getMethod("finish").invoke(current); }
                finally { setPending.invoke(receiver, new Object[]{null}); }
            }
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ORDERED_RESULT_RECEIVER_DELIVERY_FAILED", error);
        }
    }

    private static Object createPendingResult(Class<?> pendingType, String packageName,
            int code, String data, Bundle extras) throws Exception {
        Bundle values = extras == null ? new Bundle() : new Bundle(extras);
        for (Constructor<?> constructor : pendingType.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            constructor.setAccessible(true);
            if (types.length == 9) {
                return constructor.newInstance(code, data, values, 0, true, false,
                        new Binder(), 0, 0);
            }
            if (types.length == 12) {
                return constructor.newInstance(code, data, values, 0, true, false, false,
                        new Binder(), 0, 0, -1, packageName);
            }
        }
        throw new IllegalStateException("ORDERED_PENDING_RESULT_CONSTRUCTOR_UNAVAILABLE");
    }
}
