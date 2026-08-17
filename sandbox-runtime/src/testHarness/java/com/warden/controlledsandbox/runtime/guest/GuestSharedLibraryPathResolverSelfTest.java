package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualSharedLibrarySnapshot;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Verifies that Virtual PMS library resolution reaches the Guest class-loader path. */
public final class GuestSharedLibraryPathResolverSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("cs-shared-library").toFile();
        File guest = new File(root, "guest.apk");
        File provider = new File(root, "provider.apk");
        File split = new File(root, "provider.config.apk");
        require(guest.createNewFile() && provider.createNewFile() && split.createNewFile(),
                "test APK fixtures created");
        ApplicationInfo providerInfo = new ApplicationInfo();
        providerInfo.splitSourceDirs = new String[]{split.getAbsolutePath()};
        VirtualPackageStateSnapshot state = state("guest.pkg", "guest.apk",
                new VirtualSharedLibrarySnapshot(VirtualSharedLibrarySnapshot.KIND_JAVA,
                        "guest.sdk", true, 0L, "", true, "provider.pkg"));
        VirtualPackageStateSnapshot providerState = state("provider.pkg", "provider.apk");
        VirtualPackageProjectionSnapshot projection = new VirtualPackageProjectionSnapshot(
                providerState, provider.getAbsolutePath(), "", 12002, providerInfo);
        String path = GuestSharedLibraryPathResolver.appendResolvedLibraryPaths(
                guest.getAbsolutePath(), state, List.of(projection));
        require(path.contains(guest.getCanonicalPath()), "Guest APK remains first in dex path");
        require(path.contains(provider.getCanonicalPath()), "resolved provider APK is appended");
        require(path.contains(split.getCanonicalPath()), "provider split APK is appended");
        boolean missing = false;
        try {
            GuestSharedLibraryPathResolver.appendResolvedLibraryPaths(guest.getAbsolutePath(),
                    state, List.of());
        } catch (IllegalStateException expected) { missing = true; }
        require(missing, "missing virtual provider projection fails closed");
        System.out.println("PASS virtual shared-library class-loader path self-test");
    }

    private static VirtualPackageStateSnapshot state(String packageName, String apkName,
                                                      VirtualSharedLibrarySnapshot... libraries) {
        return new VirtualPackageStateSnapshot(packageName, 0, packageName, "1", 1L,
                repeat('a'), repeat(apkName.startsWith("guest") ? 'b' : 'c'), "", "", true,
                0L, 0L, "installer",
                List.of(), List.of(), List.of(libraries), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) result.append(value);
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
