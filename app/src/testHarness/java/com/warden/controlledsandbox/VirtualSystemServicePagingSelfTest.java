package com.warden.controlledsandbox;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.VirtualAccountPage;
import com.warden.controlledsandbox.contract.VirtualAccountSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationPage;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import com.warden.controlledsandbox.contract.VirtualPageBlob;
import com.warden.controlledsandbox.contract.VirtualPageRequest;
import com.warden.controlledsandbox.contract.VirtualSettingPage;
import com.warden.controlledsandbox.contract.VirtualSettingSnapshot;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic Binder page, token, budget, blob-handle, and account-minimization regression. */
public final class VirtualSystemServicePagingSelfTest {
    private VirtualSystemServicePagingSelfTest() { }

    public static void main(String[] args) throws Exception {
        requestBounds();
        pageTokensAndBudgets();
        binaryOffload();
        blobGrantRollbackAndExactBudget();
        accountSessionPaging();
        System.out.println("PASS virtual system-service Binder paging and byte-budget self-test");
    }

    private static void requestBounds() {
        expect(IllegalArgumentException.class, "PAGE_MAX_ITEMS_INVALID",
                () -> new VirtualPageRequest(0, VirtualPageRequest.MIN_BYTES, ""));
        expect(IllegalArgumentException.class, "PAGE_MAX_BYTES_INVALID",
                () -> new VirtualPageRequest(1, VirtualPageRequest.MIN_BYTES - 1, ""));
        new VirtualPageRequest(VirtualPageRequest.MAX_ITEMS, VirtualPageRequest.MAX_BYTES, "");
    }

    private static void pageTokensAndBudgets() throws Exception {
        File root = Files.createTempDirectory("virtual-page-token-test").toFile();
        try (VirtualSystemServicePager pager = new VirtualSystemServicePager(root)) {
            List<VirtualSettingSnapshot> values = settings(70, "value-");
            String token = "";
            long revision = -1L;
            int count = 0;
            int pages = 0;
            do {
                VirtualPageRequest request = new VirtualPageRequest(7, 16 * 1024, token);
                VirtualSystemServicePager.PageSlice<VirtualSettingSnapshot> slice = pager.page(
                        "SETTING:secure", "scope-a", values, request, null);
                VirtualSettingPage page = new VirtualSettingPage(slice.items(), slice.blobs(),
                        slice.nextPageToken(), slice.snapshotRevision());
                require(VirtualSystemServicePager.measuredBytes(page) <= request.maxBytes(),
                        "serialized page exceeded requested byte budget");
                require(page.items().size() <= request.maxItems(), "page exceeded item budget");
                if (revision < 0L) revision = page.snapshotRevision();
                require(revision == page.snapshotRevision(), "snapshot revision changed between stable pages");
                count += page.items().size();
                token = page.nextPageToken();
                pages++;
            } while (!token.isEmpty());
            require(count == values.size() && pages == 10, "page traversal lost or duplicated settings");

            VirtualSystemServicePager.PageSlice<VirtualSettingSnapshot> first = pager.page(
                    "SETTING:secure", "scope-a", values, new VirtualPageRequest(5, 16 * 1024, ""), null);
            String firstToken = first.nextPageToken();
            require(!firstToken.isEmpty(), "first page did not issue a continuation token");
            byte[] tamperedBytes = java.util.Base64.getUrlDecoder().decode(firstToken);
            tamperedBytes[tamperedBytes.length - 1] ^= 0x01;
            String tampered = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(tamperedBytes);
            expect(SecurityException.class, "PAGE_TOKEN_TAMPERED", () -> pager.page(
                    "SETTING:secure", "scope-a", values,
                    new VirtualPageRequest(5, 16 * 1024, tampered), null));
            expect(SecurityException.class, "PAGE_TOKEN_SCOPE_MISMATCH", () -> pager.page(
                    "SETTING:secure", "scope-b", values,
                    new VirtualPageRequest(5, 16 * 1024, firstToken), null));
            expect(SecurityException.class, "PAGE_TOKEN_COLLECTION_MISMATCH", () -> pager.page(
                    "SETTING:system", "scope-a", values,
                    new VirtualPageRequest(5, 16 * 1024, firstToken), null));
            List<VirtualSettingSnapshot> changed = new ArrayList<>(values);
            changed.set(0, new VirtualSettingSnapshot("secure", "key-000", "same-size-change", 1L));
            expect(IllegalStateException.class, "PAGE_TOKEN_STALE", () -> pager.page(
                    "SETTING:secure", "scope-a", changed,
                    new VirtualPageRequest(5, 16 * 1024, firstToken), null));

            VirtualSettingSnapshot oversized = new VirtualSettingSnapshot(
                    "secure", "oversized", "x".repeat(16_384), 1L);
            expect(IllegalStateException.class, "ITEM_EXCEEDS_BINDER_BUDGET", () -> pager.page(
                    "SETTING:secure", "scope-a", List.of(oversized),
                    new VirtualPageRequest(1, 16 * 1024, ""), null));
        }
    }

