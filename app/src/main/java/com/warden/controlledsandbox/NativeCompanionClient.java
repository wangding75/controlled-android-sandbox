package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.warden.controlledsandbox.contract.ControlledReleaseIdentity;
import com.warden.controlledsandbox.contract.INativeAbiCompanion;
import com.warden.controlledsandbox.contract.INativeCompanionArtifactService;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactRequest;
import com.warden.controlledsandbox.contract.NativeCompanionArtifactResult;
import com.warden.controlledsandbox.contract.NativeCompanionIdentity;
import com.warden.controlledsandbox.contract.NativeCompanionRequest;
import com.warden.controlledsandbox.contract.NativeCompanionResult;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;
import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Signature-protected client for the independent 32-bit Runtime Broker and artifact workspace. */
final class NativeCompanionClient implements AutoCloseable {
    private static final String RELEASE_PACKAGE = "com.warden.controlledsandbox.companion32";
    private static final String DEBUG_PACKAGE = RELEASE_PACKAGE + ".debug";
    private static final String CONTROL_SERVICE =
            "com.warden.controlledsandbox.companion32.NativeCompanionService";
    private static final String ARTIFACT_SERVICE =
            "com.warden.controlledsandbox.companion32.NativeCompanionArtifactService";
    private static final String RUNTIME_BROKER_SERVICE =
            "com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService";
    private static final int PROTOCOL = ControlledReleaseIdentity.COMPANION_PROTOCOL;
    private static final int MAX_NATIVE_LIBRARIES = 256;

    private final Context context;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> stagedRevisions = new HashSet<>();
    private final RebindableServiceConnector<INativeAbiCompanion> controlConnection;
    private final RebindableServiceConnector<INativeCompanionArtifactService> artifactConnection;
    private final RebindableServiceConnector<IRuntimeBroker> brokerConnection;

    NativeCompanionClient(Context context) {
        this.context = context.getApplicationContext();
        this.controlConnection = new RebindableServiceConnector<>(this.context,
                companionIntent(CONTROL_SERVICE), INativeAbiCompanion.Stub::asInterface,
                ignored -> { }, "Native companion control");
        this.artifactConnection = new RebindableServiceConnector<>(this.context,
                companionIntent(ARTIFACT_SERVICE), INativeCompanionArtifactService.Stub::asInterface,
                ignored -> { }, "Native companion artifact service");
        this.brokerConnection = new RebindableServiceConnector<>(this.context,
                companionIntent(RUNTIME_BROKER_SERVICE), IRuntimeBroker.Stub::asInterface,
                ignored -> { }, "Native companion Runtime broker");
    }

    NativeCompanionResult probe(SandboxRecord record, int virtualUserId) throws Exception {
        if (!NativeAbiRoutePlanner.requiresCompanion(record.nativeAbi)) {
            throw new IllegalArgumentException("NATIVE_COMPANION_NOT_REQUIRED:" + record.nativeAbi);
        }
        byte[] nonce = nonce();
        NativeCompanionRequest request = new NativeCompanionRequest(
                PROTOCOL, "preflight-" + UUID.randomUUID(), 1L, virtualUserId,
                record.packageName, record.sha256, nonce, record.nativeAbi,
                NativeCompanionRequest.OP_PROBE);
        INativeAbiCompanion control = requireCompatibleControl();
        NativeCompanionResult result = control.execute(request);
        if (result == null) throw new IllegalStateException("NATIVE_COMPANION_EMPTY_RESULT");
        return result;
    }

    Bundle prepare(SandboxRecord record, int virtualUserId, Bundle request) throws Exception {
        return execute(RuntimeOperationRequest.PREPARE_GUEST,
                stageRequest(record, virtualUserId, request));
    }

    Bundle launchActivity(SandboxRecord record, int virtualUserId, Bundle request) throws Exception {
        return execute(RuntimeOperationRequest.LAUNCH_ACTIVITY,
                stageRequest(record, virtualUserId, request));
    }

    Bundle invokeComponent(SandboxRecord record, int virtualUserId, Bundle request) throws Exception {
        return execute(RuntimeOperationRequest.INVOKE_COMPONENT,
                stageRequest(record, virtualUserId, request));
    }

    private Bundle execute(String operation, Bundle request) throws Exception {
        return RuntimeOperationTransport.toLegacyBundle(
                RuntimeOperationTransport.execute(requireBroker(), operation, request));
    }

    void stopGuest(SandboxRecord record, int virtualUserId) throws Exception {
        requireBroker().stopGuest(record.packageName, virtualUserId);
        clearWorkspace(record, virtualUserId);
    }

