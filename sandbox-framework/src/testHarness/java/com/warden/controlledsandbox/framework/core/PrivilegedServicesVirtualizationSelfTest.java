package com.warden.controlledsandbox.framework.core;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side M5-T17 privileged service behavior tests. */
public final class PrivilegedServicesVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity("STATIC");
        testSearch(identity);
        testStorage(identity);
        testGraphics(identity);
        testContextHub(identity);
        testPersistentData(identity);
        testSystemUpdate(identity);
        testHostPassThrough();
        System.out.println("PASS M5-T17 privileged-services virtualization self-test");
    }

    private static void testSearch(GuestIdentity identity) {
        SearchApi api = proxy(SearchApi.class, new SearchDelegate(), identity, "search");
        Object value = api.getGlobalSearchActivity();
        require(value instanceof ComponentName component
                        && "guest.search".equals(component.getPackageName()),
                "global search component projected");
        require(api.isGlobalSearchEnabled(), "global search state projected");
        require(api.getSuggestionAuthorities().length == 1, "search authority list projected");
    }

    private static void testStorage(GuestIdentity identity) {
        StorageApi api = proxy(StorageApi.class, new StorageDelegate(), identity, "storagestats");
        require(api.getTotalBytes() == 1_000_000L && api.getFreeBytes() == 600_000L,
                "storage totals projected");
        require(api.isQuotaSupported() && api.queryStatsForPackage().get("appBytes") == 10_000L,
                "storage quota and package attribution projected");
    }

    private static void testGraphics(GuestIdentity identity) {
        GraphicsApi api = proxy(GraphicsApi.class, new GraphicsDelegate(), identity, "graphicsstats");
        Bundle stats = api.getStats();
        require(stats.getLong("total_frames", -1L) == 100L,
                "graphics counters projected");
        Object first = new Object();
        api.requestBufferForProcess(first);
        boolean quota = false;
        try {
            api.requestBufferForProcess(new Object());
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "graphics buffer quota enforced");
        boolean saveMutationDenied = false;
        try { api.addToSaveBuffer(first); }
        catch (SecurityException expected) { saveMutationDenied = true; }
        require(saveMutationDenied, "add-to-save-buffer must not be misclassified as cleanup");
        api.saveBufferForProcess(first);
        api.requestBufferForProcess(new Object());
    }

    private static void testContextHub(GuestIdentity identity) {
        ContextHubApi api = proxy(ContextHubApi.class, new ContextHubDelegate(), identity, "contexthub");
        require(api.getContextHubHandles().length == 1 && api.getContextHubHandles()[0] == 7,
                "ContextHub handle list projected");
        require(api.getContextHubInfo(7) instanceof VirtualContextHubSnapshot,
                "ContextHub info projected in host test surface");
        Object first = new Object();
        api.createClient(first);
        boolean quota = false;
        try {
            api.createClient(new Object());
        } catch (IllegalStateException expected) {
            quota = expected.getMessage().contains("LIMIT");
        }
        require(quota, "ContextHub client quota enforced");
        api.closeClient(first);
        api.createClient(new Object());
        boolean mutation = false;
        try {
            api.loadNanoApp();
        } catch (SecurityException expected) {
            mutation = expected.getMessage().contains("MUTATION_DENIED");
        }
        require(mutation, "ContextHub nanoapp mutation denied");
    }

    private static void testPersistentData(GuestIdentity identity) {
        PersistentDelegate delegate = new PersistentDelegate();
        PersistentApi api = proxy(PersistentApi.class, delegate, identity,
                "persistentDataBlock");
        require(api.read().length == 3 && api.getDataBlockSize() == 3,
                "persistent data block projected");
        require(api.write(new byte[]{9, 8}) == 2 && api.read()[0] == 9,
                "bounded persistent data overlay written");
        boolean tooLarge = false;
        try {
            api.write(new byte[17]);
        } catch (IllegalArgumentException expected) {
            tooLarge = expected.getMessage().contains("LIMIT");
        }
        require(tooLarge, "persistent data block size limit enforced");
        boolean wipeDenied = false;
        try {
            api.wipe();
        } catch (SecurityException expected) {
            wipeDenied = expected.getMessage().contains("WIPE_DENIED");
        }
        require(wipeDenied, "persistent data wipe denied");
        boolean frpDenied = false;
        try {
            api.setFactoryResetProtectionSecret(new byte[32]);
        } catch (UnsupportedOperationException expected) {
            frpDenied = expected.getMessage().contains("OPERATION_UNSUPPORTED");
        }
        require(frpDenied, "unsupported FRP mutation fails closed");
        require(delegate.calls == 0, "persistent data virtualization does not call Host");
    }

    private static void testSystemUpdate(GuestIdentity identity) {
        SystemUpdateApi api = proxy(SystemUpdateApi.class, new SystemUpdateDelegate(), identity,
                "systemUpdate");
        require(api.retrieveSystemUpdateInfo().getInt("progress", -1) == 42,
                "system update state projected");
        Bundle submitted = new Bundle();
        submitted.putInt("progress", 77);
        api.updateSystemUpdateInfo(submitted);
        require(api.retrieveSystemUpdateInfo().getInt("progress", -1) == 77,
                "system update submission isolated in guest process");
    }

    private static void testHostPassThrough() {
        SearchDelegate delegate = new SearchDelegate();
        SearchApi api = proxy(SearchApi.class, delegate, identity("HOST"), "search");
        require("host".equals(api.getGlobalSearchActivity()) && delegate.calls == 1,
                "HOST search passes through");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(String mode) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
        app.uid = 12001;
        VirtualDeviceServiceProfileSnapshot device = new VirtualDeviceServiceProfileSnapshot(1L, 0L,
                new VirtualLocationProfileSnapshot("BLOCKED", "", false, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot("STATIC", "0123456789abcdef", "serial",
                        "11111111-2222-3333-4444-555555555555", true, "install", "b", "m",
                        "model", "d", "p", "fp", "board", "hw"),
                new VirtualTelephonyProfileSnapshot("BLOCKED", -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot("BLOCKED", false, "", "", "", 0, -1, 0,
                        -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot("BLOCKED", false, 10, "", "", false,
                        List.of(), List.of()),
                new VirtualSensorProfileSnapshot("BLOCKED", 1, List.of()));
        VirtualPrivilegedServicesProfileSnapshot privileged = new VirtualPrivilegedServicesProfileSnapshot(
                1L, 0L,
                new VirtualSearchProfileSnapshot(mode, true, true,
                        "guest.search/.GlobalSearch", "guest.search/.WebSearch",
                        List.of("guest.search/.SearchActivity"), List.of("guest.search.suggest"), 20),
                new VirtualStorageStatsProfileSnapshot(mode, 1_000_000L, 600_000L,
                        100_000L, 10_000L, 20_000L, 3_000L, 1_000L, true, true),
                new VirtualGraphicsStatsProfileSnapshot(mode, true, true, 1, 100L, 5L, 10L),
                new VirtualContextHubProfileSnapshot(mode, true, true, true, false, 1,
                        List.of(new VirtualContextHubSnapshot(
                                7, "Virtual Hub", "Warden", 1024, List.of("0x1234")))),
                new VirtualPersistentDataBlockProfileSnapshot(mode, true, true, false,
                        16, new byte[]{1, 2, 3}, false, 1, true),
                new VirtualSystemUpdateProfileSnapshot(mode, true, true,
                        "IN_PROGRESS", "Update", "1.2.3", "2026-07-01", 42, 100L));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of(), "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", app, List.of()), "guest.pkg", 0, 1,
                new VirtualPermissionPolicy(Set.of(), Map.of()), new SandboxAppOpsPolicy(Map.of()),
                event -> { }, new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(device, null, null, null, null, null, null, null, privileged),
                "rev");
    }

    interface SearchApi {
        Object getGlobalSearchActivity();
        boolean isGlobalSearchEnabled();
        String[] getSuggestionAuthorities();
    }
    interface StorageApi {
        long getTotalBytes();
        long getFreeBytes();
        boolean isQuotaSupported();
        Map<String, Long> queryStatsForPackage();
    }
    interface GraphicsApi {
        Bundle getStats();
        void requestBufferForProcess(Object token);
        void addToSaveBuffer(Object token);
        void saveBufferForProcess(Object token);
    }
    interface ContextHubApi {
        int[] getContextHubHandles();
        Object getContextHubInfo(int id);
        String createClient(Object token);
        void closeClient(Object token);
        void loadNanoApp();
    }
    interface PersistentApi {
        byte[] read();
        int write(byte[] value);
        int getDataBlockSize();
        void wipe();
        void setFactoryResetProtectionSecret(byte[] secret);
    }
    interface SystemUpdateApi {
        Bundle retrieveSystemUpdateInfo();
        void updateSystemUpdateInfo(Bundle value);
    }

    static final class SearchDelegate implements SearchApi {
        int calls;
        public Object getGlobalSearchActivity() { calls++; return "host"; }
        public boolean isGlobalSearchEnabled() { return false; }
        public String[] getSuggestionAuthorities() { return new String[]{"host"}; }
    }
    static final class StorageDelegate implements StorageApi {
        public long getTotalBytes() { return 9; }
        public long getFreeBytes() { return 8; }
        public boolean isQuotaSupported() { return false; }
        public Map<String, Long> queryStatsForPackage() { return Map.of("host", 1L); }
    }
    static final class GraphicsDelegate implements GraphicsApi {
        public Bundle getStats() { return new Bundle(); }
        public void requestBufferForProcess(Object token) { throw new AssertionError("delegate"); }
        public void addToSaveBuffer(Object token) { throw new AssertionError("delegate"); }
        public void saveBufferForProcess(Object token) { throw new AssertionError("delegate"); }
    }
    static final class ContextHubDelegate implements ContextHubApi {
        public int[] getContextHubHandles() { return new int[]{99}; }
        public Object getContextHubInfo(int id) { return "host"; }
        public String createClient(Object token) { return "host"; }
        public void closeClient(Object token) { throw new AssertionError("delegate"); }
        public void loadNanoApp() { throw new AssertionError("delegate"); }
    }
    static final class PersistentDelegate implements PersistentApi {
        int calls;
        public byte[] read() { calls++; return new byte[]{99}; }
        public int write(byte[] value) { calls++; return value.length; }
        public int getDataBlockSize() { calls++; return 99; }
        public void wipe() { calls++; }
        public void setFactoryResetProtectionSecret(byte[] secret) { calls++; }
    }
    static final class SystemUpdateDelegate implements SystemUpdateApi {
        public Bundle retrieveSystemUpdateInfo() { return new Bundle(); }
        public void updateSystemUpdateInfo(Bundle value) { }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
