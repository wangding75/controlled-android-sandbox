package com.warden.controlledsandbox.runtime.guest;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import java.util.List;

/** Verifies P1-07's exact hidden-PMS signatures without relying on method enumeration order. */
public final class HostPackageManagerBridgeSelfTest {
    public static void main(String[] args) {
        ExactPms pms = new ExactPms();
        Intent serviceIntent = new Intent("test.SERVICE").setPackage("host.system.service");

        HostPackageManagerBridge.Lookup<ServiceInfo> service =
                HostPackageManagerBridge.resolveService(pms, serviceIntent, 17);
        require(service.isResult() && service.value() != null
                        && "host.system.service.Service".equals(service.value().name),
                "resolveService uses exact long-flags signature");
        require(pms.resolveCalls == 1 && !pms.wrongResolveOverloadCalled
                        && pms.resolveIntent == serviceIntent && pms.resolveType == null
                        && pms.resolveFlags == 0L && pms.resolveUserId == 17,
                "resolveService keeps Intent/null MIME/long flags/userId positional");

        pms.returnResolveEmpty = true;
        HostPackageManagerBridge.Lookup<ServiceInfo> queryFallback =
                HostPackageManagerBridge.resolveService(pms, serviceIntent, 23);
        require(queryFallback.isResult() && queryFallback.value() != null
                        && "host.system.service.QueriedService".equals(queryFallback.value().name),
                "queryIntentServices Slice-like result is unwrapped");
        require(pms.queryCalls == 1 && !pms.wrongQueryOverloadCalled
                        && pms.queryIntent == serviceIntent && pms.queryType == null
                        && pms.queryFlags == 0L && pms.queryUserId == 23,
                "queryIntentServices preserves null MIME and userId");

        HostPackageManagerBridge.Lookup<ProviderInfo> provider =
                HostPackageManagerBridge.resolveContentProvider(pms, "host.authority", 0x1_0000_0001L,
                        29);
        require(provider.isResult() && provider.value() != null
                        && "host.authority.Provider".equals(provider.value().name),
                "resolveContentProvider uses exact three-parameter signature");
        require(!pms.wrongProviderOverloadCalled && "host.authority".equals(pms.authority)
                        && pms.providerFlags == 0x1_0000_0001L && pms.providerUserId == 29,
                "Provider authority/long flags/userId do not shift between overloads");

        pms.returnQueryEmpty = true;
        HostPackageManagerBridge.Lookup<ServiceInfo> empty =
                HostPackageManagerBridge.resolveService(pms, serviceIntent, 31);
        require(empty.isEmpty(), "both normal null/empty returns produce normal EMPTY");

        IntOnlyPms legacy = new IntOnlyPms();
        HostPackageManagerBridge.Lookup<ServiceInfo> legacyService =
                HostPackageManagerBridge.resolveService(legacy, serviceIntent, 41);
        require(legacyService.isResult() && legacy.resolveType == null && legacy.resolveFlags == 0
                        && legacy.resolveUserId == 41,
                "API32 int flags is an explicit supported signature, not a guessed overload");
        HostPackageManagerBridge.Lookup<ProviderInfo> legacyProvider =
                HostPackageManagerBridge.resolveContentProvider(legacy, "legacy.authority", 7L, 43);
        require(legacyProvider.isResult() && "legacy.authority".equals(legacy.authority)
                        && legacy.providerFlags == 7 && legacy.providerUserId == 43,
                "API32 Provider uses its explicit int flags signature");
        HostPackageManagerBridge.Lookup<ProviderInfo> unsupported =
                HostPackageManagerBridge.resolveContentProvider(legacy, "legacy.authority",
                        0x1_0000_0000L, 43);
        require(unsupported.status() == HostPackageManagerBridge.LookupStatus.UNSUPPORTED_SIGNATURE,
                "unrepresentable API32 flags are not truncated or treated as absent");

        HostPackageManagerBridge.Lookup<ProviderInfo> failure =
                HostPackageManagerBridge.resolveContentProvider(new ThrowingPms(), "broken", 0L, 0);
        require(failure.status() == HostPackageManagerBridge.LookupStatus.INVOCATION_FAILURE
                        && failure.diagnostic().contains("resolveContentProvider(String,long,int)"),
                "invocation failure is distinct from a missing provider");
    }

