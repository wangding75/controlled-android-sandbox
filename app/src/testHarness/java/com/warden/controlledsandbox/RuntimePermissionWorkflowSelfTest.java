package com.warden.controlledsandbox;

import java.util.List;

/** Host-side regression coverage for runtime permission request, resolution and revocation state. */
public final class RuntimePermissionWorkflowSelfTest {
    private static final String PACKAGE = "com.example.permission";
    private static final String CAMERA = "android.permission.CAMERA";
    private static final String MICROPHONE = "android.permission.RECORD_AUDIO";

    private RuntimePermissionWorkflowSelfTest() { }

    public static void main(String[] args) {
        SandboxRecord record = record(PACKAGE, 1L, repeat('a'));
        SandboxCatalogState initial = new SandboxCatalogState(
                List.of(record),
                List.of(new SandboxInstance(PACKAGE, 0, "Default", 1L, "READY", 2L),
                        new SandboxInstance(PACKAGE, 1, "Clone 1", 1L, "READY", 2L)));

        SandboxCatalogState.PermissionRequestResult first = initial.withPermissionRequest(
                PACKAGE, 0, CAMERA, "android:camera", false, 41,
                "session-a", 1L, 10L, "RUNTIME_BROKER");
        require(RuntimePermissionRequestRecord.PENDING.equals(first.request.state),
                "new request must be pending");
        require(first.state.pendingPermissionRequests(PACKAGE, 0).size() == 1,
                "pending request must be queryable");
        require(first.state.permissionAudit(PACKAGE, 0, 10).get(0).action.equals("REQUEST"),
                "request must be audited");

        SandboxCatalogState.PermissionRequestResult duplicate = first.state.withPermissionRequest(
                PACKAGE, 0, CAMERA, "android:camera", false, 41,
                "session-a", 1L, 11L, "RUNTIME_BROKER");
        require(duplicate.state == first.state && duplicate.request.requestId == first.request.requestId,
                "same generation request must be idempotent");

        SandboxCatalogState.PermissionRequestResult superseded = duplicate.state.withPermissionRequest(
                PACKAGE, 0, CAMERA, "android:camera", true, 42,
                "session-b", 2L, 20L, "RUNTIME_BROKER");
        require(superseded.request.requestId > first.request.requestId,
                "new generation must get a new request identity");
        require(RuntimePermissionRequestRecord.CANCELLED.equals(
                        superseded.state.permissionRequest(first.request.requestId).state),
                "stale pending request must be cancelled");
        require(superseded.state.pendingPermissionRequest(PACKAGE, 0, CAMERA, 42,
                        "session-b", 2L) != null,
                "new generation pending request must be owner-bound");
        require(superseded.state.pendingPermissionRequest(PACKAGE, 0, CAMERA, 41,
                        "session-a", 1L) == null,
                "cancelled request must not remain pending");

        expectSecurity(() -> superseded.state.withPermissionResolution(
                        superseded.request.requestId, RuntimePermissionRequestRecord.GRANTED,
                        false, "host denied", "ANDROID_PERMISSION_RESULT", 30L),
                "virtual grant must require host capability");

        SandboxCatalogState.PermissionRequestResult granted = superseded.state.withPermissionResolution(
                superseded.request.requestId, RuntimePermissionRequestRecord.GRANTED,
                true, "host granted", "ANDROID_PERMISSION_RESULT", 31L);
        require(SandboxPolicyState.PERMISSION_GRANTED.equals(granted.state.policy(PACKAGE, 0)
                        .permissionDecision(CAMERA)),
                "grant must update virtual permission policy");
        require(SandboxPolicyState.APP_OP_ALLOWED.equals(granted.state.policy(PACKAGE, 0)
                        .appOpMode("android:camera")),
                "grant must update linked AppOps policy");
        require(RuntimePermissionRequestRecord.GRANTED.equals(
                        granted.state.latestPermissionRequestState(PACKAGE, 0, CAMERA)),
                "latest request state must expose grant");

        SandboxCatalogState.PermissionRequestResult cloneDeniedPending = granted.state.withPermissionRequest(
                PACKAGE, 1, CAMERA, "android:camera", false, 7,
                "clone-session", 1L, 40L, "RUNTIME_BROKER");
        SandboxCatalogState.PermissionRequestResult cloneDenied =
                cloneDeniedPending.state.withPermissionResolution(
                        cloneDeniedPending.request.requestId, RuntimePermissionRequestRecord.DENIED,
                        false, "user denied", "ANDROID_PERMISSION_RESULT", 41L);
        require(SandboxPolicyState.PERMISSION_DENIED.equals(cloneDenied.state.policy(PACKAGE, 1)
                        .permissionDecision(CAMERA)),
                "clone denial must be persisted");
        require(SandboxPolicyState.PERMISSION_GRANTED.equals(cloneDenied.state.policy(PACKAGE, 0)
                        .permissionDecision(CAMERA)),
                "permission decisions must remain isolated by virtual user");

        SandboxCatalogState.PermissionRequestResult pendingMicrophone =
                cloneDenied.state.withPermissionRequest(PACKAGE, 0, MICROPHONE,
                        "android:record_audio", false, 8, "session-b", 2L,
                        50L, "RUNTIME_BROKER");
        SandboxCatalogState appOpConfigured = pendingMicrophone.state.withAppOpMode(
                PACKAGE, 0, "android:camera", SandboxPolicyState.APP_OP_IGNORED);
        SandboxCatalogState reset = appOpConfigured.withPolicyReset(
                PACKAGE, 0, "administrator reset", "HOST_MAIN", 51L);
        require(reset.policy(PACKAGE, 0).permissionDecisions().isEmpty(),
                "policy reset must clear decisions");
        require(reset.policy(PACKAGE, 0).appOpModes().isEmpty(),
                "policy reset must clear AppOps modes");
        require(RuntimePermissionRequestRecord.CANCELLED.equals(
                        reset.latestPermissionRequestState(PACKAGE, 0, MICROPHONE)),
                "policy reset must cancel pending callbacks");
        require(reset.permissionAudit(PACKAGE, 0, 20).stream()
                        .anyMatch(item -> item.action.equals("RESET")
                                && item.permission.equals(MICROPHONE)
                                && item.outcome.equals(RuntimePermissionRequestRecord.CANCELLED)),
                "policy reset cancellation must be audited");
        require(reset.permissionAudit(PACKAGE, 0, 20).stream()
                        .anyMatch(item -> item.action.equals("RESET")
                                && item.permission.equals(CAMERA)
                                && item.outcome.equals(SandboxPolicyState.PERMISSION_DEFAULT)),
                "policy reset must audit cleared permission decisions without pending callbacks");
        require(reset.permissionAudit(PACKAGE, 0, 20).stream()
                        .anyMatch(item -> item.action.equals("RESET_APP_OP")
                                && item.permission.equals("android:camera")
                                && item.outcome.equals(SandboxPolicyState.APP_OP_DEFAULT)),
                "policy reset must audit cleared AppOps policy");

        SandboxCatalogState.PermissionRequestResult pendingBeforeUpgrade =
                cloneDenied.state.withPermissionRequest(PACKAGE, 0, MICROPHONE,
                        "android:record_audio", false, 19, "session-upgrade", 3L,
                        55L, "RUNTIME_BROKER");
        SandboxCatalogState upgraded = pendingBeforeUpgrade.state.withImported(
                record(PACKAGE, 2L, repeat('b')), 56L);
        require(RuntimePermissionRequestRecord.CANCELLED.equals(
                        upgraded.permissionRequest(pendingBeforeUpgrade.request.requestId).state),
                "package revision change must cancel pending permission requests");
        require(upgraded.permissionAudit(PACKAGE, 0, 20).stream()
                        .anyMatch(item -> item.action.equals("PACKAGE_UPDATE")),
                "package revision cancellation must be audited");

        SandboxCatalogState revoked = granted.state.withPermissionRevocation(
                PACKAGE, 0, CAMERA, "android:camera", "administrator revoked",
                "HOST_MAIN", 60L);
        require(SandboxPolicyState.PERMISSION_DENIED.equals(revoked.policy(PACKAGE, 0)
                        .permissionDecision(CAMERA)),
                "revocation must deny permission");
        require(SandboxPolicyState.APP_OP_IGNORED.equals(revoked.policy(PACKAGE, 0)
                        .appOpMode("android:camera")),
                "revocation must deny linked AppOp");
        require(revoked.permissionAudit(PACKAGE, 0, 20).get(0).action.equals("REVOKE"),
                "revocation must be the latest audit event");

        SandboxCatalogState cloneRemoved = cloneDenied.state.withoutInstance(PACKAGE, 1);
        require(cloneRemoved.permissionRequests().stream()
                        .noneMatch(item -> item.virtualUserId == 1),
                "instance deletion must remove permission requests");
        require(cloneRemoved.permissionAudit().stream()
                        .noneMatch(item -> item.virtualUserId == 1),
                "instance deletion must remove permission audit");

        System.out.println("PASS runtime permission workflow self-test");
    }

    private static SandboxRecord record(String packageName, long versionCode, String sha) {
        return new SandboxRecord(packageName, packageName, "v" + versionCode, versionCode,
                repeat('d'), "/trusted/packages/" + packageName + "/revisions/" + sha + "/base.apk",
                "", packageName + ".MainActivity", packageName, "", "", packageName,
                "", packageName, "", "", packageName, "", "", sha, 1L,
                "NOT_TESTED", 0L);
    }

    private static String repeat(char value) { return String.valueOf(value).repeat(64); }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    private static void expectSecurity(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (SecurityException expected) { }
    }
}
