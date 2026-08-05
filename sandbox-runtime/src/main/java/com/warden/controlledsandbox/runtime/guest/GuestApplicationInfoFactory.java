package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;

/** Builds Guest ApplicationInfo without copying Host-only identity or process metadata. */
final class GuestApplicationInfoFactory {
    private GuestApplicationInfoFactory() { }

    static ApplicationInfo create(GuestPackageSpec spec, String dataDir) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = spec.packageName;
        info.name = emptyToNull(spec.applicationClass);
        info.className = emptyToNull(spec.applicationClass);
        info.processName = spec.processName;
        info.sourceDir = spec.apkPath;
        info.publicSourceDir = spec.apkPath;
        info.splitSourceDirs = spec.splitPathArray();
        info.splitPublicSourceDirs = spec.splitPathArray();
        info.nativeLibraryDir = spec.nativeLibraryDir;
        info.dataDir = dataDir;
        info.uid = spec.virtualUid;
        info.enabled = spec.packageState.enabled();
        return info;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
