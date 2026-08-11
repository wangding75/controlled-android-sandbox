package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.DeviceServiceBindingRegistry;
import com.warden.controlledsandbox.framework.core.GuestSystemServiceOverrideRegistry;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Version-tolerant TelephonyManager Binder hooks. */
public final class TelephonyServiceHook {
    private TelephonyServiceHook() { }
    public static AutoCloseable installTelephony(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "telephony");
    }

    /**
     * Radio-less images can return null from Context#getSystemService("phone") even after a
     * virtual Binder exists. Materialize the public manager against the Guest Context so normal
     * TelephonyManager calls reach the generic virtual telephony boundary.
     */
    public static AutoCloseable installGuestManager(Context guestContext,
                                                    GuestIdentity identity) throws Exception {
        Constructor<?> constructor = android.telephony.TelephonyManager.class
                .getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        android.telephony.TelephonyManager delegate =
                (android.telephony.TelephonyManager) constructor.newInstance(guestContext);
        Object manager = android.telephony.ControlledTelephonyManager.create(
                delegate, () -> identity.virtualServices().deviceServiceProfile().telephony());
        List<AutoCloseable> hooks = new ArrayList<>();
        hooks.add(GuestSystemServiceOverrideRegistry.install(
                guestContext, Context.TELEPHONY_SERVICE, manager));
        // TelephonyManager on the target image caches the Binder interfaces in static fields.
        // A radio-less Host initializes those fields to null before the synthetic ServiceManager
        // aliases are installed, so public read APIs otherwise return null without invoking the
        // controlled proxy. Bind only descriptor-matching fields from the virtual aliases.
        hooks.add(bindStaticInterface("android.telephony.TelephonyManager",
                "com.android.internal.telephony.ITelephony", "phone"));
        hooks.add(bindStaticInterface("android.telephony.TelephonyManager",
                "com.android.internal.telephony.IPhoneSubInfo", "iphonesubinfo"));
        hooks.add(bindStaticInterface("android.telephony.SubscriptionManager",
                "com.android.internal.telephony.ISub", "isub"));
        return ReflectiveServiceHook.compose(hooks.toArray(new AutoCloseable[0]));
    }

    private static AutoCloseable bindStaticInterface(String ownerName, String descriptor,
                                                     String serviceName) throws Exception {
        Class<?> owner = Class.forName(ownerName);
        Class<?> contract = Class.forName(descriptor);
        Class<?> stub = Class.forName(descriptor + "$Stub");
        Field target = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !field.getType().getName().equals(descriptor)) continue;
            target = field;
            break;
        }
        if (target == null) {
            android.util.Log.i("CS_TELEPHONY_CACHE", "NO_STATIC_FIELD owner=" + ownerName
                    + " descriptor=" + descriptor);
            return () -> { };
        }
        target.setAccessible(true);
        Method getService = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object value = getService.invoke(null, serviceName);
        if (!(value instanceof android.os.IBinder binder)) {
            // Some API images omit subscriber-info or subscription services entirely.  Their
            // absence is a capability boundary, not a failure of the Guest telephony manager
            // or of the generic phone Binder.  Keep the optional cache slot untouched and let
            // the public API return its normal unavailable value.
            android.util.Log.i("CS_TELEPHONY_CACHE", "UNAVAILABLE service=" + serviceName
                    + " descriptor=" + descriptor);
            return () -> { };
        }
        String actual = binder.getInterfaceDescriptor();
        if (!descriptor.equals(actual)) {
            throw new IllegalStateException("VIRTUAL_TELEPHONY_DESCRIPTOR_MISMATCH:" + serviceName
                    + ":" + actual);
        }
        Method asInterface = stub.getDeclaredMethod("asInterface", android.os.IBinder.class);
        asInterface.setAccessible(true);
        Object replacement = asInterface.invoke(null, binder);
        if (replacement == null || !contract.isInstance(replacement)) {
            throw new IllegalStateException("VIRTUAL_TELEPHONY_INTERFACE_UNAVAILABLE:" + descriptor);
        }
        Object original = target.get(null);
        target.set(null, replacement);
        android.util.Log.i("CS_TELEPHONY_CACHE", "BOUND owner=" + ownerName
                + " field=" + target.getName() + " service=" + serviceName);
        Field finalTarget = target;
        return () -> {
            synchronized (finalTarget) {
                if (finalTarget.get(null) == replacement) finalTarget.set(null, original);
            }
        };
    }
    public static AutoCloseable installSubscriberInfo(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "phoneSubInfo");
    }
    public static AutoCloseable installRegistry(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "telephonyRegistry");
    }
    public static AutoCloseable installSubscription(Context context, GuestIdentity identity) throws Exception {
        return DeviceServiceBindingRegistry.install(context, identity, "subscription");
    }
}
