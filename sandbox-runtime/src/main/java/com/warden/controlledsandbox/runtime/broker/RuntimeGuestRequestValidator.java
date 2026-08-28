package com.warden.controlledsandbox.runtime.broker;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import com.warden.controlledsandbox.contract.NativeGuestPolicyContract;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.session.PackageRevision;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Validates the immutable executable and process identity entering a Guest lease. */
final class RuntimeGuestRequestValidator {
    private final RuntimeBrokerService owner;

    RuntimeGuestRequestValidator(RuntimeBrokerService owner) {
        this.owner = java.util.Objects.requireNonNull(owner, "owner");
    }

    void validate(Bundle input) throws Exception {
        // Never trust a caller-supplied optimization bit.  Only this validator may mint the
        // Broker-issued proof after hashing the complete immutable base/split set below.
        input.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER, false);
        int protocol = input.getInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        if (!RuntimeProtocol.isCompatible(protocol)) {
            throw new IllegalArgumentException("UNSUPPORTED_PROTOCOL:" + protocol);
        }
        String packageName = RuntimeBrokerService.required(input, RuntimeKeys.PACKAGE_NAME);
        if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid package name");
        }
        int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (userId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        String processName = RuntimeBrokerService.processName(input, packageName);
        if (!processName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+(\\:[A-Za-z0-9_.]+)?")) {
            throw new IllegalArgumentException("Invalid process name");
        }
        input.putString(RuntimeKeys.PROCESS_NAME, processName);
        File apk = new File(RuntimeBrokerService.required(input, RuntimeKeys.APK_PATH))
                .getCanonicalFile();
        File privateRoot = owner.getFilesDir().getCanonicalFile();
        if (!apk.isFile()) throw new IllegalArgumentException("APK file is missing");
        if (!apk.toPath().startsWith(privateRoot.toPath())) {
            throw new SecurityException("APK path is outside app-private storage");
        }
        long apkVersionCode = input.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        String apkSha256 = RuntimeBrokerService.required(input, RuntimeKeys.APK_SHA256);
        String baseApkSha256 = input.getString(RuntimeKeys.BASE_APK_SHA256, apkSha256);
        ArrayList<String> splitNames = optionalStringList(input, RuntimeKeys.SPLIT_NAMES);
        ArrayList<String> splitTypes = optionalStringList(input, RuntimeKeys.SPLIT_TYPES);
        ArrayList<String> splitConfigFor = optionalStringList(input, RuntimeKeys.SPLIT_CONFIG_FOR);
        ArrayList<String> splitUses = optionalStringList(input, RuntimeKeys.SPLIT_USES);
        ArrayList<String> splitPaths = optionalStringList(input, RuntimeKeys.SPLIT_PATHS);
        ArrayList<String> splitSha256s = optionalStringList(input, RuntimeKeys.SPLIT_SHA256S);
        int splitCount = splitNames.size();
        if (splitCount > 255) throw new IllegalArgumentException("Too many split APKs");
        if (splitTypes.size() != splitCount || splitConfigFor.size() != splitCount
                || splitUses.size() != splitCount || splitPaths.size() != splitCount
                || splitSha256s.size() != splitCount) {
            throw new IllegalArgumentException("Split metadata arrays must have identical sizes");
        }
        Set<String> uniqueSplitNames = new HashSet<>();
        Set<String> uniqueSplitPaths = new HashSet<>();
        ArrayList<PackageRevisionSetVerifier.Artifact> splitArtifacts = new ArrayList<>();
        ArrayList<String> canonicalSplitPaths = new ArrayList<>();
        for (int index = 0; index < splitCount; index++) {
            String splitName = splitNames.get(index);
            if (splitName == null || splitName.trim().isEmpty()
                    || !uniqueSplitNames.add(splitName)) {
                throw new IllegalArgumentException("Split names must be non-empty and unique");
            }
            File splitFile = new File(splitPaths.get(index)).getCanonicalFile();
            if (!splitFile.isFile()) {
                throw new IllegalArgumentException("Split APK file is missing: " + splitName);
            }
            if (!splitFile.toPath().startsWith(privateRoot.toPath())) {
                throw new SecurityException("Split APK path is outside app-private storage: "
                        + splitName);
            }
            if (!uniqueSplitPaths.add(splitFile.getCanonicalPath())) {
                throw new IllegalArgumentException("Split APK paths must be unique");
            }
            canonicalSplitPaths.add(splitFile.getCanonicalPath());
            splitArtifacts.add(new PackageRevisionSetVerifier.Artifact(
                    splitName, splitTypes.get(index), splitConfigFor.get(index), splitUses.get(index),
                    splitFile, splitSha256s.get(index)));
        }
        PackageRevision revision = PackageRevisionSetVerifier.verify(
                apk, baseApkSha256, splitArtifacts, apkVersionCode, apkSha256);
        input.putString(RuntimeKeys.APK_PATH, apk.getCanonicalPath());
        input.putString(RuntimeKeys.BASE_APK_SHA256,
                baseApkSha256.toLowerCase(java.util.Locale.ROOT));
        input.putStringArrayList(RuntimeKeys.SPLIT_PATHS, canonicalSplitPaths);
        input.putString(RuntimeKeys.APK_SHA256, revision.apkSha256());
        input.putLong(RuntimeKeys.APK_VERSION_CODE, revision.versionCode());
        input.putString(RuntimeKeys.PACKAGE_REVISION, revision.canonical());
        input.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER, true);
        String nativeDir = input.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        if (!nativeDir.trim().isEmpty()) {
            File nativeFile = new File(nativeDir).getCanonicalFile();
            if (!nativeFile.toPath().startsWith(privateRoot.toPath())) {
                throw new SecurityException("Native library path is outside app-private storage");
            }
        }
        boolean containsNativeCode = input.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT,
                !nativeDir.trim().isEmpty());
        String nativeTrust = NativeGuestPolicyContract.normalizeTrust(
                input.getString(RuntimeKeys.NATIVE_GUEST_TRUST, ""));
        String nativeMode = input.getString(RuntimeKeys.NATIVE_EXECUTION_MODE,
                NativeGuestPolicyContract.executionMode(containsNativeCode));
        NativeGuestPolicyContract.requireAllowed(
                containsNativeCode, nativeTrust, nativeMode, nativeDir);
        input.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, containsNativeCode);
        input.putString(RuntimeKeys.NATIVE_GUEST_TRUST, nativeTrust);
        input.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, nativeMode);
    }

    Set<String> validateDeclaredProcess(String packageName, int virtualUserId,
                                        String requestedProcess) throws Exception {
        if (owner.packageAuthority == null) {
            throw new IllegalStateException("RUNTIME_PACKAGE_AUTHORITY_NOT_INITIALIZED");
        }
        VirtualPackageStateSnapshot state = owner.packageAuthority.virtualPackageState(
                packageName, virtualUserId);
        if (state == null || !packageName.equals(state.packageName())
                || state.virtualUserId() != virtualUserId) {
            throw new SecurityException("PROCESS_PACKAGE_STATE_MISMATCH");
        }
        Set<String> declared = new HashSet<>();
        ApplicationInfo application = state.applicationInfo();
        String applicationProcess = RuntimeBrokerService.normalizeProcessName(packageName,
                application == null ? "" : application.processName);
        declared.add(applicationProcess.isEmpty() ? packageName : applicationProcess);
        for (VirtualComponentSnapshot component : state.components()) {
            if (component == null || component.isolated()) continue;
            String componentProcess = RuntimeBrokerService.normalizeProcessName(
                    packageName, component.processName());
            declared.add(componentProcess.isEmpty() ? packageName : componentProcess);
        }
        if (!declared.contains(requestedProcess)) {
            throw new SecurityException("PROCESS_NAME_NOT_DECLARED:" + requestedProcess);
        }
        return declared;
    }

    private static ArrayList<String> optionalStringList(Bundle input, String key) {
        ArrayList<String> values = input.getStringArrayList(key);
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
