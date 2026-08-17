package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

/** Regression tests for Host/Companion Provider capability translation and generation fencing. */
public final class RuntimeCrossAbiProviderRelaySelfTest {
    private RuntimeCrossAbiProviderRelaySelfTest() { }

    public static void main(String[] args) {
        translatesAndConsumesOpaqueResources();
        rejectsIdentitySubstitution();
        invalidatesCallerTargetAndRemoteGeneration();
        System.out.println("PASS cross ABI provider relay self-test");
    }

    private static void translatesAndConsumesOpaqueResources() {
        RuntimeCrossAbiProviderRelay relay = new RuntimeCrossAbiProviderRelay();
        Bundle targetRequest = request();

        Bundle cursorResult = new Bundle();
        cursorResult.putString(RuntimeKeys.CURSOR_TOKEN, "remote-cursor");
        Bundle cursor = relay.exposeCursor(cursorResult, targetRequest);
        String localCursor = cursor.getString(RuntimeKeys.CURSOR_TOKEN);
        require(localCursor != null && !localCursor.equals("remote-cursor"),
                "cursor must be replaced with a Host-owned opaque token");

        Bundle pageRequest = new Bundle(targetRequest);
        pageRequest.putString(ComponentOperations.OPERATION, ComponentOperations.PROVIDER_CURSOR_PAGE);
        pageRequest.putString(RuntimeKeys.CURSOR_TOKEN, localCursor);
        RuntimeCrossAbiProviderRelay.RemoteRequest remotePage = relay.prepareExisting(
                pageRequest, ComponentOperations.PROVIDER_CURSOR_PAGE);
        require("remote-cursor".equals(remotePage.request().getString(RuntimeKeys.CURSOR_TOKEN)),
                "cursor page must translate back to the Companion token");
        relay.finishCursor(new Bundle(), pageRequest);
        require(relay.size() == 0, "terminal cursor close must remove the Host capability");

        Bundle observerResult = new Bundle();
        observerResult.putString(RuntimeKeys.OBSERVER_ID, "remote-observer");
        Bundle observer = relay.exposeObserver(observerResult, targetRequest);
        String localObserver = observer.getString(RuntimeKeys.OBSERVER_ID);
        Bundle unregister = new Bundle(targetRequest);
        unregister.putString(RuntimeKeys.OBSERVER_ID, localObserver);
        RuntimeCrossAbiProviderRelay.RemoteRequest remoteUnregister =
                relay.prepareObserverUnregister(unregister);
        require("remote-observer".equals(
                        remoteUnregister.request().getString(RuntimeKeys.OBSERVER_ID)),
                "observer unregister must translate back to the Companion id");
        relay.finishObserverUnregister(new Bundle(), unregister);
        require(relay.size() == 0, "observer unregister must remove the Host capability");
    }

    private static void rejectsIdentitySubstitution() {
        RuntimeCrossAbiProviderRelay relay = new RuntimeCrossAbiProviderRelay();
        Bundle targetRequest = request();
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.CURSOR_TOKEN, "remote-cursor");
        String local = relay.exposeCursor(result, targetRequest)
                .getString(RuntimeKeys.CURSOR_TOKEN);
        Bundle forged = new Bundle(targetRequest);
        forged.putString(RuntimeKeys.CURSOR_TOKEN, local);
        forged.putString(RuntimeKeys.CALLER_SESSION_ID, "forged-session");
        boolean rejected = false;
        try {
            relay.prepareExisting(forged, ComponentOperations.PROVIDER_CURSOR_PAGE);
        } catch (SecurityException expected) {
            rejected = true;
        }
        require(rejected, "caller session substitution must be rejected");
    }

    private static void invalidatesCallerTargetAndRemoteGeneration() {
        RuntimeCrossAbiProviderRelay relay = new RuntimeCrossAbiProviderRelay();
        Bundle targetRequest = request();
        Bundle cursor = new Bundle();
        cursor.putString(RuntimeKeys.CURSOR_TOKEN, "remote-cursor");
        relay.exposeCursor(cursor, targetRequest);
        require(relay.size() == 1, "cursor capability must be registered");
        relay.invalidateCaller("caller.package", 0, "caller-session", 7L);
        require(relay.size() == 0, "caller generation death must fence all resources");

        relay.exposeCursor(cursor, targetRequest);
        relay.invalidateTarget("target.package", 0);
        require(relay.size() == 0, "target instance stop must fence all resources");

        relay.exposeCursor(cursor, targetRequest);
        relay.invalidateAll();
        require(relay.size() == 0, "remote Companion death must fence all resources");
    }

    private static Bundle request() {
        Bundle request = new Bundle();
        request.putString(RuntimeKeys.PACKAGE_NAME, "target.package");
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, 0);
        request.putString(RuntimeKeys.TARGET_PACKAGE_NAME, "target.package");
        request.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, 0);
        request.putString(RuntimeKeys.CALLER_PACKAGE_NAME, "caller.package");
        request.putInt(RuntimeKeys.CALLER_VIRTUAL_USER_ID, 0);
        request.putString(RuntimeKeys.CALLER_SESSION_ID, "caller-session");
        request.putLong(RuntimeKeys.CALLER_GENERATION, 7L);
        return request;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