    private static void binaryOffload() throws Exception {
        File root = Files.createTempDirectory("virtual-page-blob-test").toFile();
        try (VirtualSystemServicePager pager = new VirtualSystemServicePager(root)) {
            byte[] payload = new byte[100 * 1024];
            for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index * 31);
            VirtualNotificationSnapshot value = new VirtualNotificationSnapshot(
                    1, 2, "guest", "host", "channel", VirtualNotificationSnapshot.ACTIVE,
                    "revision", "", "", List.of(), false, "", payload, 1L);
            VirtualPageRequest request = new VirtualPageRequest(4, 32 * 1024, "");
            VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> slice = pager.page(
                    "NOTIFICATION", "scope-blob", List.of(value), request,
                    VirtualSystemServicePageAdapters.NOTIFICATION);
            VirtualNotificationPage page = new VirtualNotificationPage(slice.items(), slice.blobs(),
                    slice.nextPageToken(), slice.snapshotRevision());
            require(page.items().size() == 1 && page.items().get(0).payload().length == 0,
                    "large payload was not removed from Binder page");
            require(page.blobs().size() == 1 && page.blobs().get(0).byteCount() == payload.length,
                    "large payload descriptor missing");
            require(VirtualSystemServicePager.measuredBytes(page) <= request.maxBytes(),
                    "offloaded page exceeded Binder byte budget");
            String blobToken = page.blobs().get(0).blobToken();
            expect(SecurityException.class, "PAGE_BLOB_SCOPE_MISMATCH", () -> pager.openBlob(
                    "scope-other", blobToken));
            byte[] restored;
            try (ParcelFileDescriptor descriptor = pager.openBlob("scope-blob", blobToken);
                 FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
                restored = input.readAllBytes();
            }
            require(Arrays.equals(payload, restored), "ParcelFileDescriptor payload changed");
            expect(IllegalArgumentException.class, "PAGE_BLOB_TOKEN_INVALID", () -> pager.openBlob(
                    "scope-blob", blobToken));
            expect(IllegalStateException.class, "PAGING_REQUIRED", () -> pager.legacy(slice));
            blobGrantWindow(pager, payload);
        }
    }

    private static void blobGrantWindow(VirtualSystemServicePager pager, byte[] payload) throws Exception {
        ArrayList<VirtualNotificationSnapshot> values = new ArrayList<>();
        for (int index = 0; index < VirtualPageBlobStore.MAX_GRANTS + 1; index++) {
            values.add(new VirtualNotificationSnapshot(index, index, "guest-" + index, "host-" + index,
                    "channel", VirtualNotificationSnapshot.ACTIVE, "revision", "", "", List.of(),
                    false, "", payload, index));
        }
        VirtualPageRequest request = new VirtualPageRequest(VirtualPageBlobStore.MAX_GRANTS + 1,
                VirtualPageRequest.MAX_BYTES, "");
        VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> first = pager.page(
                "NOTIFICATION-WINDOW", "scope-window", values, request,
                VirtualSystemServicePageAdapters.NOTIFICATION);
        require(first.items().size() == VirtualPageBlobStore.MAX_GRANTS,
                "page did not stop at active blob-grant window");
        require(first.blobs().size() == VirtualPageBlobStore.MAX_GRANTS && !first.nextPageToken().isEmpty(),
                "blob-grant window did not emit a resumable page");
        for (VirtualPageBlob descriptor : first.blobs()) {
            try (ParcelFileDescriptor opened = pager.openBlob("scope-window", descriptor.blobToken())) {
                require(opened.getFileDescriptor() != null, "blob descriptor was not opened");
            }
        }
        VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> second = pager.page(
                "NOTIFICATION-WINDOW", "scope-window", values,
                new VirtualPageRequest(VirtualPageBlobStore.MAX_GRANTS + 1, VirtualPageRequest.MAX_BYTES,
                        first.nextPageToken()), VirtualSystemServicePageAdapters.NOTIFICATION);
        require(second.items().size() == 1 && second.blobs().size() == 1
                        && second.nextPageToken().isEmpty(),
                "blob-grant window did not resume after one-time handles were consumed");
    }

    private static void blobGrantRollbackAndExactBudget() throws Exception {
        File root = Files.createTempDirectory("virtual-page-blob-rollback-test").toFile();
        byte[] payload = new byte[100 * 1024];
        Arrays.fill(payload, (byte) 7);
        String wide = "x".repeat(5_000);
        List<VirtualNotificationSnapshot> values = List.of(
                notification(11, wide, payload), notification(12, wide, payload));
        try (VirtualSystemServicePager pager = new VirtualSystemServicePager(root)) {
            VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> probe = pager.page(
                    "NOTIFICATION-EXACT", "scope-exact", values,
                    new VirtualPageRequest(1, VirtualPageRequest.MAX_BYTES, ""),
                    VirtualSystemServicePageAdapters.NOTIFICATION);
            require(probe.estimatedBytes() > VirtualPageRequest.MIN_BYTES,
                    "exact-budget fixture did not exceed minimum page budget");
            require(!probe.nextPageToken().isEmpty() && probe.blobs().size() == 1,
                    "exact-budget fixture did not reserve a continuation token and blob grant");
            try (ParcelFileDescriptor descriptor = pager.openBlob(
                    "scope-exact", probe.blobs().get(0).blobToken())) {
                require(descriptor.getFileDescriptor() != null, "probe blob descriptor available");
            }

            VirtualSystemServicePager.PageSlice<VirtualNotificationSnapshot> exact = pager.page(
                    "NOTIFICATION-EXACT", "scope-exact", values,
                    new VirtualPageRequest(1, probe.estimatedBytes(), ""),
                    VirtualSystemServicePageAdapters.NOTIFICATION);
            require(exact.estimatedBytes() == probe.estimatedBytes(),
                    "exact continuation-token budget changed between identical pages");
            try (ParcelFileDescriptor descriptor = pager.openBlob(
                    "scope-exact", exact.blobs().get(0).blobToken())) {
                require(descriptor.getFileDescriptor() != null, "exact blob descriptor available");
            }

            int grantsBefore = pager.blobGrantCountForTest();
            int bytesBefore = pager.blobBytesForTest();
            expect(IllegalStateException.class, "ITEM_EXCEEDS_BINDER_BUDGET", () -> pager.page(
                    "NOTIFICATION-EXACT", "scope-exact", values,
                    new VirtualPageRequest(1, probe.estimatedBytes() - 1, ""),
                    VirtualSystemServicePageAdapters.NOTIFICATION));
            require(pager.blobGrantCountForTest() == grantsBefore
                            && pager.blobBytesForTest() == bytesBefore,
                    "pre-registration budget rejection leaked a blob grant");

            for (int attempt = 0; attempt < 3; attempt++) {
                expect(IllegalStateException.class, "TEST_PAGE_ASSEMBLY_FAILURE", () -> pager.page(
                        "NOTIFICATION-ROLLBACK", "scope-rollback", values,
                        new VirtualPageRequest(2, VirtualPageRequest.MAX_BYTES, ""),
                        failingAfterFirstBlobAdapter()));
                require(pager.blobGrantCountForTest() == grantsBefore
                                && pager.blobBytesForTest() == bytesBefore,
                        "failed page assembly leaked blob grants or payload budget");
            }
        }
    }

    private static VirtualNotificationSnapshot notification(int id, String text, byte[] payload) {
        return new VirtualNotificationSnapshot(id, id, text, "host-" + id, "channel",
                VirtualNotificationSnapshot.ACTIVE, "revision", "", "", List.of(),
                false, "", payload, id);
    }

    private static VirtualSystemServicePager.BinaryAdapter<VirtualNotificationSnapshot>
            failingAfterFirstBlobAdapter() {
        return new VirtualSystemServicePager.BinaryAdapter<>() {
            private int payloadCalls;
            @Override public String fieldName() {
                return VirtualSystemServicePageAdapters.NOTIFICATION.fieldName();
            }
            @Override public byte[] payload(VirtualNotificationSnapshot value) {
                if (++payloadCalls == 2) throw new IllegalStateException("TEST_PAGE_ASSEMBLY_FAILURE");
                return VirtualSystemServicePageAdapters.NOTIFICATION.payload(value);
            }
            @Override public VirtualNotificationSnapshot withoutPayload(
                    VirtualNotificationSnapshot value) {
                return VirtualSystemServicePageAdapters.NOTIFICATION.withoutPayload(value);
            }
        };
    }

    private static void accountSessionPaging() throws Exception {
        File root = Files.createTempDirectory("virtual-account-page-test").toFile();
        TestContext context = new TestContext(root);
        PackageServiceDependencies dependencies = dependencies(context, root);
        LiveBinder runtimeAuthority = new LiveBinder();
        dependencies.capabilityRegistry.installRuntime(runtimeAuthority, 0, 1);
        PackageVirtualSystemServiceSession session = new PackageVirtualSystemServiceSession(
                dependencies, 0, new LiveBinder(), new VirtualSystemServiceStore.Scope("account.pkg", 4),
                12004, "account.pkg", 1L, "account-revision", runtimeAuthority, 0L,
                PackageCallerVerifier.HOST_RUNTIME_ROLE);
        try {
            for (int index = 0; index < 40; index++) {
                String name = String.format(java.util.Locale.ROOT, "user-%02d", index);
                require(session.addAccount(name, "mail", "password-" + index), "account add failed");
                session.setAuthToken(name, "mail", "access", "token-" + index);
            }
            VirtualAccountPage first = session.listAccountsPage("mail",
                    new VirtualPageRequest(10, 32 * 1024, ""));
            require(first.items().size() == 10 && !first.nextPageToken().isEmpty(),
                    "account first page did not respect item budget");
            for (var summary : first.items()) {
                require(summary.name().startsWith("user-") && summary.type().equals("mail"),
                        "account summary identity invalid");
            }
            expect(IllegalStateException.class, "PAGING_REQUIRED", () -> session.listAccounts("mail"));
            String staleToken = first.nextPageToken();
            require(session.removeAccount("user-39", "mail"), "account mutation failed");
            expect(IllegalStateException.class, "PAGE_TOKEN_STALE", () -> session.listAccountsPage(
                    "mail", new VirtualPageRequest(10, 32 * 1024, staleToken)));

            PackageVirtualSystemServiceSession small = new PackageVirtualSystemServiceSession(
                    dependencies, 0, new LiveBinder(), new VirtualSystemServiceStore.Scope("small.pkg", 5),
                    12005, "small.pkg", 1L, "small-revision", runtimeAuthority, 0L,
                    PackageCallerVerifier.HOST_RUNTIME_ROLE);
            try {
                require(small.addAccount("one", "mail", "secret"), "small account add failed");
                small.setAuthToken("one", "mail", "access", "sensitive-token");
                List<VirtualAccountSnapshot> legacy = small.listAccounts("mail");
                require(legacy.size() == 1, "small legacy account list failed");
                require(legacy.get(0).password().isEmpty() && legacy.get(0).tokens().isEmpty()
                                && legacy.get(0).tokenTypes().isEmpty(),
                        "legacy account list leaked password or token material");
            } finally {
                small.close();
            }
        } finally {
            session.close();
            dependencies.close();
        }
    }

    private static List<VirtualSettingSnapshot> settings(int count, String prefix) {
        ArrayList<VirtualSettingSnapshot> out = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            out.add(new VirtualSettingSnapshot("secure",
                    String.format(java.util.Locale.ROOT, "key-%03d", index), prefix + index, index));
        }
        return List.copyOf(out);
    }

    private static PackageServiceDependencies dependencies(Context context, File root) {
        return new PackageServiceDependencies(root,
                new SandboxPackageLifecycle(context), new PackageCallerVerifier(context),
                new VirtualPackageStateBuilder(context), new HostPermissionStateResolver(context),
                new VirtualSystemServiceStore(root), new VirtualDeviceServiceStore(root),
                new VirtualInteractionStore(root), new VirtualNetworkServiceStore(root),
                new ApplicationEnvironmentStore(root), new VirtualCompatibilityStore(root),
                new VirtualPolicyServicesStore(root), new VirtualMediaCommunicationStore(root),
                new VirtualPeripheralServicesStore(root), new VirtualPrivilegedServicesStore(root));
    }

    private static final class TestContext extends Context {
        private final File root;
        TestContext(File root) { this.root = root; }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return root; }
        @Override public File getDataDir() { return root; }
        @Override public File getCacheDir() { return new File(root, "cache"); }
        @Override public File getCodeCacheDir() { return new File(root, "code-cache"); }
        @Override public File getNoBackupFilesDir() { return new File(root, "no-backup"); }
    }

    private static final class LiveBinder implements IBinder {
        @Override public boolean isBinderAlive() { return true; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) { }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    }

    private static void expect(Class<? extends Throwable> type, String code, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + type.getSimpleName() + ":" + code);
        } catch (Throwable error) {
            if (!type.isInstance(error) || error.getMessage() == null || !error.getMessage().contains(code)) {
                throw new AssertionError("expected " + type.getSimpleName() + ":" + code + " but got " + error, error);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
