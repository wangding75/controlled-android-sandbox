package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;

/** Direct executable proof that the package-private Bundle adapter remains functional. */
public final class RuntimeBrokerOperationBoundarySelfTest {
    private RuntimeBrokerOperationBoundarySelfTest() { }

    public static void main(String[] args) {
        Bundle payload = new Bundle();
        payload.putInt(RuntimeKeys.PROTOCOL, 3);
        payload.putString(RuntimeKeys.PACKAGE_NAME, "guest.pkg");
        payload.putInt(RuntimeKeys.VIRTUAL_USER_ID, 4);
        payload.putString(RuntimeKeys.SESSION_ID, "session-4");
        payload.putLong(RuntimeKeys.GENERATION, 9L);
        RuntimeOperationRequest request = RuntimeOperationTransport.request(
                RuntimeOperationRequest.PREPARE_GUEST, payload);
        RuntimeOperationResult result = RuntimeBrokerOperationAdapter.execute(
                new FakeHandler(), request);
        require(result.successful(), "package-private adapter failed");
        require("PREPARED".equals(result.status()), "adapter status mismatch");
        require("guest.pkg".equals(result.payload().getString(RuntimeKeys.PACKAGE_NAME)),
                "adapter payload mismatch");
        System.out.println("PASS internal Runtime Broker Bundle boundary self-test");
    }

    private static final class FakeHandler implements RuntimeBrokerOperationHandler {
        @Override public Bundle prepareGuest(Bundle request) {
            Bundle result = new Bundle(request);
            result.putString(RuntimeKeys.STATUS, "PREPARED");
            return result;
        }
        @Override public Bundle launchActivity(Bundle request) { return unsupported(); }
        @Override public Bundle invokeComponent(Bundle request) { return unsupported(); }
        @Override public Bundle grantUriPermission(Bundle request) { return unsupported(); }
        @Override public Bundle revokeUriPermission(Bundle request) { return unsupported(); }
        @Override public Bundle consumeRoute(String token, String sessionId, long generation) {
            return unsupported();
        }
        @Override public Bundle activityEvent(Bundle request) { return unsupported(); }
        @Override public Bundle sessionStatus(String packageName, int virtualUserId) {
            return unsupported();
        }
        @Override public Bundle runtimeStatus() { return unsupported(); }

        private static Bundle unsupported() {
            throw new UnsupportedOperationException("unused test operation");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
