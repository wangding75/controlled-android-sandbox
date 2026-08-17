package com.warden.controlledsandbox;

import com.warden.controlledsandbox.domain.persistence.PersistentStateException;
import java.util.List;

public final class SandboxCatalogStateSelfTest {
    public static void main(String[] args) throws Exception {
        SandboxRecord alphaV1 = record("com.example.alpha", 1L, repeat('a'));
        SandboxCatalogState state = SandboxCatalogState.normalizeLegacy(
                List.of(alphaV1), List.of(), 100L);
        require(state.records().size() == 1, "legacy package retained");
        require(state.instances().size() == 1, "default instance created");
        require(state.instances().get(0).virtualUserId == 0, "default user id");

        SandboxRecord alphaV2 = record("com.example.alpha", 2L, repeat('b'));
        SandboxCatalogState upgraded = state.withImported(alphaV2, 200L);
        require(upgraded.records().get(0).versionCode == 2L, "package revision replaced");
        require(upgraded.instances().size() == 1, "instances survive upgrade");
        require(upgraded.instances().get(0).createdAt == 100L, "existing instance identity retained");
        require(upgraded.records().get(0).firstInstallAt == alphaV1.firstInstallAt,
                "first install timestamp survives upgrade");
        require(upgraded.records().get(0).lastUpdateAt == 200L,
                "last update timestamp advances on upgrade");

        SandboxCatalogState explicit = upgraded.withEnsuredInstance("com.example.alpha", 7, 250L);
        require(explicit.instances().size() == 2, "explicit test instance added");
        require(explicit.withEnsuredInstance("com.example.alpha", 7, 251L) == explicit,
                "existing explicit instance is idempotent");

        SandboxCatalogState.CloneResult clone = upgraded.withClone("com.example.alpha", 300L);
        require(clone.virtualUserId == 1, "next virtual user allocated");
        require(clone.state.instances().size() == 2, "clone committed in aggregate");

        SandboxCatalogState status = clone.state.withInstanceStatus(
                "com.example.alpha", 1, "READY", 400L);
        require("READY".equals(status.instances().get(1).lastRuntimeStatus), "status updated");
        require("NOT_TESTED".equals(clone.state.instances().get(1).lastRuntimeStatus),
                "prior aggregate remains immutable");

        SandboxCatalogState packageOnlyDisabled = status.withPackageState(
                "com.example.alpha", 1, "DISABLED");
        require("DISABLED".equals(packageOnlyDisabled.policy("com.example.alpha", 1).packageState()),
                "package-only override must survive aggregate persistence");
        SandboxCatalogState componentOnlyDisabled = status.withComponentState(
                "com.example.alpha", 1, "com.example.alpha.MainActivity", "DISABLED");
        require("DISABLED".equals(componentOnlyDisabled.policy("com.example.alpha", 1)
                .componentState("com.example.alpha.MainActivity")),
                "component-only override must survive aggregate persistence");

        SandboxCatalogState permissionDenied = status.withPermissionDecision(
                "com.example.alpha", 1, "android.permission.CAMERA", "DENIED");
        require("DENIED".equals(permissionDenied.policy("com.example.alpha", 1)
                .permissionDecision("android.permission.CAMERA")), "permission decision persisted");
        SandboxCatalogState appOpIgnored = permissionDenied.withAppOpMode(
                "com.example.alpha", 1, "android:camera", "IGNORED");
        require("IGNORED".equals(appOpIgnored.policy("com.example.alpha", 1)
                .appOpMode("android:camera")), "AppOps mode persisted");
        SandboxCatalogState componentDisabled = appOpIgnored.withComponentState(
                "com.example.alpha", 1, "com.example.alpha.MainActivity", "DISABLED");
        require("DISABLED".equals(componentDisabled.policy("com.example.alpha", 1)
                .componentState("com.example.alpha.MainActivity")),
                "component enabled override persisted");
        SandboxCatalogState packageDisabled = componentDisabled.withPackageState(
                "com.example.alpha", 1, "DISABLED");
        require("DISABLED".equals(packageDisabled.policy("com.example.alpha", 1).packageState()),
                "package enabled override persisted");
        require(appOpIgnored.policy("com.example.alpha", 0).permissionDecisions().isEmpty(),
                "policy is isolated by virtual user");
        SandboxCatalogState resetPolicy = packageDisabled.withoutPolicy("com.example.alpha", 1);
        require("DEFAULT".equals(resetPolicy.policy("com.example.alpha", 1).packageState())
                && resetPolicy.policy("com.example.alpha", 1).permissionDecisions().isEmpty()
                && resetPolicy.policy("com.example.alpha", 1).appOpModes().isEmpty()
                && resetPolicy.policy("com.example.alpha", 1).componentStates().isEmpty(),
                "policy reset removes overrides");

        SandboxCatalogState defaultOnly = packageDisabled.withoutInstance("com.example.alpha", 1);
        require(defaultOnly.policies().isEmpty(), "instance deletion removes policy atomically");
        require(defaultOnly.records().size() == 1, "package retained while default exists");
        SandboxCatalogState empty = defaultOnly.withoutInstance("com.example.alpha", 0);
        require(empty.records().isEmpty() && empty.instances().isEmpty(),
                "last instance removal drops package atomically");

        SandboxRecord beta = record("com.example.beta", 1L, repeat('c'));
        SandboxCatalogState sorted = new SandboxCatalogState(
                List.of(beta, alphaV1),
                List.of(new SandboxInstance("com.example.beta", 2, "B2", 1L, "N", 0L),
                        new SandboxInstance("com.example.alpha", 1, "A1", 1L, "N", 0L)));
        require("com.example.alpha".equals(sorted.records().get(0).packageName),
                "catalog package order canonical");
        require("com.example.alpha".equals(sorted.instances().get(0).packageName),
                "catalog instance order canonical");

        boolean orphanRejected = false;
        try {
            new SandboxCatalogState(List.of(alphaV1),
                    List.of(new SandboxInstance("com.example.missing", 0, "bad", 1L, "N", 0L)));
        } catch (PersistentStateException expected) {
            orphanRejected = true;
        }
        require(orphanRejected, "orphan instance rejected");

        boolean duplicateRejected = false;
        try {
            new SandboxCatalogState(List.of(alphaV1, alphaV1), List.of());
        } catch (PersistentStateException expected) {
            duplicateRejected = true;
        }
        require(duplicateRejected, "duplicate package rejected");

        boolean orphanPolicyRejected = false;
        try {
            new SandboxCatalogState(List.of(alphaV1),
                    List.of(new SandboxInstance("com.example.alpha", 0, "Default", 1L, "N", 0L)),
                    List.of(SandboxPolicyState.empty("com.example.alpha", 1)));
        } catch (PersistentStateException expected) {
            orphanPolicyRejected = true;
        }
        require(orphanPolicyRejected, "orphan policy rejected");

        testLegacyPackageLayoutMigration();
        System.out.println("PASS atomic sandbox catalog aggregate self-test");
    }

