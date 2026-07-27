package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GuestPackageSpec {
    final int protocol;
    public final String sessionId;
    public final long generation;
    public final String packageName;
    final int virtualUserId;
    final int virtualUid;
    final int processSlot;
    final String processName;
    final String apkPath;
    final String apkSha256;
    final long apkVersionCode;
    final String packageRevision;
    final String nativeLibraryDir;
    final String applicationClass;
    public final String componentClass;
    final String dataRoot;
    final List<String> permissions;
    final VirtualPackageStateSnapshot packageState;

    public GuestPackageSpec(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("request is required");
        protocol = bundle.getInt(RuntimeKeys.PROTOCOL, 0);
        if (!RuntimeProtocol.isCompatible(protocol)) throw new IllegalArgumentException("UNSUPPORTED_PROTOCOL:" + protocol);
        sessionId = required(bundle, RuntimeKeys.SESSION_ID);
        generation = bundle.getLong(RuntimeKeys.GENERATION, 0);
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        packageName = required(bundle, RuntimeKeys.PACKAGE_NAME);
        virtualUserId = bundle.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        virtualUid = bundle.getInt(RuntimeKeys.VIRTUAL_UID, -1);
        if (virtualUid < 0) throw new IllegalArgumentException("virtualUid must be non-negative");
        processSlot = bundle.getInt(RuntimeKeys.PROCESS_SLOT, -1);
        if (processSlot < 0 || processSlot > 7) throw new IllegalArgumentException("processSlot out of range");
        processName = bundle.getString(RuntimeKeys.PROCESS_NAME, packageName);
        if (processName == null || processName.trim().isEmpty()) throw new IllegalArgumentException("processName is required");
        apkPath = required(bundle, RuntimeKeys.APK_PATH);
        apkSha256 = required(bundle, RuntimeKeys.APK_SHA256);
        apkVersionCode = bundle.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        if (apkVersionCode < 0) throw new IllegalArgumentException("apkVersionCode must be non-negative");
        packageRevision = required(bundle, RuntimeKeys.PACKAGE_REVISION);
        nativeLibraryDir = bundle.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        applicationClass = bundle.getString(RuntimeKeys.APPLICATION_CLASS, "");
        componentClass = bundle.getString(RuntimeKeys.COMPONENT_CLASS, "");
        dataRoot = required(bundle, RuntimeKeys.DATA_ROOT);
        ArrayList<String> requested = bundle.getStringArrayList(RuntimeKeys.PERMISSIONS);
        permissions = requested == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(requested));
        packageState = bundle.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (packageState == null) throw new IllegalArgumentException("virtual package state is required");
        if (!packageName.equals(packageState.packageName()) || virtualUserId != packageState.virtualUserId()) {
            throw new SecurityException("VIRTUAL_PACKAGE_STATE_IDENTITY_MISMATCH");
        }
        if (!apkSha256.equals(packageState.apkSha256())) {
            throw new SecurityException("VIRTUAL_PACKAGE_STATE_REVISION_MISMATCH");
        }
    }

    Bundle toBundle() {
        Bundle out = new Bundle();
        out.putInt(RuntimeKeys.PROTOCOL, protocol);
        out.putString(RuntimeKeys.SESSION_ID, sessionId);
        out.putLong(RuntimeKeys.GENERATION, generation);
        out.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        out.putInt(RuntimeKeys.VIRTUAL_UID, virtualUid);
        out.putInt(RuntimeKeys.PROCESS_SLOT, processSlot);
        out.putString(RuntimeKeys.PROCESS_NAME, processName);
        out.putString(RuntimeKeys.APK_PATH, apkPath);
        out.putString(RuntimeKeys.APK_SHA256, apkSha256);
        out.putLong(RuntimeKeys.APK_VERSION_CODE, apkVersionCode);
        out.putString(RuntimeKeys.PACKAGE_REVISION, packageRevision);
        out.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, nativeLibraryDir);
        out.putString(RuntimeKeys.APPLICATION_CLASS, applicationClass);
        out.putString(RuntimeKeys.COMPONENT_CLASS, componentClass);
        out.putString(RuntimeKeys.DATA_ROOT, dataRoot);
        out.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>(permissions));
        out.putParcelable(RuntimeKeys.PACKAGE_STATE, packageState);
        return out;
    }

    File apkFile() { return new File(apkPath); }
    File dataRootFile() { return new File(dataRoot); }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
