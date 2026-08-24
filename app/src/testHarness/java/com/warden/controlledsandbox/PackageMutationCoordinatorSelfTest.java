package com.warden.controlledsandbox;

import org.json.JSONObject;

public final class PackageMutationCoordinatorSelfTest {
    public static void main(String[] args) throws Exception {
        PackageMutationCoordinator coordinator = new PackageMutationCoordinator();
        PackageMutationCoordinator.Start first = coordinator.begin("request-a", "import",
                "com.example.app", 0);
        require(first.owner, "first request owns key");

        PackageMutationCoordinator.Start duplicate = coordinator.begin("request-b", "import",
                "com.example.app", 0);
        require(!duplicate.owner, "duplicate package/user is BUSY");
        require(first.trace.operationId().equals(duplicate.trace.operationId()),
                "BUSY identifies the in-flight operation");

        PackageMutationCoordinator.Start otherUser = coordinator.begin("request-c", "import",
                "com.example.app", 1);
        require(otherUser.owner, "different virtual user has a distinct key");
        otherUser.trace.success();
        coordinator.complete(otherUser.trace);

        first.trace.anomaly("MIXED_ELF_MACHINE", "lib/arm64-v8a/libcvt.so");
        first.trace.success();
        coordinator.complete(first.trace);
        JSONObject completed = new JSONObject(coordinator.snapshot("request-a"));
        require("SUCCEEDED".equals(completed.optString("status")), "terminal status retained");
        require(completed.optInt("attempt") == 1, "business attempt is explicit");
        require(completed.optInt("retryBudget") == 0, "business retry budget is zero");
        require(completed.optJSONArray("anomalies").length() == 1,
                "mixed ELF anomaly retained without retry");

        PackageMutationCoordinator.Start after = coordinator.begin("request-d", "delete",
                "com.example.app", 0);
        require(after.owner, "terminal operation releases key");
        after.trace.failure("INJECTED_FAILURE", false);
        coordinator.complete(after.trace);
        JSONObject failed = new JSONObject(coordinator.snapshot("request-d"));
        require(!failed.optBoolean("retryable", true), "transaction error is not retryable");
        require("INJECTED_FAILURE".equals(failed.optString("errorCode")),
                "stable error code retained");

        System.out.println("PackageMutationCoordinatorSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
