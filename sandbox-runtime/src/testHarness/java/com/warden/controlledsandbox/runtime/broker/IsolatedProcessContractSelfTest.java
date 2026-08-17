package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.IsolatedProcessRequest;
import com.warden.controlledsandbox.contract.IsolatedProcessResult;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

public final class IsolatedProcessContractSelfTest {
    private IsolatedProcessContractSelfTest() { }

    public static void main(String[] args) {
        Bundle payload = payload();
        IsolatedProcessRequest request = new IsolatedProcessRequest(1, "session-1", 2L, 3, 7,
                "com.example", "com.example:isolated_service", "com.example.IsolatedService",
                "revision-1", ComponentOperations.START_SERVICE, "capability-1", payload);
        payload.putString(RuntimeKeys.PACKAGE_NAME, "mutated");
        check("com.example".equals(request.payload().getString(RuntimeKeys.PACKAGE_NAME)),
                "request must defensively copy payload");
        Bundle exposed = request.payload();
        exposed.putString(RuntimeKeys.PACKAGE_NAME, "mutated-again");
        check("com.example".equals(request.payload().getString(RuntimeKeys.PACKAGE_NAME)),
                "payload getter must return a copy");

        IsolatedProcessRequest frameworkRoute = new IsolatedProcessRequest(1, "session-1", 2L, 3, 7,
                "com.example", "com.example:isolated_service", "com.example.IsolatedService",
                "revision-1", ComponentOperations.ROUTE_FRAMEWORK_SERVICE, "capability-1", payload());
        check(ComponentOperations.ROUTE_FRAMEWORK_SERVICE.equals(frameworkRoute.operation()),
                "framework Service route must be a legal isolated operation");

        IsolatedProcessResult result = IsolatedProcessResult.success(request, "ISOLATED_READY", 4321, 99001, new Bundle());
        check(result.successful(), "successful result expected");
        check(result.generation() == 2L && result.processSlot() == 3, "result must bind generation and slot");
        check(result.platformPid() == 4321 && result.platformUid() == 99001, "platform identity evidence missing");

        expectInvalid(() -> new IsolatedProcessRequest(1, "s", 1L, 0, 0, "bad", "p", "c",
                "r", ComponentOperations.START_SERVICE, "cap", payload()));
        expectInvalid(() -> new IsolatedProcessRequest(1, "s", 1L, 0, 0, "com.example", "p", "c",
                "r", ComponentOperations.SEND_BROADCAST, "cap", payload()));
        expectInvalid(() -> new IsolatedProcessResult(true, "READY", "s", 1L, 0, "p", "c",
                1, 2, "SecurityException", "bad", new Bundle()));
        System.out.println("PASS typed isolated-process capability contract self-test");
    }

    private static Bundle payload() {
        Bundle payload = new Bundle();
        payload.putString(RuntimeKeys.PACKAGE_NAME, "com.example");
        payload.putInt(RuntimeKeys.VIRTUAL_USER_ID, 7);
        payload.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.IsolatedService");
        payload.putString(RuntimeKeys.PACKAGE_REVISION, "revision-1");
        return payload;
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid isolated contract must fail closed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
