package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionPage;
import com.warden.controlledsandbox.contract.InstallSessionParamsSnapshot;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class InstallSessionPagingSelfTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("install-session-pager").toFile();
        try (VirtualSystemServicePager pager = new VirtualSystemServicePager(root)) {
            List<InstallSessionInfoSnapshot> source = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int id = 1; id <= 40; id++) {
                source.add(new InstallSessionInfoSnapshot(id,
                        InstallSessionInfoSnapshot.STATE_OPEN,
                        InstallSessionParamsSnapshot.fullInstall("pkg." + id),
                        0, 0L, 0F, now, now, 0, "", ""));
            }
            VirtualSystemServicePager.PageSlice<InstallSessionInfoSnapshot> first = pager.page(
                    "install-sessions", "management", source,
                    new VirtualPageRequest(16, 128 * 1024, ""), null);
            InstallSessionPage firstPage = new InstallSessionPage(first.items(),
                    first.nextPageToken(), first.snapshotRevision());
            require(firstPage.items().size() == 16, "first page size");
            require(!firstPage.nextPageToken().isEmpty(), "first page token");
            VirtualSystemServicePager.PageSlice<InstallSessionInfoSnapshot> second = pager.page(
                    "install-sessions", "management", source,
                    new VirtualPageRequest(32, 128 * 1024, firstPage.nextPageToken()), null);
            require(second.items().size() == 24, "second page size");
            require(second.nextPageToken().isEmpty(), "last page token");
            require(second.snapshotRevision() == firstPage.snapshotRevision(), "stable revision");
            System.out.println("PASS bounded install-session paging self-test");
        } finally {
            ApkImportManager.deleteTreeOrThrow(root);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