    private synchronized Bundle stageRequest(SandboxRecord record, int virtualUserId,
            Bundle request) throws Exception {
        NativeCompanionResult probe = probe(record, virtualUserId);
        if (!probe.successful()) {
            throw new IllegalStateException("NATIVE_COMPANION_PROBE_FAILED:"
                    + probe.errorType() + ":" + probe.errorMessage());
        }
        String key = record.packageName + "\n" + virtualUserId + "\n" + record.sha256
                + "\n" + record.nativeAbi;
        INativeCompanionArtifactService service = requireArtifacts();
        NativeCompanionRequest workspaceRequest = workspaceRequest(record, virtualUserId,
                NativeCompanionRequest.OP_PREPARE_GENERATION);
        NativeCompanionArtifactResult workspace = requireSuccess(
                service.prepareWorkspace(workspaceRequest));
        Bundle staged = new Bundle(request);
        if (!stagedRevisions.contains(key)) {
            NativeCompanionArtifactResult base = stageFile(service, record, virtualUserId,
                    NativeCompanionArtifactRequest.BASE_APK, "base.apk",
                    new File(record.apkPath), record.baseApkSha256);
            staged.putString(RuntimeKeys.APK_PATH, base.absolutePath());

            ArrayList<String> names = staged.getStringArrayList(RuntimeKeys.SPLIT_NAMES);
            ArrayList<String> paths = staged.getStringArrayList(RuntimeKeys.SPLIT_PATHS);
            ArrayList<String> hashes = staged.getStringArrayList(RuntimeKeys.SPLIT_SHA256S);
            ArrayList<String> stagedPaths = new ArrayList<>();
            int splitCount = paths == null ? 0 : paths.size();
            for (int index = 0; index < splitCount; index++) {
                String name = names == null || index >= names.size() ? "split" + index : names.get(index);
                String relative = "splits/" + index + "-" + safe(name) + ".apk";
                NativeCompanionArtifactResult split = stageFile(service, record, virtualUserId,
                        NativeCompanionArtifactRequest.SPLIT_APK, relative,
                        new File(paths.get(index)), hashes.get(index));
                stagedPaths.add(split.absolutePath());
            }
            staged.putStringArrayList(RuntimeKeys.SPLIT_PATHS, stagedPaths);
            stagedRevisions.add(key);
        } else {
            staged.putString(RuntimeKeys.APK_PATH,
                    new File(workspace.workspaceRoot(), "base.apk").getCanonicalPath());
            ArrayList<String> names = staged.getStringArrayList(RuntimeKeys.SPLIT_NAMES);
            ArrayList<String> stagedPaths = new ArrayList<>();
            if (names != null) {
                for (int index = 0; index < names.size(); index++) {
                    stagedPaths.add(new File(workspace.workspaceRoot(), "splits/" + index + "-"
                            + safe(names.get(index)) + ".apk").getCanonicalPath());
                }
            }
            staged.putStringArrayList(RuntimeKeys.SPLIT_PATHS, stagedPaths);
        }
        // APK/split staging is revision keyed, but native staging must be repaired on every
        // request.  The companion evicts old workspaces and can also be restarted independently
        // of this host process; retaining only the host-side stagedRevisions bit would then leave
        // an apparently valid nativeLibraryDir pointing at an empty directory.  Native payloads
        // are bounded and content-addressed by stageFile, so re-staging them is cheap and gives
        // the guest a real file/ABI invariant before ClassLoader bootstrap.
        stageNativeLibraries(service, record, virtualUserId, workspace, staged);
        staged.putString(RuntimeKeys.DATA_ROOT, workspace.dataRoot());
        return staged;
    }

