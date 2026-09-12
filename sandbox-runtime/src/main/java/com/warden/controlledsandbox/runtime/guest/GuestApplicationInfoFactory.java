package com.warden.controlledsandbox.runtime.guest;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Build;
import java.io.File;
import java.util.List;

/** Builds Guest ApplicationInfo without copying Host-only identity or process metadata. */
public final class GuestApplicationInfoFactory {
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
        // Logical APK/library paths. NativePolicy maps /data/app/<pkg>/... onto the CAS
        // revision. Publishing spec.apkPath (Host files/packages/.../base.apk) in
        // ApplicationInfo is the same mixed-identity leak that made U4 classify a
        // "multi open env" and SIGKILL when nativeLibraryDir was a Host path.
        info.sourceDir = logicalApkPath(spec);
        info.publicSourceDir = info.sourceDir;
        setOptionalField(info, "splitNames", spec.splitNames.toArray(new String[0]));
        info.splitSourceDirs = spec.splitPathArray();
        info.splitPublicSourceDirs = spec.splitPathArray();
        info.nativeLibraryDir = logicalNativeLibraryDir(spec);
        setOptionalField(info, "primaryCpuAbi", emptyToNull(spec.nativeAbi));
        setOptionalField(info, "secondaryCpuAbi", null);
        List<String> sharedLibraryFiles = GuestSharedLibraryPathResolver.resolvedSharedLibraryFiles(
                spec.packageState, spec.packageUniverse);
        setOptionalField(info, "sharedLibraryFiles", sharedLibraryFiles.isEmpty()
                ? null : sharedLibraryFiles.toArray(new String[0]));
        ApplicationInfo packageTemplate = spec.packageState.applicationInfo();
        if (packageTemplate != null) info.flags = packageTemplate.flags;
        info.dataDir = logicalDataDir(spec);
        info.uid = spec.virtualUid;
        info.enabled = spec.packageState.enabled();
        setOptionalField(info, "appComponentFactory", emptyToNull(appComponentFactory));
        if (metaData != null && !metaData.isEmpty()) {
            Bundle merged = info.metaData == null ? new Bundle() : new Bundle(info.metaData);
            merged.putAll(metaData);
            info.metaData = merged;
        }
        return info;
    }

    private static String logicalDataDir(GuestPackageSpec spec) {
        return "/data/user/" + spec.virtualUserId + "/" + spec.packageName;
    }

    private static String logicalApkPath(GuestPackageSpec spec) {
        if (spec.apkPath == null || spec.apkPath.trim().isEmpty()) return "";
        return "/data/app/" + spec.packageName + "/base.apk";
    }

    private static String logicalNativeLibraryDir(GuestPackageSpec spec) {
        String packagedNativeLibraryDir = spec.effectiveNativeLibraryDir();
        if (packagedNativeLibraryDir == null || packagedNativeLibraryDir.isEmpty()) return "";
        return "/data/app/" + spec.packageName + "/lib/" + spec.nativeAbi;
    }

    /** Reads the API-28 factory field without linking it on older platform images. */
    public static String readComponentFactory(ApplicationInfo info) {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "";
        return readApi28ComponentFactory(info);
    }

    @SuppressLint("NewApi")
    private static String readApi28ComponentFactory(ApplicationInfo info) {
        String value = info.appComponentFactory;
        return value == null ? "" : value.trim();
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
