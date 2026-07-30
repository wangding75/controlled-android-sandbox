package com.warden.controlledsandbox.framework.service;

import android.content.Context;
import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Version-tolerant TelephonyManager Binder hooks. */
public final class TelephonyServiceHook {
    private TelephonyServiceHook() { }
    public static AutoCloseable installTelephony(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "phone", "telephony", identity,
                "mITelephony", "sITelephony", "mTelephonyService");
    }
    public static AutoCloseable installSubscriberInfo(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "phone", "phonesubinfo", identity,
                "mSubscriberInfo", "mIPhoneSubInfo", "sIPhoneSubInfo");
    }
    public static AutoCloseable installRegistry(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "telephony_registry",
                "telephonyregistry", identity, "mTelephonyRegistry", "sTelephonyRegistry", "mService");
    }
    public static AutoCloseable installSubscription(Context context, GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.managerFieldCandidates(context, "telephony_subscription_service",
                "subscription", identity, "mService", "mSubscriptionManagerService", "mISub", "sISub");
    }
}
