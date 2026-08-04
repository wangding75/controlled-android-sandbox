package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.InstallSessionInfoSnapshot;
import com.warden.controlledsandbox.contract.InstallSessionPage;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import java.io.File;
import java.util.List;

/** Owns bounded install-session pagination and its page-token lifecycle. */
final class InstallSessionPageAuthority implements AutoCloseable {
    private static final String COLLECTION = "install-sessions";
    private static final String SCOPE = "management";

    private final VirtualSystemServicePager pager;

    InstallSessionPageAuthority(File filesDir) {
        pager = new VirtualSystemServicePager(filesDir);
    }

    List<InstallSessionInfoSnapshot> legacy(SandboxPackageLifecycle lifecycle) throws Exception {
        return pager.legacy(page(lifecycle.installSessions(), new VirtualPageRequest(
                VirtualSystemServicePager.LEGACY_MAX_ITEMS,
                VirtualSystemServicePager.LEGACY_MAX_BYTES, "")));
    }

    InstallSessionPage page(SandboxPackageLifecycle lifecycle, VirtualPageRequest request) {
        VirtualSystemServicePager.PageSlice<InstallSessionInfoSnapshot> result =
                page(uncheckedSessions(lifecycle), request);
        return new InstallSessionPage(
                result.items(), result.nextPageToken(), result.snapshotRevision());
    }

    private VirtualSystemServicePager.PageSlice<InstallSessionInfoSnapshot> page(
            List<InstallSessionInfoSnapshot> sessions, VirtualPageRequest request) {
        return pager.page(COLLECTION, SCOPE, sessions, request, null);
    }

    private static List<InstallSessionInfoSnapshot> uncheckedSessions(
            SandboxPackageLifecycle lifecycle) {
        try {
            return lifecycle.installSessions();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("INSTALL_SESSION_LIST_FAILED", error);
        }
    }

    @Override public void close() {
        pager.close();
    }
}
