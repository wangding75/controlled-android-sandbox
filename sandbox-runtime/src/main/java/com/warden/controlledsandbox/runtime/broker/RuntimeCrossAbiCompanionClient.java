package com.warden.controlledsandbox.runtime.broker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
import com.warden.controlledsandbox.contract.INativeCompanionArtifactService;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.contract.RuntimeOperationResult;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Host-Broker gateway for a target whose executable ABI belongs to the 32-bit companion.
 *
 * <p>The host Broker remains the authority for caller validation and package visibility.  Once
 * that decision is made, this class transfers the immutable target revision into companion-owned
 * storage and sends the already validated operation to the companion Broker.  It deliberately
 * does not share Host session identifiers with the companion; the companion allocates the target
 * session and owns its generation, slot, Component transport and recovery state.</p>
 */
final class RuntimeCrossAbiCompanionClient implements AutoCloseable {
    private static final String ARTIFACT_SERVICE =
            "com.warden.controlledsandbox.companion32.NativeCompanionArtifactService";
    private static final String RUNTIME_BROKER_SERVICE =
            "com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService";
    private static final int PROTOCOL = ControlledReleaseIdentity.COMPANION_PROTOCOL;
    private static final int MAX_NATIVE_LIBRARIES = 256;
    private static final int BUFFER_BYTES = 64 * 1024;

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private final RebindableServiceConnector<INativeCompanionArtifactService> artifactConnection;
    private final RebindableServiceConnector<IRuntimeBroker> brokerConnection;

    RuntimeCrossAbiCompanionClient(Context context) {
        this(context, () -> { });
    }

    RuntimeCrossAbiCompanionClient(Context context, Runnable onRemoteInvalidated) {
        this.context = context.getApplicationContext();
        Runnable invalidation = onRemoteInvalidated == null ? () -> { } : onRemoteInvalidated;
        this.artifactConnection = new RebindableServiceConnector<>(this.context,
                companionIntent(ARTIFACT_SERVICE),
                INativeCompanionArtifactService.Stub::asInterface,
                ignored -> { }, "Cross-ABI companion artifact service", invalidation);
        this.brokerConnection = new RebindableServiceConnector<>(this.context,
                companionIntent(RUNTIME_BROKER_SERVICE), IRuntimeBroker.Stub::asInterface,
                ignored -> { }, "Cross-ABI companion Runtime broker", invalidation);
    }

    synchronized Bundle execute(PackageRecordSnapshot record, int virtualUserId,
                                String operation, Bundle request) throws Exception {
        if (record == null) throw new IllegalArgumentException("target package record is required");
        if (operation == null || operation.trim().isEmpty()) {
            throw new IllegalArgumentException("cross-ABI operation is required");
        }
        Bundle staged = stage(record, virtualUserId, request);
        RuntimeOperationResult result = RuntimeOperationTransport.execute(
                brokerConnection.require(), operation, staged);
        return RuntimeOperationTransport.toLegacyBundle(result);
    }