    private static void testLegacyPackageLayoutMigration() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("package-layout-migration");
        try {
            FileBundle bundle = createLegacyBundle(root, "com.example.legacy");
            SandboxRecord legacyRecord = recordWithStorage("com.example.legacy", 3L,
                    bundle.sha256, bundle.apk.getAbsolutePath(), bundle.nativeDir.getAbsolutePath());
            SandboxCatalogState legacy = new SandboxCatalogState(List.of(legacyRecord),
                    List.of(new SandboxInstance("com.example.legacy", 0, "Default", 1L,
                            "NOT_TESTED", 0L)));
            PackageStorageLayout layout = new PackageStorageLayout(root.toFile());
            LegacyPackageLayoutMigrator migrator = new LegacyPackageLayoutMigrator(layout);
            SandboxCatalogState migrated = migrator.migrate(legacy);
            SandboxRecord migratedRecord = migrated.records().get(0);
            require(migratedRecord.apkPath.equals(layout.apkFile(
                    "com.example.legacy", bundle.sha256).getCanonicalPath()),
                    "legacy APK moved to immutable revision metadata");
            require(new java.io.File(migratedRecord.apkPath).isFile(),
                    "migrated immutable APK exists");
            require(new java.io.File(migratedRecord.nativeLibraryDir, "liblegacy.so").isFile(),
                    "legacy native library copied");
            require(new java.io.File(migratedRecord.nativeLibraryDir, "liblegacy.so").canExecute(),
                    "legacy native library remains executable after sealing");
            layout.requireCatalogLayout(migrated);
            SandboxCatalogState repeated = migrator.migrate(migrated);
            require(repeated.records().get(0).apkPath.equals(migratedRecord.apkPath),
                    "legacy migration is idempotent");

            java.io.File outside = root.resolve("outside.apk").toFile();
            java.nio.file.Files.writeString(outside.toPath(), "outside");
            String outsideSha = ApkImportManager.sha256(outside);
            SandboxRecord hostile = recordWithStorage("com.example.hostile", 1L,
                    outsideSha, outside.getAbsolutePath(), "");
            boolean outsideRejected = false;
            try {
                migrator.migrate(new SandboxCatalogState(List.of(hostile),
                        List.of(new SandboxInstance("com.example.hostile", 0, "Default",
                                1L, "NOT_TESTED", 0L))));
            } catch (SecurityException expected) {
                outsideRejected = true;
            }
            require(outsideRejected, "legacy package path outside managed root rejected");
        } finally {
            ApkImportManager.deleteTreeOrThrow(root.toFile());
        }
    }

    private static FileBundle createLegacyBundle(java.nio.file.Path filesDir,
                                                  String packageName) throws Exception {
        java.io.File packageDir = filesDir.resolve("packages")
                .resolve(PackageStorageLayout.safeSegment(packageName)).toFile();
        if (!packageDir.mkdirs()) throw new IllegalStateException("Cannot create legacy package directory");
        java.io.File apk = new java.io.File(packageDir, "base.apk");
        java.nio.file.Files.writeString(apk.toPath(), "legacy-apk-bytes");
        java.io.File nativeDir = new java.io.File(packageDir, "lib");
        if (!nativeDir.mkdirs()) throw new IllegalStateException("Cannot create legacy native directory");
        java.nio.file.Files.writeString(new java.io.File(nativeDir, "liblegacy.so").toPath(),
                "legacy-native-bytes");
        return new FileBundle(apk, nativeDir, ApkImportManager.sha256(apk));
    }

    private static SandboxRecord recordWithStorage(String packageName, long versionCode,
                                                   String sha, String apkPath,
                                                   String nativeLibraryDir) {
        return new SandboxRecord(packageName, packageName, "v" + versionCode, versionCode,
                repeat('d'), apkPath, nativeLibraryDir, packageName + ".MainActivity",
                packageName, "", "", packageName, "", packageName, "", "",
                packageName, "", "", sha, 1L, "NOT_TESTED", 0L);
    }

    private static final class FileBundle {
        final java.io.File apk;
        final java.io.File nativeDir;
        final String sha256;

        FileBundle(java.io.File apk, java.io.File nativeDir, String sha256) {
            this.apk = apk;
            this.nativeDir = nativeDir;
            this.sha256 = sha256;
        }
    }

    private static SandboxRecord record(String packageName, long versionCode, String sha) {
        return new SandboxRecord(packageName, packageName, "v" + versionCode, versionCode,
                repeat('d'), "/trusted/packages/" + packageName + "/revisions/" + sha + "/base.apk",
                "", packageName + ".MainActivity", packageName, "", "", packageName,
                "", packageName, "", "", packageName, "", "", sha, 1L,
                "NOT_TESTED", 0L);
    }

    private static String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
