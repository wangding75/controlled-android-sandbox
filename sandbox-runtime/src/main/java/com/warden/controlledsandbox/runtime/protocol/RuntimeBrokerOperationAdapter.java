package com.warden.controlledsandbox.runtime.protocol;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;

/** Compatibility adapter that dispatches typed Broker operations onto legacy entry points. */
public final class RuntimeBrokerOperationAdapter {
    private RuntimeBrokerOperationAdapter() { }

    public static RuntimeOperationResult execute(
            IRuntimeBroker broker,
            RuntimeOperationRequest request) {
        if (broker == null) throw new IllegalArgumentException("broker is required");
        if (request == null) throw new IllegalArgumentException("request is required");
        try {
            Bundle payload = request.payload();
            Bundle result = switch (request.operation()) {
                case RuntimeOperationRequest.PREPARE_GUEST -> broker.prepareGuest(payload);
                case RuntimeOperationRequest.LAUNCH_ACTIVITY -> broker.launchActivity(payload);
                case RuntimeOperationRequest.INVOKE_COMPONENT -> broker.invokeComponent(payload);
                case RuntimeOperationRequest.GRANT_URI_PERMISSION -> broker.grantUriPermission(payload);
                case RuntimeOperationRequest.REVOKE_URI_PERMISSION -> broker.revokeUriPermission(payload);
                case RuntimeOperationRequest.CONSUME_ROUTE -> broker.consumeRoute(
                        payload.getString(RuntimeKeys.ROUTE_TOKEN, ""),
                        request.sessionId(),
                        request.generation());
                case RuntimeOperationRequest.ACTIVITY_EVENT -> broker.activityEvent(payload);
                case RuntimeOperationRequest.SESSION_STATUS -> broker.sessionStatus(
                        request.packageName(), request.virtualUserId());
                default -> throw new IllegalArgumentException(
                        "unsupported broker operation: " + request.operation());
            };
            return RuntimeOperationTransport.fromLegacy(request, result);
        } catch (Throwable error) {
            return RuntimeOperationTransport.failure(request, error);
        }
    }
}
