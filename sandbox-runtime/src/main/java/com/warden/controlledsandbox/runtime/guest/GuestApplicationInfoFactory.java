package com.warden.controlledsandbox.runtime.guest;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.io.File;

/** Builds Guest ApplicationInfo without copying Host-only identity or process metadata. */
final class GuestApplicationInfoFactory {
    private GuestApplicationInfoFactory() { }

    static ApplicationInfo create(GuestPackageSpec spec, String dataDir) {
        return create(spec, dataDir, null);
    }

    static ApplicationInfo create(GuestPackageSpec spec, String dataDir, Bundle metaData) {
        return create(spec, dataDir, metaData, "");
    }

    static ApplicationInfo create(GuestPackageSpec spec, String dataDir, Bundle metaData,
                                  String appComponentFactory) {
        return create(spec, dataDir, metaData, appComponentFactory, null);
    }

    /**
     * Projects the platform-parsed APK ApplicationInfo instead of rebuilding a partial record.
     * The source object is guest APK metadata; all identity-bearing fields are overwritten below
     * so no host UID/data/process path can cross the sandbox boundary.
     */
    static ApplicationInfo create(GuestPackageSpec spec, String dataDir, Bundle metaData,
                                  String appComponentFactory, ApplicationInfo parsed) {
        ApplicationInfo info = parsed == null
                ? new ApplicationInfo() : new ApplicationInfo(parsed);
        info.packageName = spec.packageName;
        info.name = emptyToNull(spec.applicationClass);
        info.className = emptyToNull(spec.applicationClass);
        info.processName = spec.processName;
        info.sourceDir = spec.apkPath;
        info.publicSourceDir = spec.apkPath;
        setOptionalField(info, "splitNames", spec.splitNames.toArray(new String[0]));
        info.splitSourceDirs = spec.splitPathArray();
        info.splitPublicSourceDirs = spec.splitPathArray();
        info.nativeLibraryDir = GuestNativeRuntimeProjection.select(spec, new File(dataDir),
                spec.effectiveNativeLibraryDir());
        setOptionalField(info, "primaryCpuAbi", emptyToNull(spec.nativeAbi));
        setOptionalField(info, "secondaryCpuAbi", null);
        setOptionalField(info, "sharedLibraryFiles", null);
        ApplicationInfo packageTemplate = spec.packageState.applicationInfo();
        if (packageTemplate != null) info.flags = packageTemplate.flags;
        info.dataDir = dataDir;
        info.uid = spec.virtualUid;
        info.enabled = spec.packageState.enabled();
        info.appComponentFactory = emptyToNull(appComponentFactory);
        if (metaData != null && !metaData.isEmpty()) {
            Bundle merged = info.metaData == null ? new Bundle() : new Bundle(info.metaData);
            merged.putAll(metaData);
            info.metaData = merged;
        }
        return info;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void setOptionalField(ApplicationInfo info, String name, Object value) {
        try {
            java.lang.reflect.Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(info, value);
        } catch (NoSuchFieldException ignored) {
            // API 32 compile stubs omit some newer ABI metadata fields.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // ABI metadata is an optional projection; NativeLoader still receives the validated
            // GuestPackageSpec path and ABI as its authoritative input.
        }
    }
}
