package com.warden.controlledsandbox.framework.identity;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic PMS projection test for split paths and signer-bearing PackageInfo results. */
public final class VirtualPackageMetadataProjectionSelfTest {
    public static void main(String[] args) throws Exception {
        ApplicationInfo application = new ApplicationInfo();
        application.packageName = "guest.projection";
        application.sourceDir = "/data/cas/base.apk";
        application.publicSourceDir = application.sourceDir;
        application.splitSourceDirs = new String[]{"/data/cas/split_feature.apk"};
        application.splitPublicSourceDirs = application.splitSourceDirs.clone();
        String digest = repeat('a');
        VirtualPackageMetadata metadata = new VirtualPackageMetadata(
                "guest.projection", "guest.projection.Main", application, List.of(),
                "7.2", 72L, digest, 100L, 200L, "com.warden.virtualinstaller",
                List.of(), List.of(), List.of(), List.of(), true,
                Set.of(), Set.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(new byte[]{1, 2, 3, 4}));

        PackageInfo info = metadata.packageInfo(0x00000040L | 0x08000000L);
        require(info.applicationInfo != null, "PackageInfo contains ApplicationInfo");
        require("/data/cas/base.apk".equals(info.applicationInfo.sourceDir),
                "PackageInfo sourceDir remains virtual package state");
        require(info.applicationInfo.splitSourceDirs != null
                        && info.applicationInfo.splitSourceDirs.length == 1,
                "PackageInfo splitSourceDirs remains visible");
        Field signatures = PackageInfo.class.getField("signatures");
        Object signatureArray = signatures.get(info);
        require(signatureArray != null && Array.getLength(signatureArray) == 1,
                "GET_SIGNATURES returns virtual signer");
        require(info.signingInfo != null, "GET_SIGNING_CERTIFICATES returns SigningInfo");
        System.out.println("PASS virtual PackageInfo split/signature projection self-test");
    }

    private static String repeat(char value) {
        char[] output = new char[64];
        java.util.Arrays.fill(output, value);
        return new String(output);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
