package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.provider.BrokerProviderRuntime;

/** Validates a Provider caller's declared permission against the live prepared Guest revision. */
final class ProviderDeclaredPermissionAuthorizer {
    interface SessionLookup { GuestSession find(String sessionId, long generation); }

    private ProviderDeclaredPermissionAuthorizer() { }

    static boolean allows(Bundle request, String targetPackage, int targetUser, String permission,
                          SessionLookup sessions, BrokerStateStore brokerState) {
        if (permission == null || permission.trim().isEmpty()) return true;
        if (request == null) return false;
        String callerPackage = request.getString(RuntimeKeys.CALLER_PACKAGE_NAME, targetPackage);
        int callerUser = request.getInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, targetUser);
        GuestSession caller = sessions.find(request.getString(RuntimeKeys.CALLER_SESSION_ID, ""),
                request.getLong(RuntimeKeys.CALLER_GENERATION, -1L));
        if (!matchesCaller(caller, callerPackage, callerUser)) return false;
        Bundle prepared = brokerState.prepared(processKey(caller));
        if (prepared != null) {
            prepared.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
        }
        VirtualPackageStateSnapshot state = prepared == null
                ? null : prepared.getParcelable(RuntimeKeys.PACKAGE_STATE);
        return hasEffectivePermission(state, permission);
    }

    private static boolean matchesCaller(GuestSession caller, String packageName, int userId) {
        return caller != null && caller.packageName().equals(packageName)
                && caller.virtualUserId() == userId;
    }

    private static String processKey(GuestSession caller) {
        return BrokerProviderRuntime.instanceId(caller.packageName(), caller.virtualUserId())
                + ":" + caller.processName();
    }

    private static boolean hasEffectivePermission(VirtualPackageStateSnapshot state, String permission) {
        if (state == null) return false;
        for (VirtualPermissionSnapshot snapshot : state.permissions()) {
            if (permission.equals(snapshot.name()) && snapshot.effectiveGranted()) return true;
        }
        return false;
    }
}