    private Bundle stage(PackageRecordSnapshot record, int virtualUserId, Bundle request)
            throws Exception {
        INativeCompanionArtifactService service = artifactConnection.require();
        NativeCompanionRequest workspaceRequest = new NativeCompanionRequest(
                PROTOCOL, transferSession(record, virtualUserId), 1L, virtualUserId,
                record.packageName(), record.apkSha256(), nonce(),
                record.nativeAbi(), NativeCompanionRequest.OP_PREPARE_GENERATION);
        NativeCompanionArtifactResult workspace = requireSuccess(
                service.prepareWorkspace(workspaceRequest));

        Bundle staged = request == null ? new Bundle() : new Bundle(request);
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> types = new ArrayList<>();
        ArrayList<String> configFor = new ArrayList<>();
        ArrayList<String> uses = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> hashes = new ArrayList<>();
        int splitIndex = 0;
        for (PackageArtifactSnapshot artifact : record.artifacts()) {
            if (artifact.base()) {
                NativeCompanionArtifactResult base = stageFile(service, record, virtualUserId,
                        NativeCompanionArtifactRequest.BASE_APK, "base.apk",
                        new File(artifact.path()), artifact.sha256());
                staged.putString(RuntimeKeys.APK_PATH, base.absolutePath());
                continue;
            }
            String relative = "splits/" + splitIndex + "-" + safe(artifact.splitName()) + ".apk";
            NativeCompanionArtifactResult split = stageFile(service, record, virtualUserId,
                    NativeCompanionArtifactRequest.SPLIT_APK, relative,
                    new File(artifact.path()), artifact.sha256());
            names.add(artifact.splitName());
            types.add(artifact.type());
            configFor.add(artifact.configForSplit());
            uses.add(artifact.usesSplit());
            paths.add(split.absolutePath());
            hashes.add(artifact.sha256());
            splitIndex++;
        }
        staged.putStringArrayList(RuntimeKeys.SPLIT_NAMES, names);
        staged.putStringArrayList(RuntimeKeys.SPLIT_TYPES, types);
        staged.putStringArrayList(RuntimeKeys.SPLIT_CONFIG_FOR, configFor);
        staged.putStringArrayList(RuntimeKeys.SPLIT_USES, uses);
        staged.putStringArrayList(RuntimeKeys.SPLIT_PATHS, paths);
        staged.putStringArrayList(RuntimeKeys.SPLIT_SHA256S, hashes);

        stageNativeLibraries(service, record, virtualUserId, workspace, staged);
        staged.putString(RuntimeKeys.DATA_ROOT, workspace.dataRoot());
        staged.putString(RuntimeKeys.PACKAGE_NAME, record.packageName());
        staged.putString(RuntimeKeys.APK_SHA256, record.apkSha256());
        staged.putString(RuntimeKeys.BASE_APK_SHA256, record.baseApkSha256());
        staged.putLong(RuntimeKeys.APK_VERSION_CODE, record.versionCode());
        staged.putString(RuntimeKeys.NATIVE_ABI, record.nativeAbi());
        staged.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, record.containsNativeCode());
        staged.putString(RuntimeKeys.NATIVE_GUEST_TRUST, record.nativeGuestTrust());
        staged.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, record.nativeExecutionMode());
        return staged;
    }

    private void stageNativeLibraries(INativeCompanionArtifactService service,
            PackageRecordSnapshot record, int virtualUserId,
            NativeCompanionArtifactResult workspace, Bundle staged) throws Exception {
        String configured = record.nativeLibraryDir();
        if (configured == null || configured.trim().isEmpty()) return;
        File nativeRoot = checkedHostPath(new File(configured));
        if (!nativeRoot.isDirectory()) {
            throw new IllegalStateException("CROSS_ABI_NATIVE_ROOT_MISSING:" + nativeRoot);
        }
        File abiRoot = new File(nativeRoot, record.nativeAbi());
        if (abiRoot.isDirectory()) nativeRoot = abiRoot;
        File[] libraries = nativeRoot.listFiles(file -> file.isFile()
                && file.getName().endsWith(".so"));
        if (libraries == null || libraries.length == 0) {
            throw new IllegalStateException("CROSS_ABI_NATIVE_LIBRARY_MISSING:" + nativeRoot);
        }
        if (libraries.length > MAX_NATIVE_LIBRARIES) {
            throw new IllegalStateException("CROSS_ABI_NATIVE_LIBRARY_LIMIT_EXCEEDED");
        }
        Set<String> names = new HashSet<>();
        for (File library : libraries) {
            if (!names.add(library.getName())) {
                throw new SecurityException("CROSS_ABI_DUPLICATE_NATIVE_LIBRARY:" + library.getName());
            }
            NativeCompanionArtifactResult result = stageFile(service, record, virtualUserId,
                    NativeCompanionArtifactRequest.NATIVE_LIBRARY, "lib/" + library.getName(),
                    library, sha256(library));
            String expected = new File(workspace.nativeLibraryRoot(), library.getName())
                    .getCanonicalPath();
            if (!expected.equals(new File(result.absolutePath()).getCanonicalPath())) {
                throw new SecurityException("CROSS_ABI_NATIVE_PATH_MISMATCH:" + library.getName());
            }
        }
        staged.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, workspace.nativeLibraryRoot());
    }

    private NativeCompanionArtifactResult stageFile(
            INativeCompanionArtifactService service, PackageRecordSnapshot record,
            int virtualUserId, String kind, String relativePath, File source, String sha256)
            throws Exception {
        File checked = checkedHostFile(source);
        NativeCompanionArtifactRequest artifact = new NativeCompanionArtifactRequest(
                PROTOCOL, transferSession(record, virtualUserId), 1L, virtualUserId,
                record.packageName(), record.apkSha256(), record.nativeAbi(), kind, relativePath,
                sha256, checked.length());
        NativeCompanionArtifactResult existing = service.inspectArtifact(artifact);
        if (existing != null && existing.successful()) return existing;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                checked, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return requireSuccess(service.stageArtifact(artifact, descriptor));
        }
    }

    private File checkedHostFile(File file) throws Exception {
        File checked = checkedHostPath(file);
        if (!checked.isFile()) throw new IllegalArgumentException("CROSS_ABI_ARTIFACT_MISSING:" + checked);
        return checked;
    }

    private File checkedHostPath(File file) throws Exception {
        File checked = file.getCanonicalFile();
        File root = context.getFilesDir().getCanonicalFile();
        if (!checked.toPath().startsWith(root.toPath())) {
            throw new SecurityException("CROSS_ABI_ARTIFACT_OUTSIDE_HOST_PRIVATE_ROOT:" + checked);
        }
        return checked;
    }

    private static NativeCompanionArtifactResult requireSuccess(
            NativeCompanionArtifactResult result) {
        if (result == null) throw new IllegalStateException("CROSS_ABI_ARTIFACT_EMPTY_RESULT");
        if (!result.successful()) {
            throw new IllegalStateException("CROSS_ABI_ARTIFACT_FAILED:" + result.errorType()
                    + ":" + result.errorMessage());
        }
        return result;
    }

    private Intent companionIntent(String serviceClass) {
        String packageName = context.getPackageName().endsWith(".debug")
                ? RuntimePeerPolicy.COMPANION_DEBUG_PACKAGE
                : RuntimePeerPolicy.COMPANION_RELEASE_PACKAGE;
        return new Intent().setComponent(new ComponentName(packageName, serviceClass));
    }

    private static String transferSession(PackageRecordSnapshot record, int virtualUserId) {
        return "cross-abi-" + safe(record.packageName()) + "-u" + virtualUserId + "-"
                + record.apkSha256().substring(0, Math.min(16, record.apkSha256().length()));
    }

    private static String safe(String value) {
        return value == null ? "artifact" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private byte[] nonce() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        return nonce;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    @Override public void close() {
        brokerConnection.close();
        artifactConnection.close();
    }
}
