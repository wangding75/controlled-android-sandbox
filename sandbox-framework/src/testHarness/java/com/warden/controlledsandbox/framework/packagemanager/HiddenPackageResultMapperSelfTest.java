package com.warden.controlledsandbox.framework.packagemanager;

import android.content.pm.PackageManager;
import java.util.Collections;
import java.util.List;

public final class HiddenPackageResultMapperSelfTest {
    public static void main(String[] args) {
        HiddenPackageResultMapper.CollectionAdapter lists =
                (values, type) -> type == List.class ? values : Collections.emptyList();

        require(HiddenPackageResultMapper.map("getPackageInfo", Object.class, lists) == null,
                "getPackageInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getApplicationInfo", Object.class, lists) == null,
                "getApplicationInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getActivityInfo", Object.class, lists) == null,
                "getActivityInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getReceiverInfo", Object.class, lists) == null,
                "getReceiverInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getServiceInfo", Object.class, lists) == null,
                "getServiceInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getProviderInfo", Object.class, lists) == null,
                "getProviderInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("getInstrumentationInfo", Object.class, lists) == null,
                "getInstrumentationInfo is null / NameNotFound-shaped");
        require(HiddenPackageResultMapper.map("resolveActivity", Object.class, lists) == null,
                "resolveActivity is null");
        require(HiddenPackageResultMapper.map("getChangedPackages", Object.class, lists) == null,
                "getChangedPackages stays fail-closed");
        require(Integer.valueOf(-1).equals(
                        HiddenPackageResultMapper.map("getPackageUid", int.class, lists)),
                "getPackageUid is absent uid");
        require(Boolean.FALSE.equals(
                        HiddenPackageResultMapper.map("isPackageAvailable", boolean.class, lists)),
                "isPackageAvailable is false");
        require(Integer.valueOf(PackageManager.PERMISSION_DENIED).equals(
                        HiddenPackageResultMapper.map("checkPermission", int.class, lists)),
                "checkPermission is denied");
        Object queried = HiddenPackageResultMapper.map("queryIntentActivities", List.class, lists);
        require(queried instanceof List && ((List<?>) queried).isEmpty(),
                "queryIntentActivities is an empty list");

        boolean mutationBlocked = false;
        try {
            HiddenPackageResultMapper.map("setComponentEnabledSetting", void.class, lists);
        } catch (SecurityException expected) {
            mutationBlocked = "HOST_PACKAGE_MUTATION_BLOCKED".equals(expected.getMessage());
        }
        require(mutationBlocked, "mutation of a hidden package stays SecurityException");
        System.out.println("PASS hidden package result mapper self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
