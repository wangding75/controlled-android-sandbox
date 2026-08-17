package com.warden.controlledsandbox.runtime.broker;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;

/**
 * Broker-owned IIntentSender transport for a durable virtual PendingIntent.
 *
 * <p>The Binder is intentionally created in the Broker process rather than in a Guest process.
 * Android system services may retain and invoke an intent sender after the creator process has
 * died; the relay therefore carries only the durable token and delegates each send back to the
 * Broker, which can fence the old generation and cold-bind a replacement Guest.</p>
 */
final class RuntimePendingIntentSender extends Binder {
    private static final int INTERFACE_TRANSACTION = 0x5f4e5446;
    private static final int SEND_TRANSACTION = 1;

    @FunctionalInterface
    interface Dispatcher {
        DispatchResult send(String tokenId, int resultCode, Intent fillIn,
                            int flagsMask, int flagsValues, String permission) throws Exception;
    }

    record DispatchResult(int resultCode, Intent deliveredIntent) { }

    private final String tokenId;
    private final String descriptor;
    private final Dispatcher dispatcher;

    RuntimePendingIntentSender(String tokenId, String descriptor, Dispatcher dispatcher) {
        if (tokenId == null || tokenId.trim().isEmpty()) {
            throw new IllegalArgumentException("pending intent token is required");
        }
        this.tokenId = tokenId.trim();
        this.descriptor = descriptor == null || descriptor.trim().isEmpty()
                ? "android.content.IIntentSender" : descriptor.trim();
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        // Force generated IIntentSender.Stub.asInterface() to use the Binder proxy even when a
        // caller happens to be in the Broker process. The system-facing object must always use
        // the same transact contract.
        attachInterface(null, this.descriptor);
    }

    String tokenId() { return tokenId; }

    @Override public String toString() {
        return "RuntimePendingIntentSender[" + tokenId + "]";
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
        if (code != SEND_TRANSACTION) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(descriptor);
        if (dataAvail(data) <= 0) throw new RemoteException("IIntentSender.send payload missing");
        int resultCode = data.readInt();
        Intent fillIn = data.readInt() != 0 ? readIntent(data) : null;
        data.readString(); // resolved type
        readStrongBinder(data); // whitelist token
        IBinder finishedReceiver = readStrongBinder(data);
        String permission = data.readString();
        // API 26+ carries options after the permission. Options do not affect virtual identity,
        // but must still be consumed so the Binder cursor remains aligned.
        if (dataAvail(data) > 0) readBundle(data);
        try {
            DispatchResult delivery = dispatcher.send(tokenId, resultCode, fillIn, 0, 0, permission);
            // PendingIntent.send() is allowed to supply an IIntentReceiver even when the sender
            // itself is retained by SystemUI/AlarmManager. The receiver is a completion channel,
            // not a Guest capability; notify it from the Broker-owned relay after the durable
            // dispatch succeeds so the callback remains valid across Guest process death.
            notifyFinishedReceiver(finishedReceiver, delivery.deliveredIntent(), delivery.resultCode());
            reply.writeNoException();
            reply.writeInt(delivery.resultCode());
            return true;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            writeException(reply, error instanceof Exception
                    ? (Exception) error : new RuntimeException(error));
            return true;
        }
    }

