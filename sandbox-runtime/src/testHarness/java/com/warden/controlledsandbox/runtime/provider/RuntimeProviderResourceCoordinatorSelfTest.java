package com.warden.controlledsandbox.runtime.provider;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.provider.UriGrantRegistry;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.session.SessionState;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.List;

/** Evidence that Provider close delivery no longer belongs to RuntimeBrokerService. */
public final class RuntimeProviderResourceCoordinatorSelfTest {
    private RuntimeProviderResourceCoordinatorSelfTest() { }

    public static void main(String[] args) {
        ProviderLifecycleCoordinator lifecycle = new ProviderLifecycleCoordinator(
                new BrokerProviderRuntime(), new BrokerCursorRuntime(), new BrokerFileRuntime(),
                new BrokerObserverRuntime(), new UriGrantRegistry());
        GuestSession session = new GuestSession("session", "guest.pkg", 2, "guest.pkg:provider",
                3, 7L, SessionState.READY, 1L, "");
        List<Bundle> calls = new ArrayList<>();
        RuntimeProviderResourceCoordinator coordinator = new RuntimeProviderResourceCoordinator(
                lifecycle,
                (sessionId, generation) -> "session".equals(sessionId) && generation == 7L ? session : null,
                target -> {
                    Bundle prepared = new Bundle();
                    prepared.putString(RuntimeKeys.PACKAGE_NAME, target.packageName());
                    prepared.putInt(RuntimeKeys.VIRTUAL_USER_ID, target.virtualUserId());
                    return prepared;
                },
                (slot, request) -> { calls.add(new Bundle(request)); return new Bundle(); });

        coordinator.closeCursorBestEffort(session, "cursor-token");
        coordinator.closeFileBestEffort(session, "file-token");
        require(calls.size() == 2, "both Provider capability families must be closed");
        require(ComponentOperations.PROVIDER_CURSOR_CANCEL.equals(
                        calls.get(0).getString(ComponentOperations.OPERATION, ""))
                        && "cursor-token".equals(calls.get(0).getString(RuntimeKeys.CURSOR_TOKEN, "")),
                "cursor close request must be routed through the coordinator");
        require(ComponentOperations.PROVIDER_FILE_CLOSE.equals(
                        calls.get(1).getString(ComponentOperations.OPERATION, ""))
                        && "file-token".equals(calls.get(1).getString(RuntimeKeys.FILE_TOKEN, "")),
                "file close request must be routed through the coordinator");

        RuntimeProviderResourceCoordinator unavailable = new RuntimeProviderResourceCoordinator(
                lifecycle, (id, generation) -> null, target -> null,
                (slot, request) -> { throw new AssertionError("unavailable target must not be called"); });
        unavailable.purgeExpired(0L);
        System.out.println("PASS Runtime Provider resource coordinator extraction self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
