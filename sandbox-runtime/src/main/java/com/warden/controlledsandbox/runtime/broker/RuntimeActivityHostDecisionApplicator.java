package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;

/**
 * Applies a virtual task reuse decision to the live Host trampoline instead of asking
 * Android to match by physical component class.
 */
final class RuntimeActivityHostDecisionApplicator {
    private RuntimeActivityHostDecisionApplicator() { }

    static boolean isReuseAction(String action) {
        return "DELIVERED_NEW_INTENT".equals(action)
                || "CLEARED_TOP".equals(action)
                || "REORDERED_TO_FRONT".equals(action);
    }

    static boolean apply(RuntimeBrokerService owner, GuestSession session, Bundle transaction) {
        if (owner == null || session == null || transaction == null) return false;
        String action = transaction.getString(RuntimeKeys.ACTIVITY_ACTION, "");
        if (!isReuseAction(action)) return false;
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, session.virtualUserId());
        request.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        request.putLong(RuntimeKeys.GENERATION, session.generation());
        request.putString(RuntimeKeys.ACTIVITY_TOKEN,
                transaction.getString(RuntimeKeys.ACTIVITY_TOKEN, ""));
        request.putString(RuntimeKeys.ROUTE_TOKEN,
                transaction.getString(RuntimeKeys.ROUTE_TOKEN, ""));
        request.putString(RuntimeKeys.ACTIVITY_ACTION, action);
        ArrayList<String> removed = transaction.getStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS);
        if (removed != null) request.putStringArrayList(RuntimeKeys.REMOVED_ACTIVITY_TOKENS, removed);
        try {
            Bundle applied = owner.callGuest(session.processSlot(), guest ->
                    RuntimeBrokerService.guestOperation(guest,
                            RuntimeOperationRequest.APPLY_ACTIVITY_HOST_DECISION, request));
            return applied != null && "APPLIED".equals(applied.getString(RuntimeKeys.STATUS));
        } catch (Exception ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            return false;
        }
    }
}