    private static int dataAvail(Parcel parcel) {
        try {
            Method method = Parcel.class.getMethod("dataAvail");
            return ((Number) method.invoke(parcel)).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static IBinder readStrongBinder(Parcel parcel) {
        try {
            Object value = Parcel.class.getMethod("readStrongBinder").invoke(parcel);
            return value instanceof IBinder ? (IBinder) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Intent readIntent(Parcel parcel) throws RemoteException {
        try {
            java.lang.reflect.Field field = Intent.class.getField("CREATOR");
            Object creator = field.get(null);
            Method method = creator.getClass().getMethod("createFromParcel", Parcel.class);
            return (Intent) method.invoke(creator, parcel);
        } catch (Throwable error) {
            throw new RemoteException("IIntentSender Intent decode failed: " + error);
        }
    }

    private static void readBundle(Parcel parcel) {
        try {
            Parcel.class.getMethod("readBundle", ClassLoader.class)
                    .invoke(parcel, RuntimePendingIntentSender.class.getClassLoader());
        } catch (Throwable ignored) {
            // Older API adapters do not expose Bundle options; the required send fields have
            // already been consumed and remain ABI-compatible.
        }
    }

    /**
     * Delivers the hidden IIntentReceiver callback without linking the Broker to a platform API
     * level. The method gained parameters over Android releases, so map the stable semantic
     * types reflectively and default new user/ordering fields to the virtual defaults.
     */
    private static void notifyFinishedReceiver(IBinder binder, Intent delivered, int resultCode) {
        if (binder == null) return;
        try {
            Class<?> stub = Class.forName("android.content.IIntentReceiver$Stub");
            Method asInterface = findAsInterface(stub);
            if (asInterface == null) throw new NoSuchMethodException("IIntentReceiver.asInterface");
            asInterface.setAccessible(true);
            Object receiver = asInterface.invoke(null, binder);
            if (receiver == null) return;
            Method callback = null;
            for (Method candidate : receiver.getClass().getMethods()) {
                if ("performReceive".equals(candidate.getName())) {
                    callback = candidate;
                    break;
                }
            }
            if (callback == null) return;
            callback.setAccessible(true);
            Class<?>[] types = callback.getParameterTypes();
            Object[] args = new Object[types.length];
            int intIndex = 0;
            for (int index = 0; index < types.length; index++) {
                Class<?> type = types[index];
                if (Intent.class.isAssignableFrom(type)) args[index] = delivered;
                else if (type == int.class || type == Integer.class) {
                    args[index] = intIndex++ == 0 ? resultCode : 0;
                } else if (type == boolean.class || type == Boolean.class) {
                    args[index] = false;
                } else if (type == String.class || Bundle.class.isAssignableFrom(type)
                        || IBinder.class.isAssignableFrom(type)) {
                    args[index] = null;
                } else if (type == long.class || type == Long.class) {
                    args[index] = 0L;
                } else if (type == float.class || type == Float.class) {
                    args[index] = 0F;
                } else if (type == double.class || type == Double.class) {
                    args[index] = 0D;
                } else if (type == byte.class || type == Byte.class) {
                    args[index] = (byte) 0;
                } else if (type == short.class || type == Short.class) {
                    args[index] = (short) 0;
                } else if (type == char.class || type == Character.class) {
                    args[index] = (char) 0;
                }
            }
            callback.invoke(receiver, args);
            return;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            // Some Android builds expose the generated IIntentReceiver transport without the
            // public-looking Stub.asInterface helper. Fall back to the stable Binder transaction
            // ABI before giving up; this is also what the generated Proxy emits.
            if (rawNotifyFinishedReceiver(binder, delivered, resultCode)) return;
            // A completion receiver is owned by the caller/system process. Its death must not
            // turn a successfully delivered PendingIntent into a failed virtual transaction.
            android.util.Log.w("CS_PENDING_INTENT", "finished receiver callback unavailable", error);
        }
    }

    private static Method findAsInterface(Class<?> stub) {
        Class<?> cursor = stub;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if ("asInterface".equals(method.getName())
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 1
                        && IBinder.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static boolean rawNotifyFinishedReceiver(IBinder binder, Intent delivered,
                                                       int resultCode) {
        Parcel data = Parcel.obtain();
        try {
            Method token = Parcel.class.getMethod("writeInterfaceToken", String.class);
            token.invoke(data, "android.content.IIntentReceiver");
            data.writeInt(delivered == null ? 0 : 1);
            if (delivered != null) {
                Intent.class.getMethod("writeToParcel", Parcel.class, int.class)
                        .invoke(delivered, data, 0);
            }
            data.writeInt(resultCode);
            data.writeString(null);
            data.writeBundle(null);
            data.writeInt(0); // ordered
            data.writeInt(0); // sticky
            data.writeInt(0); // virtual sending user
            return binder.transact(SEND_TRANSACTION, data, null, 1 /* FLAG_ONEWAY */);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        } finally {
            data.recycle();
        }
    }

    private static void writeException(Parcel parcel, Exception error) {
        try {
            Parcel.class.getMethod("writeException", Exception.class).invoke(parcel, error);
        } catch (Throwable ignored) {
            parcel.writeNoException();
        }
    }
}