    private void stageNativeLibraries(INativeCompanionArtifactService service, SandboxRecord record,
            int virtualUserId, NativeCompanionArtifactResult workspace, Bundle staged)
            throws Exception {
        File nativeRoot = record.nativeLibraryDir == null || record.nativeLibraryDir.trim().isEmpty()
                ? null : new File(record.nativeLibraryDir);
        if (nativeRoot == null || !nativeRoot.isDirectory()) return;
        // Package import preserves the platform layout as <revision>/lib/<abi>/<name>.so,
        // while PackageRecordSnapshot intentionally keeps nativeLibraryDir at the stable
        // revision/lib root (the same shape Android exposes to PackageManager).  The companion
        // workspace is already ABI-scoped, so enumerate the selected child rather than silently
        // handing it an empty parent directory.
        File abiRoot = record.nativeAbi == null || record.nativeAbi.trim().isEmpty()
                ? nativeRoot : new File(nativeRoot, record.nativeAbi.trim());
        if (abiRoot.isDirectory()) nativeRoot = abiRoot;
        File[] libraries = nativeRoot.listFiles(file -> file.isFile()
                && file.getName().endsWith(".so"));
        if (libraries == null) libraries = new File[0];
        if (libraries.length > MAX_NATIVE_LIBRARIES) {
            throw new IllegalStateException("NATIVE_COMPANION_LIBRARY_LIMIT_EXCEEDED");
        }
        Set<String> namesSeen = new HashSet<>();
        for (File library : libraries) {
            if (!namesSeen.add(library.getName())) {
                throw new IllegalStateException("NATIVE_COMPANION_DUPLICATE_LIBRARY_NAME");
            }
            NativeCompanionArtifactResult stagedLibrary = stageFile(service, record, virtualUserId,
                    NativeCompanionArtifactRequest.NATIVE_LIBRARY,
                    "lib/" + library.getName(), library, ApkImportManager.sha256(library));
            String expected = new File(workspace.nativeLibraryRoot(), library.getName())
                    .getCanonicalPath();
            if (!expected.equals(new File(stagedLibrary.absolutePath()).getCanonicalPath())) {
                throw new SecurityException("COMPANION_NATIVE_PATH_MISMATCH:" + library.getName());
            }
        }
        staged.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, workspace.nativeLibraryRoot());
    }

    private NativeCompanionArtifactResult stageFile(INativeCompanionArtifactService service,
            SandboxRecord record, int virtualUserId, String kind, String relativePath,
            File source, String sha256) throws Exception {
        if (!source.isFile()) throw new IllegalArgumentException("COMPANION_SOURCE_MISSING:" + source);
        NativeCompanionArtifactRequest request = new NativeCompanionArtifactRequest(
                PROTOCOL, transferSession(record, virtualUserId), 1L, virtualUserId,
                record.packageName, record.sha256, record.nativeAbi, kind, relativePath,
                sha256, source.length());
        NativeCompanionArtifactResult existing = service.inspectArtifact(request);
        if (existing != null && existing.successful()) return existing;
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                source, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return requireSuccess(service.stageArtifact(request, descriptor));
        }
    }

    private void clearWorkspace(SandboxRecord record, int virtualUserId) throws Exception {
        NativeCompanionArtifactResult result = requireArtifacts().clearWorkspace(
                workspaceRequest(record, virtualUserId, NativeCompanionRequest.OP_CLEAR_GENERATION));
        requireSuccess(result);
        stagedRevisions.remove(record.packageName + "\n" + virtualUserId + "\n" + record.sha256
                + "\n" + record.nativeAbi);
    }

    private NativeCompanionRequest workspaceRequest(SandboxRecord record, int virtualUserId,
            String operation) {
        return new NativeCompanionRequest(PROTOCOL, transferSession(record, virtualUserId), 1L,
                virtualUserId, record.packageName, record.sha256, nonce(), record.nativeAbi,
                operation);
    }

    private static NativeCompanionArtifactResult requireSuccess(NativeCompanionArtifactResult result) {
        if (result == null) throw new IllegalStateException("NATIVE_COMPANION_ARTIFACT_EMPTY_RESULT");
        if (!result.successful()) {
            throw new IllegalStateException("NATIVE_COMPANION_ARTIFACT_FAILED:"
                    + result.errorType() + ":" + result.errorMessage());
        }
        return result;
    }

    private INativeAbiCompanion requireCompatibleControl() throws Exception {
        INativeAbiCompanion control = controlConnection.require();
        try {
            NativeCompanionIdentity identity = control.getIdentity();
            NativeCompanionIdentityVerifier.requireCompatible(identity);
            PackageInfo host = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            PackageInfo companion = context.getPackageManager()
                    .getPackageInfo(companionPackage(), 0);
            NativeCompanionIdentityVerifier.requireInstalledPair(
                    identity, versionCode(host), host.versionName,
                    versionCode(companion), companion.versionName);
            return control;
        } catch (Exception failure) {
            controlConnection.invalidate();
            throw failure;
        }
    }

    private INativeCompanionArtifactService requireArtifacts() throws Exception {
        return artifactConnection.require();
    }

    private static long versionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private IRuntimeBroker requireBroker() throws Exception {
        return brokerConnection.require();
    }

    private Intent companionIntent(String className) {
        return new Intent().setComponent(new ComponentName(companionPackage(), className));
    }

    private byte[] nonce() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        return nonce;
    }

    private String companionPackage() {
        String hostPackage = context.getPackageName();
        return hostPackage != null && hostPackage.endsWith(".debug") ? DEBUG_PACKAGE : RELEASE_PACKAGE;
    }

    private static String transferSession(SandboxRecord record, int virtualUserId) {
        return "transfer-" + safe(record.packageName) + "-u" + virtualUserId + "-"
                + record.sha256.substring(0, Math.min(16, record.sha256.length()));
    }

    private static String safe(String value) {
        return value == null ? "artifact" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override public void close() {
        brokerConnection.close();
        artifactConnection.close();
        controlConnection.close();
        stagedRevisions.clear();
    }
}
