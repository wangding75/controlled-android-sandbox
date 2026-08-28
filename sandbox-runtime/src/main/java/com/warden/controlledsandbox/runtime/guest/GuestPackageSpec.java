package com.warden.controlledsandbox.runtime.guest;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.contract.NativeGuestPolicyContract;
import com.warden.controlledsandbox.contract.ProcessSlotContract;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    final ParcelFileDescriptor apkDescriptor;
    final String apkEntryName;
    final ParcelFileDescriptor materializedApkDescriptor;
    final String apkSha256;
    final String baseApkSha256;
    final long apkVersionCode;
    final String packageRevision;
    /** Proof minted by the Broker validator for this exact immutable revision. */
    final boolean packageRevisionVerifiedByBroker;
    final String nativeLibraryDir;
    final String nativeAbi;
    final boolean containsNativeCode;
    final String nativeGuestTrust;
    final String nativeExecutionMode;
    final String applicationClass;
    public final String componentClass;
    final String dataRoot;
    final ParcelFileDescriptor dataRootDescriptor;
    final ParcelFileDescriptor nativeLibraryDescriptor;
    final List<ParcelFileDescriptor> nativeLibraryDescriptors;
    final List<String> nativeLibraryEntryNames;
    final boolean isolatedProcess;
    final List<String> permissions;
    final List<String> splitNames;
    final List<String> splitTypes;
    final List<String> splitConfigFor;
    final List<String> splitUses;
    final List<String> splitPaths;
    final List<ParcelFileDescriptor> splitDescriptors;
    final List<String> splitSha256s;
    final List<String> sharedLibraries;
    final VirtualPackageStateSnapshot packageState;
    final List<VirtualPackageProjectionSnapshot> packageUniverse;
    final IBinder virtualSystemServiceBinder;
    final IBinder runtimeBrokerBinder;
    final IBinder runtimeStorageBinder;

    public GuestPackageSpec(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("request is required");
        // Binder-restored Bundles may default to the boot class loader on API 32.  Install the
        // contract loader before reading any custom Parcelable; copying or reading first can
        // eagerly resolve VirtualPackageStateSnapshot through the wrong loader.
        bundle.setClassLoader(VirtualPackageStateSnapshot.class.getClassLoader());
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
        if (!ProcessSlotContract.isOrdinarySlot(processSlot)) {
            throw new IllegalArgumentException("processSlot out of range");
        }
        processName = bundle.getString(RuntimeKeys.PROCESS_NAME, packageName);
        if (processName == null || processName.trim().isEmpty()) throw new IllegalArgumentException("processName is required");
        isolatedProcess = bundle.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false);
        String declaredApkPath = required(bundle, RuntimeKeys.APK_PATH);
        apkDescriptor = parcelDescriptor(bundle, RuntimeKeys.ISOLATED_APK_FD);
        apkEntryName = validateEntryName(bundle.getString(RuntimeKeys.ISOLATED_APK_ENTRY_NAME,
                apkDescriptor == null ? "" : new File(declaredApkPath).getName()));
        apkPath = apkDescriptor == null
                ? declaredApkPath
                : bundle.getString(RuntimeKeys.ISOLATED_MATERIALIZED_APK_PATH,
                        "/data/app/" + packageName + "/" + apkEntryName);
        materializedApkDescriptor = parcelDescriptor(bundle,
                RuntimeKeys.ISOLATED_MATERIALIZED_APK_FD);
        apkSha256 = required(bundle, RuntimeKeys.APK_SHA256);
        baseApkSha256 = bundle.getString(RuntimeKeys.BASE_APK_SHA256, apkSha256);
        apkVersionCode = bundle.getLong(RuntimeKeys.APK_VERSION_CODE, -1L);
        if (apkVersionCode < 0) throw new IllegalArgumentException("apkVersionCode must be non-negative");
        packageRevision = required(bundle, RuntimeKeys.PACKAGE_REVISION);
        packageRevisionVerifiedByBroker = bundle.getBoolean(
                RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER, false);
        String declaredNativeLibraryDir = bundle.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        nativeLibraryDescriptor = parcelDescriptor(bundle, RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FD);
        nativeLibraryDescriptors = parcelDescriptors(bundle,
                RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FDS);
        nativeLibraryEntryNames = immutable(bundle.getStringArrayList(
                RuntimeKeys.ISOLATED_NATIVE_LIBRARY_ENTRY_NAMES));
        if (isolatedProcess && !nativeLibraryDescriptors.isEmpty()) {
            if (nativeLibraryDescriptors.size() != nativeLibraryEntryNames.size()) {
                throw new IllegalArgumentException("isolated native library descriptor count mismatch");
            }
            for (String entryName : nativeLibraryEntryNames) validateEntryName(entryName);
        }
        nativeLibraryDir = nativeLibraryDescriptor == null
                ? declaredNativeLibraryDir
                : "/data/app/" + packageName + "/lib/" + nativeAbiValue(bundle);
        nativeAbi = bundle.getString(RuntimeKeys.NATIVE_ABI, "");
        containsNativeCode = bundle.getBoolean(RuntimeKeys.NATIVE_CODE_PRESENT,
                !nativeLibraryDir.trim().isEmpty());
        nativeGuestTrust = NativeGuestPolicyContract.normalizeTrust(
                bundle.getString(RuntimeKeys.NATIVE_GUEST_TRUST, ""));
        nativeExecutionMode = bundle.getString(RuntimeKeys.NATIVE_EXECUTION_MODE,
                NativeGuestPolicyContract.executionMode(containsNativeCode));
        validateNativeAbi(nativeLibraryDir, nativeAbi);
        NativeGuestPolicyContract.requireAllowed(
                containsNativeCode, nativeGuestTrust, nativeExecutionMode, nativeLibraryDir);
        applicationClass = bundle.getString(RuntimeKeys.APPLICATION_CLASS, "");
        componentClass = bundle.getString(RuntimeKeys.COMPONENT_CLASS, "");
        String declaredDataRoot = required(bundle, RuntimeKeys.DATA_ROOT);
        dataRootDescriptor = parcelDescriptor(bundle, RuntimeKeys.ISOLATED_DATA_ROOT_FD);
        runtimeStorageBinder = bundle.getBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER);
        if (isolatedProcess && dataRootDescriptor != null && runtimeStorageBinder == null) {
            throw new IllegalArgumentException("ISOLATED_STORAGE_CAPABILITY_MISSING");
        }
        dataRoot = dataRootDescriptor == null
                ? declaredDataRoot
                : "/data/user/" + virtualUserId + "/" + packageName;
        permissions = immutable(bundle.getStringArrayList(RuntimeKeys.PERMISSIONS));
        splitNames = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_NAMES));
        splitTypes = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_TYPES));
        splitConfigFor = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_CONFIG_FOR));
        splitUses = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_USES));
        List<String> declaredSplitPaths = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_PATHS));
        splitDescriptors = parcelDescriptors(bundle, RuntimeKeys.ISOLATED_SPLIT_FDS);
        List<String> splitEntryNames = immutable(
                bundle.getStringArrayList(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES));
        List<String> resolvedSplitPaths = descriptorPaths(splitDescriptors, declaredSplitPaths,
                splitEntryNames);
        if (isolatedProcess && !splitDescriptors.isEmpty()) {
            ArrayList<String> logicalSplits = new ArrayList<>();
            for (int index = 0; index < splitDescriptors.size(); index++) {
                logicalSplits.add("/data/app/" + packageName + "/split-" + index + ".apk");
            }
            resolvedSplitPaths = Collections.unmodifiableList(logicalSplits);
        }
        splitPaths = resolvedSplitPaths;
        splitSha256s = immutable(bundle.getStringArrayList(RuntimeKeys.SPLIT_SHA256S));
        sharedLibraries = csv(bundle.getString(RuntimeKeys.SHARED_LIBRARIES, ""));
        validateSplits();
        virtualSystemServiceBinder = bundle.getBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER);
        runtimeBrokerBinder = bundle.getBinder(RuntimeKeys.RUNTIME_BROKER_BINDER);
        packageState = bundle.getParcelable(RuntimeKeys.PACKAGE_STATE);
        if (packageState == null) throw new IllegalArgumentException("virtual package state is required");
        ArrayList<VirtualPackageProjectionSnapshot> universe = bundle.getParcelableArrayList(
                RuntimeKeys.PACKAGE_UNIVERSE);
        packageUniverse = universe == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(universe));
        if (packageUniverse.size() > 256) {
            throw new IllegalArgumentException("virtual package universe is too large");
        }
        if (!packageName.equals(packageState.packageName()) || virtualUserId != packageState.virtualUserId()) {
            throw new SecurityException("VIRTUAL_PACKAGE_STATE_IDENTITY_MISMATCH");
        }
        if (!apkSha256.equals(packageState.apkSha256())) {
            throw new SecurityException("VIRTUAL_PACKAGE_STATE_REVISION_MISMATCH");
        }
    }

    Bundle toBundle() {
        Bundle out = new Bundle();
        out.putInt(RuntimeKeys.PROTOCOL, protocol); out.putString(RuntimeKeys.SESSION_ID, sessionId);
        out.putLong(RuntimeKeys.GENERATION, generation); out.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        out.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId); out.putInt(RuntimeKeys.VIRTUAL_UID, virtualUid);
        out.putInt(RuntimeKeys.PROCESS_SLOT, processSlot); out.putString(RuntimeKeys.PROCESS_NAME, processName);
        out.putString(RuntimeKeys.APK_PATH, apkPath); out.putString(RuntimeKeys.APK_SHA256, apkSha256);
        if (apkDescriptor != null) out.putParcelable(RuntimeKeys.ISOLATED_APK_FD, apkDescriptor);
        if (materializedApkDescriptor != null) {
            out.putParcelable(RuntimeKeys.ISOLATED_MATERIALIZED_APK_FD, materializedApkDescriptor);
            out.putString(RuntimeKeys.ISOLATED_MATERIALIZED_APK_PATH, apkPath);
        }
        if (apkDescriptor != null && !apkEntryName.isEmpty()) {
            out.putString(RuntimeKeys.ISOLATED_APK_ENTRY_NAME, apkEntryName);
        }
        out.putString(RuntimeKeys.BASE_APK_SHA256, baseApkSha256);
        out.putLong(RuntimeKeys.APK_VERSION_CODE, apkVersionCode); out.putString(RuntimeKeys.PACKAGE_REVISION, packageRevision);
        out.putBoolean(RuntimeKeys.PACKAGE_REVISION_VERIFIED_BY_BROKER,
                packageRevisionVerifiedByBroker);
        out.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, nativeLibraryDir); out.putString(RuntimeKeys.NATIVE_ABI, nativeAbi);
        if (dataRootDescriptor != null) out.putParcelable(RuntimeKeys.ISOLATED_DATA_ROOT_FD, dataRootDescriptor);
        if (nativeLibraryDescriptor != null) {
            out.putParcelable(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FD, nativeLibraryDescriptor);
        }
        if (!nativeLibraryDescriptors.isEmpty()) {
            out.putParcelableArrayList(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FDS,
                    new ArrayList<>(nativeLibraryDescriptors));
            out.putStringArrayList(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_ENTRY_NAMES,
                    new ArrayList<>(nativeLibraryEntryNames));
        }
        out.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, containsNativeCode);
        out.putString(RuntimeKeys.NATIVE_GUEST_TRUST, nativeGuestTrust);
        out.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, nativeExecutionMode);
        out.putString(RuntimeKeys.APPLICATION_CLASS, applicationClass);
        out.putString(RuntimeKeys.COMPONENT_CLASS, componentClass); out.putString(RuntimeKeys.DATA_ROOT, dataRoot);
        out.putBoolean(RuntimeKeys.ISOLATED_PROCESS, isolatedProcess);
        out.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>(permissions));
        out.putStringArrayList(RuntimeKeys.SPLIT_NAMES, new ArrayList<>(splitNames));
        out.putStringArrayList(RuntimeKeys.SPLIT_TYPES, new ArrayList<>(splitTypes));
        out.putStringArrayList(RuntimeKeys.SPLIT_CONFIG_FOR, new ArrayList<>(splitConfigFor));
        out.putStringArrayList(RuntimeKeys.SPLIT_USES, new ArrayList<>(splitUses));
        out.putStringArrayList(RuntimeKeys.SPLIT_PATHS, new ArrayList<>(splitPaths));
        if (!splitDescriptors.isEmpty()) {
            out.putParcelableArrayList(RuntimeKeys.ISOLATED_SPLIT_FDS,
                    new ArrayList<>(splitDescriptors));
            ArrayList<String> entryNames = new ArrayList<>();
            for (String path : splitPaths) entryNames.add(bundleEntryName(path));
            out.putStringArrayList(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES, entryNames);
        }
        out.putStringArrayList(RuntimeKeys.SPLIT_SHA256S, new ArrayList<>(splitSha256s));
        out.putString(RuntimeKeys.SHARED_LIBRARIES, String.join(",", sharedLibraries));
        if (virtualSystemServiceBinder != null) out.putBinder(RuntimeKeys.VIRTUAL_SYSTEM_SERVICE_BINDER, virtualSystemServiceBinder);
        out.putBinder(RuntimeKeys.RUNTIME_BROKER_BINDER, runtimeBrokerBinder);
        if (runtimeStorageBinder != null) out.putBinder(RuntimeKeys.RUNTIME_STORAGE_BINDER,
                runtimeStorageBinder);
        out.putParcelable(RuntimeKeys.PACKAGE_STATE, packageState);
        out.putParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE,
                new ArrayList<>(packageUniverse));
        return out;
    }

    /**
     * Builds the compact form used on ordinary Guest -> Broker operation calls.
     * The package universe is immutable session state retained by the Broker after preparation;
     * re-sending every projection on each Binder call can exceed the platform transaction budget.
     */
    Bundle toRuntimeRequestBundle() {
        Bundle out = toBundle();
        out.remove(RuntimeKeys.PACKAGE_UNIVERSE);
        return out;
    }

    private static void validateNativeAbi(String nativeLibraryDir, String nativeAbi) {
        String dir = nativeLibraryDir == null ? "" : nativeLibraryDir.trim();
        String abi = nativeAbi == null ? "" : nativeAbi.trim();
        if (!dir.isEmpty() && (abi.isEmpty() || abi.equals("legacy-unknown"))) {
            throw new IllegalArgumentException("NATIVE_ABI_METADATA_MISSING");
        }
        if (!abi.isEmpty() && !abi.equals("arm64-v8a") && !abi.equals("armeabi-v7a")
                && !abi.equals("x86_64") && !abi.equals("x86")) {
            throw new IllegalArgumentException("UNSUPPORTED_NATIVE_ABI:" + abi);
        }
    }

    public VirtualPackageStateSnapshot packageState() { return packageState; }

    public String processName() { return processName; }

    /**
     * Mirrors ApplicationInfo.nativeLibraryDir semantics for revisions that preserve the
     * installed lib/<abi> layout. Older revisions were flattened to lib/; retain a safe
     * compatibility fallback until they are reinstalled and migrated.
     */
    String effectiveNativeLibraryDir() {
        if (nativeLibraryDir == null || nativeLibraryDir.trim().isEmpty()) return "";
        if (nativeAbi == null || nativeAbi.trim().isEmpty()) return nativeLibraryDir;
        File abiDirectory = new File(nativeLibraryDir, nativeAbi.trim());
        return abiDirectory.isDirectory() ? abiDirectory.getAbsolutePath() : nativeLibraryDir;
    }

    File apkFile() { return new File(apkPath); }
    File dataRootFile() { return new File(dataRoot); }
    String dexPath() {
        StringBuilder value = new StringBuilder(apkPath);
        for (String path : splitPaths) value.append(File.pathSeparator).append(path);
        return value.toString();
    }
    String[] splitPathArray() { return splitPaths.toArray(new String[0]); }
    boolean hasSplit(String splitName) { return splitName != null && splitNames.contains(splitName); }
    List<PackageRevisionSetVerifier.Artifact> splitArtifacts() {
        List<PackageRevisionSetVerifier.Artifact> result = new ArrayList<>();
        for (int index = 0; index < splitNames.size(); index++) {
            result.add(new PackageRevisionSetVerifier.Artifact(splitNames.get(index), splitTypes.get(index),
                    splitConfigFor.get(index), splitUses.get(index), new File(splitPaths.get(index)),
                    splitSha256s.get(index)));
        }
        return result;
    }

    List<PackageRevisionSetVerifier.Artifact> splitDescriptorArtifacts() {
        if (splitDescriptors.size() != splitNames.size()) {
            throw new IllegalArgumentException("ISOLATED_SPLIT_DESCRIPTOR_COUNT_MISMATCH");
        }
        List<PackageRevisionSetVerifier.Artifact> result = new ArrayList<>();
        for (int index = 0; index < splitNames.size(); index++) {
            result.add(new PackageRevisionSetVerifier.Artifact(splitNames.get(index),
                    splitTypes.get(index), splitConfigFor.get(index), splitUses.get(index),
                    splitDescriptors.get(index), splitSha256s.get(index)));
        }
        return result;
    }

    private void validateSplits() {
        int size = splitNames.size();
        if (size > 255) throw new IllegalArgumentException("Too many split APKs");
        if (splitTypes.size() != size || splitConfigFor.size() != size || splitUses.size() != size
                || splitPaths.size() != size || splitSha256s.size() != size) {
            throw new IllegalArgumentException("Split artifact arrays have different lengths");
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < size; index++) {
            if (splitNames.get(index).trim().isEmpty() || !names.add(splitNames.get(index))) {
                throw new IllegalArgumentException("Split name is empty or duplicated");
            }
            if (splitPaths.get(index).trim().isEmpty() || !splitSha256s.get(index).matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Split artifact metadata is incomplete");
            }
        }
    }

    private static List<String> immutable(ArrayList<String> values) {
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static List<String> csv(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) if (!part.trim().isEmpty()) result.add(part.trim());
        return Collections.unmodifiableList(result);
    }
    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static ParcelFileDescriptor parcelDescriptor(Bundle bundle, String key) {
        Object value = bundle.getParcelable(key);
        return value instanceof ParcelFileDescriptor ? (ParcelFileDescriptor) value : null;
    }

    private static List<ParcelFileDescriptor> parcelDescriptors(Bundle bundle, String key) {
        ArrayList<ParcelFileDescriptor> values = bundle.getParcelableArrayList(key);
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<ParcelFileDescriptor> result = new ArrayList<>();
        for (ParcelFileDescriptor value : values) {
            if (value == null) throw new IllegalArgumentException(key + " contains null descriptor");
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static String descriptorPath(ParcelFileDescriptor descriptor, String fallback) {
        return descriptorPath(descriptor, fallback, "");
    }

    private static String descriptorPath(ParcelFileDescriptor descriptor, String fallback,
                                         String entryName) {
        if (descriptor == null) return fallback;
        int fd = descriptor.getFd();
        if (fd < 0) throw new IllegalArgumentException("isolated file descriptor is closed");
        String safeEntry = validateEntryName(entryName);
        return safeEntry.isEmpty()
                ? "/proc/self/fd/" + fd
                : "/proc/self/fd/" + fd + "/" + safeEntry;
    }

    private static List<String> descriptorPaths(List<ParcelFileDescriptor> descriptors,
                                                List<String> fallback,
                                                List<String> entryNames) {
        if (descriptors.isEmpty()) return fallback;
        if (descriptors.size() != fallback.size()) {
            throw new IllegalArgumentException("isolated split descriptor count mismatch");
        }
        if (!entryNames.isEmpty() && descriptors.size() != entryNames.size()) {
            throw new IllegalArgumentException("isolated split entry count mismatch");
        }
        ArrayList<String> result = new ArrayList<>();
        for (int index = 0; index < descriptors.size(); index++) {
            String entryName = entryNames.isEmpty() ? "" : entryNames.get(index);
            result.add(descriptorPath(descriptors.get(index), fallback.get(index), entryName));
        }
        return Collections.unmodifiableList(result);
    }

    private static String validateEntryName(String entryName) {
        String value = entryName == null ? "" : entryName.trim();
        if (value.isEmpty()) return "";
        if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("isolated descriptor entry is unsafe");
        }
        return value;
    }

    private static String nativeAbiValue(Bundle bundle) {
        String abi = bundle.getString(RuntimeKeys.NATIVE_ABI, "").trim();
        if (abi.isEmpty()) throw new IllegalArgumentException("isolated native ABI is required");
        return abi;
    }

    private static String bundleEntryName(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String value = path.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        String entry = slash < 0 ? value : value.substring(slash + 1);
        return validateEntryName(entry);
    }
}
