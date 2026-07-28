package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.PackageServiceResult;

public final class RuntimePermissionCoordinatorSelfTest {
    public static void main(String[] args) {
        FakeGateway gateway = new FakeGateway();
        RuntimePermissionCoordinator coordinator = new RuntimePermissionCoordinator(gateway,
                (id, generation) -> "session-1".equals(id) && generation == 4
                        ? new RuntimePermissionCoordinator.PermissionSession(
                        "guest.pkg", 2, id, generation, true) : null);
        require(coordinator.request("session-1", 4, "android.permission.CAMERA", 9).successful(),
                "ready session request delegated");
        require(gateway.requests == 1 && gateway.lastUser == 2, "identity carried to gateway");
        require(!coordinator.request("stale", 4, "android.permission.CAMERA", 9).successful(),
                "unknown session rejected");
        require(!coordinator.report("session-1", 4, "", 9, true, "").successful(),
                "empty permission rejected");
        coordinator.close();
        require(gateway.closed, "gateway closed with coordinator");
        System.out.println("PASS runtime permission coordinator self-test");
    }

    private static final class FakeGateway implements RuntimePermissionGateway {
        int requests;
        int lastUser;
        boolean closed;
        @Override public PackageServiceResult request(String packageName, int virtualUserId,
                String permission, int requestCode, String sessionId, long generation) {
            requests++; lastUser = virtualUserId;
            return PackageServiceResult.success("request");
        }
        @Override public PackageServiceResult report(String packageName, int virtualUserId,
                String permission, int requestCode, String sessionId, long generation,
                boolean hostGranted, String reason) {
            return PackageServiceResult.success("report");
        }
        @Override public void close() { closed = true; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
