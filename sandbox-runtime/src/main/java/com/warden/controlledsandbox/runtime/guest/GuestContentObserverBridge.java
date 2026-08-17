package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.framework.identity.ContentObserverBridge;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Session-bound Guest-to-Broker transport for ContentObserver registrations. */
final class GuestContentObserverBridge implements ContentObserverBridge {
    private final GuestRuntimeBrokerBridge bridge;

    GuestContentObserverBridge(GuestPackageSpec spec, GuestMainThreadDispatcher mainThread) {
        this.bridge = new GuestRuntimeBrokerBridge(spec, mainThread);
    }

    @Override public Registration register(String targetPackage, int targetVirtualUserId,
                                           String targetProcessName, String authority,
                                           String componentClass, String uri,
                                           boolean notifyForDescendants,
                                           boolean deliverSelfNotifications, IBinder callback) {
        Bundle request = request(ComponentOperations.PROVIDER_OBSERVER_REGISTER,
                targetPackage, targetVirtualUserId, targetProcessName, authority, componentClass, uri);
        request.putBoolean(RuntimeKeys.OBSERVER_NOTIFY_DESCENDANTS, notifyForDescendants);
        request.putBoolean(RuntimeKeys.OBSERVER_DELIVER_SELF, deliverSelfNotifications);
        request.putBinder(RuntimeKeys.OBSERVER_CALLBACK, callback);
        Bundle result = bridge.invokeComponent(request);
        String id = result.getString(RuntimeKeys.OBSERVER_ID, "");
        if (id.trim().isEmpty()) throw new IllegalStateException("PROVIDER_OBSERVER_ID_MISSING");
        return new Registration(id);
    }

    @Override public void unregister(String registrationId) {
        if (registrationId == null || registrationId.trim().isEmpty()) return;
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_OBSERVER_UNREGISTER);
        request.putString(RuntimeKeys.OBSERVER_ID, registrationId);
        bridge.invokeComponent(request);
    }

    @Override public void notifyChange(String targetPackage, int targetVirtualUserId,
                                       String targetProcessName, String authority,
                                       String componentClass, String uri, int flags) {
        Bundle request = request(ComponentOperations.PROVIDER_NOTIFY_CHANGE,
                targetPackage, targetVirtualUserId, targetProcessName, authority, componentClass, uri);
        request.putInt(RuntimeKeys.OBSERVER_CHANGE_FLAGS, flags);
        bridge.invokeComponent(request);
    }

    private Bundle request(String operation, String targetPackage, int targetVirtualUserId,
                           String targetProcessName, String authority, String componentClass,
                           String uri) {
        Bundle request = bridge.baseRequest();
        request.putString(ComponentOperations.OPERATION, operation);
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, required(targetPackage, "targetPackage"));
        request.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, targetVirtualUserId);
        request.putString(RuntimeKeys.PROCESS_NAME, required(targetProcessName, "targetProcessName"));
        request.putString(ComponentOperations.AUTHORITY, required(authority, "authority"));
        request.putString(RuntimeKeys.COMPONENT_CLASS, required(componentClass, "componentClass"));
        request.putString(RuntimeKeys.URI, required(uri, "uri"));
        return request;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }
}