    public static final class ExactPms {
        boolean returnResolveEmpty;
        boolean returnQueryEmpty;
        boolean wrongResolveOverloadCalled;
        boolean wrongQueryOverloadCalled;
        boolean wrongProviderOverloadCalled;
        int resolveCalls;
        int queryCalls;
        Intent resolveIntent;
        String resolveType;
        long resolveFlags;
        int resolveUserId;
        Intent queryIntent;
        String queryType;
        long queryFlags;
        int queryUserId;
        String authority;
        long providerFlags;
        int providerUserId;

        public ResolveInfo resolveService(Intent intent, String type, int flags, int userId) {
            wrongResolveOverloadCalled = true;
            return service("wrong.Resolve");
        }

        public ResolveInfo resolveService(Intent intent, String type, long flags, int userId) {
            resolveCalls++;
            resolveIntent = intent;
            resolveType = type;
            resolveFlags = flags;
            resolveUserId = userId;
            return returnResolveEmpty ? null : service("host.system.service.Service");
        }

        public List<ResolveInfo> queryIntentServices(Intent intent, String type, int flags,
                                                      int userId) {
            wrongQueryOverloadCalled = true;
            return List.of(service("wrong.Query"));
        }

        public SliceLike queryIntentServices(Intent intent, String type, long flags, int userId) {
            queryCalls++;
            queryIntent = intent;
            queryType = type;
            queryFlags = flags;
            queryUserId = userId;
            return new SliceLike(returnQueryEmpty ? List.of()
                    : List.of(service("host.system.service.QueriedService")));
        }

        public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
            wrongProviderOverloadCalled = true;
            return provider("wrong.Provider");
        }

        public ProviderInfo resolveContentProvider(String name, long flags, int userId) {
            authority = name;
            providerFlags = flags;
            providerUserId = userId;
            return provider("host.authority.Provider");
        }
    }

    public static final class IntOnlyPms {
        String resolveType;
        int resolveFlags;
        int resolveUserId;
        String authority;
        int providerFlags;
        int providerUserId;

        public ResolveInfo resolveService(Intent intent, String type, int flags, int userId) {
            resolveType = type;
            resolveFlags = flags;
            resolveUserId = userId;
            return service("legacy.Service");
        }

        public List<ResolveInfo> queryIntentServices(Intent intent, String type, int flags,
                                                      int userId) {
            return List.of();
        }

        public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
            authority = name;
            providerFlags = flags;
            providerUserId = userId;
            return provider("legacy.Provider");
        }
    }

    public static final class ThrowingPms {
        public ProviderInfo resolveContentProvider(String name, long flags, int userId) {
            throw new IllegalStateException("fixture invocation failure");
        }
    }

    public static final class SliceLike {
        private final List<ResolveInfo> values;
        SliceLike(List<ResolveInfo> values) { this.values = values; }
        public List<ResolveInfo> getList() { return values; }
    }

    private static ResolveInfo service(String name) {
        ResolveInfo result = new ResolveInfo();
        result.serviceInfo = new ServiceInfo();
        result.serviceInfo.name = name;
        result.serviceInfo.packageName = "host.system.service";
        result.serviceInfo.applicationInfo = new ApplicationInfo();
        result.serviceInfo.applicationInfo.packageName = result.serviceInfo.packageName;
        return result;
    }

    private static ProviderInfo provider(String name) {
        ProviderInfo result = new ProviderInfo();
        result.name = name;
        result.packageName = "host.system.provider";
        result.applicationInfo = new ApplicationInfo();
        result.applicationInfo.packageName = result.packageName;
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
