package com.warden.controlledsandbox;

import java.util.List;

public final class PackageLifecycleTransactionSelfTest {
    private PackageLifecycleTransactionSelfTest() { }

    public static void main(String[] args) throws Exception {
        SandboxRecord v1 = record("com.example.rev", 1L, "a");
        SandboxRecord v2 = record("com.example.rev", 2L, "b");
        PackageLifecycleTransaction installed = PackageLifecycleTransaction.installed(v1, 10L);
        require(installed.state == PackageLifecycleTransaction.State.ACTIVE, "install starts ACTIVE");
        require(installed.installRevision == 1L && installed.dataRevision == 1L
                        && installed.identityGeneration == 1L,
                "revision counters start at 1");

        PackageLifecycleTransaction prepared = installed.prepareUpdate(v1, 20L);
        require(prepared.state == PackageLifecycleTransaction.State.UPDATING_PREPARE,
                "prepare is UPDATING_PREPARE");
        require(prepared.previousPackageRevision.equals(v1.sha256), "previous revision retained");
        require(prepared.dataRevision == 1L, "package revision change must not reset dataRevision");

        PackageLifecycleTransaction switched = prepared.switchUpdate(v2, 30L);
        require(switched.state == PackageLifecycleTransaction.State.UPDATING_SWITCH,
                "switch is UPDATING_SWITCH");
        require(switched.installRevision == 2L, "installRevision advances on switch");
        require(switched.dataRevision == 1L, "dataRevision stays across upgrade");
        require(switched.currentVersionCode == 2L && switched.previousVersionCode == 1L,
                "version codes tracked independently");

        PackageLifecycleTransaction active = switched.activate(40L, "update-active");
        require(active.state == PackageLifecycleTransaction.State.ACTIVE, "activate returns ACTIVE");

        PackageLifecycleTransaction rollback = active.beginRollback(50L).abortToPrevious(60L);
        require(rollback.currentPackageRevision.equals(v1.sha256), "rollback restores previous revision");
        require(rollback.currentVersionCode == 1L, "rollback restores previous versionCode");
        require(rollback.dataRevision == 1L, "rollback does not reset data");

        PackageLifecycleTransaction reset = rollback.resetIdentity(70L);
        require(reset.identityGeneration == 2L, "identity reset advances identityGeneration");
        require(reset.dataRevision == 2L, "identity reset is not clear-data; it advances dataRevision");
        require(reset.currentPackageRevision.equals(v1.sha256), "identity reset keeps package revision");

        SandboxCatalogState catalog = new SandboxCatalogState(List.of(v2),
                List.of(new SandboxInstance("com.example.rev", 0, "Default", 1L, "N", 0L)));
        SandboxCatalogState restored = catalog.withRestoredRevision(v1);
        require(restored.findRecord("com.example.rev").sha256.equals(v1.sha256),
                "catalog restore must not mix new metadata with old revision");
        require(restored.instances().size() == 1, "restore keeps instance/data identity");

        boolean inFlightRejected = false;
        try {
            prepared.requireNotInFlight("launch");
        } catch (IllegalStateException expected) {
            inFlightRejected = String.valueOf(expected.getMessage()).contains("LIFECYCLE_IN_FLIGHT");
        }
        require(inFlightRejected, "in-flight update must reject cross-revision launch");

        System.out.println("PASS transactional package lifecycle revision self-test");
    }

    private static SandboxRecord record(String packageName, long versionCode, String seed) {
        String sha = seed.repeat(64).substring(0, 64);
        return new SandboxRecord(packageName, packageName, "1.0." + versionCode, versionCode,
                sha, "/tmp/" + sha + "/base.apk", "", "Main", packageName, "",
                "", "", "", "", "", "", "", "", "", sha, 1L, "", 0L);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
