package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimePerformanceTrace;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.ProcessInitializationGate;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.ProcessSlotContract;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.protocol.RuntimeIntentWireCodec;
import com.warden.controlledsandbox.framework.routing.VirtualPendingIntentRegistry;

import android.app.Application;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import com.warden.controlledsandbox.framework.core.FrameworkHooks;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
import com.warden.controlledsandbox.framework.identity.VirtualPackageUniverse;
import com.warden.controlledsandbox.framework.identity.VirtualPermissionPolicy;
import com.warden.controlledsandbox.framework.identity.SandboxAppOpsPolicy;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.framework.capability.CapabilityAccessPolicy;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.runtime.capability.GuestCapabilityAuditLog;
import com.warden.controlledsandbox.runtime.capability.CapabilityProxyReadiness;
import com.warden.controlledsandbox.contract.VirtualPermissionSnapshot;
import com.warden.controlledsandbox.contract.IVirtualSystemServiceSession;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.contract.PackageAppOpSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.framework.core.VirtualCameraCaptureEngine;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import com.warden.controlledsandbox.nativebridge.NativeNetworkIdentity;
import com.warden.controlledsandbox.runtime.systemservice.RemoteVirtualSystemServiceAuthority;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import android.os.ParcelFileDescriptor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Process-local runtime. One Android guest process hosts exactly one generation at a time. */
public final class GuestRuntimeEnvironment {
    private static Session current;
    private static final ProcessInitializationGate<PreparationKey, Bundle> INITIALIZATION_GATE =
            new ProcessInitializationGate<>();
    private static final long PREPARATION_WAIT_TIMEOUT_SECONDS = 60L;

    private GuestRuntimeEnvironment() { }

    static Bundle consumeActivityRoute(Session session, String token) throws Exception {
        if (session == null) throw new IllegalArgumentException("session is required");
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("token is required");
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL,
                com.warden.controlledsandbox.domain.protocol.RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.ROUTE_TOKEN, token);
        request.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        request.putLong(RuntimeKeys.GENERATION, session.generation());
        IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(session.spec.runtimeBrokerBinder);
        if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_BINDER_UNAVAILABLE");
        return RuntimeOperationTransport.toLegacyBundle(RuntimeOperationTransport.execute(
                broker, RuntimeOperationRequest.CONSUME_ROUTE, request));
    }

    static Bundle dispatchActivityEvent(Session session, Bundle request) throws Exception {
        if (session == null) throw new IllegalArgumentException("session is required");
        IRuntimeBroker broker = IRuntimeBroker.Stub.asInterface(session.spec.runtimeBrokerBinder);
        if (broker == null) throw new IllegalStateException("RUNTIME_BROKER_BINDER_UNAVAILABLE");
        return RuntimeOperationTransport.toLegacyBundle(RuntimeOperationTransport.execute(
                broker, RuntimeOperationRequest.ACTIVITY_EVENT, request));
    }

    private record PreparationKey(String packageName, String sessionId, long generation,
                                  String packageRevision, String processName) {
        static PreparationKey from(GuestPackageSpec spec) {
            if (spec == null) throw new IllegalArgumentException("spec is required");
            return new PreparationKey(spec.packageName, spec.sessionId, spec.generation,
                    spec.packageRevision, spec.processName);
        }

        boolean matches(GuestPackageSpec spec) {
            return spec != null && generation == spec.generation
                    && java.util.Objects.equals(packageName, spec.packageName)
                    && java.util.Objects.equals(sessionId, spec.sessionId)
                    && java.util.Objects.equals(packageRevision, spec.packageRevision)
                    && java.util.Objects.equals(processName, spec.processName);
        }
    }

    static Bundle prepare(Context host, GuestPackageSpec spec) {
        long prepareStarted = android.os.SystemClock.elapsedRealtime();
        prepareTrace("ENTRY", spec, "", prepareStarted, null);
        PreparationKey key = PreparationKey.from(spec);
        ProcessInitializationGate<PreparationKey, Bundle>.Start start;
        synchronized (GuestRuntimeEnvironment.class) {
            // A live same-revision session is already the authoritative process initialization.
            // Check this only when no other generation is being prepared; an upgrade must fence
            // concurrent callers instead of handing them the old session while it is shutting down.
            if (!INITIALIZATION_GATE.initializing() && current != null
                    && key.matches(current.spec)) {
                prepareTrace("RETURN", spec, "ALREADY_READY", prepareStarted, null);
                return current.status("ALREADY_READY",
                        android.os.SystemClock.elapsedRealtime());
            }
            start = INITIALIZATION_GATE.start(key);
        }
        if (start.rejected()) {
            prepareTrace("REJECTED", spec, "GUEST_PREPARATION_IN_PROGRESS", prepareStarted,
                    null);
            throw new IllegalStateException("GUEST_PREPARATION_IN_PROGRESS");
        }
        if (start.waiter()) {
            // Waiting on the main thread would deadlock the owner, which is the same recursive
            // call protection the old preparing flag provided.  Binder/background callers join
            // the future and receive the exact result of the single owner attempt.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                prepareTrace("REJECTED", spec, "GUEST_PREPARATION_IN_PROGRESS_MAIN_THREAD",
                        prepareStarted, null);
                throw new IllegalStateException("GUEST_PREPARATION_IN_PROGRESS");
            }
            Bundle result = awaitPreparation(start.future());
            prepareTrace("RETURN_WAITER", spec,
                    result == null ? "<null>" : result.getString(RuntimeKeys.STATUS, ""),
                    prepareStarted, null);
            return result;
        }

        Runnable initialize = () -> completePreparation(start, host, spec);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initialize.run();
        } else {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            if (!mainHandler.post(initialize)) {
                completePreparationFailure(start,
                        new IllegalStateException("GUEST_PREPARE_MAIN_HANDLER_REJECTED"));
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // The main-thread owner has already completed the future synchronously.
            Bundle result = awaitPreparation(start.future());
            prepareTrace("RETURN_OWNER", spec,
                    result == null ? "<null>" : result.getString(RuntimeKeys.STATUS, ""),
                    prepareStarted, null);
            return result;
        }
        Bundle result = awaitPreparation(start.future());
        prepareTrace("RETURN_OWNER", spec,
                result == null ? "<null>" : result.getString(RuntimeKeys.STATUS, ""),
                prepareStarted, null);
        return result;
    }

    private static void completePreparation(
            ProcessInitializationGate<PreparationKey, Bundle>.Start start,
        Context host, GuestPackageSpec spec) {
        long preparationStarted = android.os.SystemClock.elapsedRealtime();
        prepareTrace("OWNER_BEGIN", spec, "", preparationStarted, null);
        try {
            Bundle result = prepareOnCurrentThread(host, spec);
            synchronized (GuestRuntimeEnvironment.class) {
                if (result == null) {
                    INITIALIZATION_GATE.completeFailure(start,
                            new IllegalStateException("GUEST_PREPARE_EMPTY_RESULT"));
                } else if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
                    String type = result.getString(RuntimeKeys.ERROR_TYPE,
                            "GUEST_PREPARE_FAILED");
                    String message = result.getString(RuntimeKeys.ERROR_MESSAGE, "");
                    INITIALIZATION_GATE.completeFailureResult(start, result,
                            new IllegalStateException(type + ":" + message));
                } else {
                    INITIALIZATION_GATE.completeSuccess(start, result);
                }
            }
            prepareTrace("OWNER_RETURN", spec,
                    result == null ? "<null>" : result.getString(RuntimeKeys.STATUS, ""),
                    preparationStarted, null);
        } catch (Throwable error) {
            prepareTrace("OWNER_FAIL", spec, error.getClass().getName(), preparationStarted,
                    error);
            completePreparationFailure(start, error);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
        }
    }

    private static void completePreparationFailure(
            ProcessInitializationGate<PreparationKey, Bundle>.Start start, Throwable error) {
        synchronized (GuestRuntimeEnvironment.class) {
            INITIALIZATION_GATE.completeFailure(start, error);
        }
    }

    private static void prepareTrace(String phase, GuestPackageSpec spec, String status,
                                     long started, Throwable error) {
        String message = "phase=" + phase
                + " request=" + (spec == null ? "" : spec.requestId)
                + " operation=" + (spec == null ? "" : spec.operationId)
                + " package=" + (spec == null ? "" : spec.packageName)
                + " process=" + (spec == null ? "" : spec.processName)
                + " session=" + (spec == null ? "" : spec.sessionId)
                + " generation=" + (spec == null ? 0L : spec.generation)
                + " slot=" + (spec == null ? -1 : spec.processSlot)
                + " status=" + (status == null ? "" : status)
                + " elapsedMs=" + Math.max(0L,
                        android.os.SystemClock.elapsedRealtime() - started)
                + " pid=" + Process.myPid();
        if (error == null) {
            android.util.Log.i("CS_GUEST_PREPARE", message);
        } else {
            android.util.Log.e("CS_GUEST_PREPARE", message
                    + " error=" + error.getClass().getName()
                    + " message=" + String.valueOf(error.getMessage()), error);
        }
    }

    private static Bundle awaitPreparation(CompletableFuture<Bundle> future) {
        try {
            return future.get(PREPARATION_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GUEST_PREPARE_MAIN_THREAD_INTERRUPTED", error);
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("GUEST_PREPARE_MAIN_THREAD_TIMEOUT", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    private static Bundle prepareOnCurrentThread(Context host, GuestPackageSpec spec) {
        long started = android.os.SystemClock.elapsedRealtime();
        prepareTrace("CURRENT_THREAD_BEGIN", spec, "", started, null);
        RuntimePerformanceTrace perf = new RuntimePerformanceTrace(
                spec.requestId, spec.operationId, spec.packageName);
        Bundle result = new Bundle();
        FrameworkHooks stagedHooks = null;
        GuestFrameworkCallRouter stagedFrameworkCallRouter = null;
        Session stagedSession = null;
        GuestProcessIdentityBridge stagedProcessIdentity = null;
        ParcelFileDescriptor loaderApkDescriptor = null;
        ParcelFileDescriptor loaderNativeArchiveDescriptor = null;
        try {
            if (current != null) {
                if (current.spec.sessionId.equals(spec.sessionId)
                        && current.spec.generation == spec.generation
                        && current.spec.packageRevision.equals(spec.packageRevision)) {
                    Bundle alreadyReady = current.status("ALREADY_READY", started);
                    perf.close();
                    return alreadyReady;
                }
                if (spec.generation <= current.spec.generation) throw new IllegalStateException("STALE_GUEST_GENERATION");
                // Revoke the old generation before configuring process-wide native state for
                // the new one. Session.shutdown() resets hooks and policy; doing it after
                // prepareNativeBootstrap() silently erased the new generation's configuration
                // during an in-process Binder-death recovery.
                Session previous = current;
                current = null;
                previous.shutdown();
            }
            IVirtualSystemServiceSession systemServiceSession;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.SYSTEM_SERVICE)) {
                systemServiceSession = requireSystemServiceSession(spec);
            }
            NativeBootstrap nativeBootstrap;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.NATIVE_BOOTSTRAP)) {
                nativeBootstrap = prepareNativeBootstrap(host, spec, systemServiceSession);
            }
            String nativeAbi = nativeBootstrap.nativeAbi;
            String packagedNativeLibraryDir = nativeBootstrap.packagedNativeLibraryDir;
            File guestDataRoot = nativeBootstrap.guestDataRoot;
            String runtimeNativeLibraryDir = nativeBootstrap.runtimeNativeLibraryDir;
            String nativeLibrarySearchPath = nativeBootstrap.nativeLibrarySearchPath;
            String guestDexPath = nativeBootstrap.guestDexPath;
            String coreDexMode = nativeBootstrap.coreDexMode;
            String nativePolicyLibraryRoot = nativeBootstrap.nativePolicyLibraryRoot;
            boolean nativePolicyConfigured = nativeBootstrap.nativePolicyConfigured;
            boolean systemIoHooksInstalled = nativeBootstrap.systemIoHooksInstalled;
            boolean nativeCrashRecorderInstalled = nativeBootstrap.nativeCrashRecorderInstalled;
            File optimized = host.getCodeCacheDir();
            if (!spec.isolatedProcess) {
                File optimizedBase = host.getCodeCacheDir();
        optimized = new File(optimizedBase, "guest/" + safe(spec.packageName)
                + "/" + safe(spec.packageRevision) + "/" + spec.generation);
        ensureDirectory(optimized);
        String coreOverlay = GuestNativeRuntimeProjection.materializeRawDexOverlay(
                guestDataRoot, nativeAbi, false, packagedNativeLibraryDir);
            if (!coreOverlay.isEmpty()) {
                    guestDexPath = coreOverlay + File.pathSeparator + spec.dexPath();
                    coreDexMode = "zip-overlay";
                } else {
                    guestDexPath = GuestNativeRuntimeProjection.prependCoreDexPath(
                            guestDataRoot, nativeAbi, false, packagedNativeLibraryDir,
                            spec.dexPath());
                    if (!guestDexPath.equals(spec.dexPath())) coreDexMode = "zip-path";
                }
                guestDexPath = GuestSharedLibraryPathResolver.appendResolvedLibraryPaths(
                        guestDexPath, spec.packageState, spec.packageUniverse);
            }
            android.util.Log.i("CS_NATIVE_RUNTIME", "projection="
                    + (!runtimeNativeLibraryDir.equals(packagedNativeLibraryDir))
                    + " runtimeDir=" + runtimeNativeLibraryDir
                    + " packagedDir=" + packagedNativeLibraryDir
                    + " dexPathProjected=" + !guestDexPath.equals(spec.dexPath())
                    + " coreDexMode=" + coreDexMode);
            if (Build.VERSION.SDK_INT >= 29 && !NativePolicy.installHiddenApiBridge()) {
                throw new IllegalStateException("HIDDEN_API_BRIDGE_UNAVAILABLE");
            }
            // A translated guest ABI (for example ARM64 guest code on the x86_64 RD
            // emulator) must not receive a rewritten JNIEnv/JVM function table. The
            // probe is diagnostic only; disabling it for an ABI mismatch preserves the
            // native bridge's calling convention while retaining the probe for native
            // guests running with the host ABI.
            boolean nativeCodePresent = spec.containsNativeCode
                    && spec.nativeAbi != null && !spec.nativeAbi.trim().isEmpty();
            boolean translatedGuestAbi = nativeCodePresent && isTranslatedGuestAbi(spec.nativeAbi);
            boolean jniEx = nativeCodePresent && !translatedGuestAbi
                    && NativePolicy.installJniPendingExceptionProbe();
            android.util.Log.i("CS_JNI_EX", "PROBE installed=" + jniEx
                    + " translatedAbi=" + translatedGuestAbi
                    + " guestAbi=" + safe(spec.nativeAbi)
                    + " hostAbi=" + safe(hostAbi()));
            GuestClassLoader loader;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.CLASSLOADER)) {
            if (spec.isolatedProcess) {
                java.util.List<java.nio.ByteBuffer> isolatedDexBuffers =
                        loadIsolatedGuestDexBuffers(spec);
                if (!spec.nativeLibraryDescriptors.isEmpty()) {
                    loaderNativeArchiveDescriptor = createNativeLibraryArchive(spec);
                    android.util.Log.i("CS_NATIVE_BIND", "FD_NATIVE_ARCHIVE_MATERIALIZED fd="
                            + loaderNativeArchiveDescriptor.getFd() + " count="
                            + spec.nativeLibraryDescriptors.size());
                } else if (spec.apkDescriptor != null && spec.containsNativeCode
                        && spec.nativeAbi != null && !spec.nativeAbi.trim().isEmpty()) {
                    // Zip-backed NativeLibraryElement lookup cannot open the host APK through
                    // the isolated UID's /proc view on all Android 12 builds. Materialize the
                    // already verified APK capability into a process-local memfd: the bytes
                    // stay inside this isolated process, while DexPathList can use the normal
                    // apk!/lib/<abi> NativeLoader path and preserve JNI_OnLoad semantics.
                    loaderApkDescriptor = NativePolicy.materializeCapabilityFile(spec.apkDescriptor);
                    android.util.Log.i("CS_NATIVE_BIND", "FD_APK_MATERIALIZED fd="
                            + loaderApkDescriptor.getFd() + " abi=" + safe(spec.nativeAbi));
                }
                loader = new GuestClassLoader(isolatedDexBuffers,
                        isolatedNativeLibraryPath(spec,
                                loaderApkDescriptor, loaderNativeArchiveDescriptor),
                        GuestRuntimeEnvironment.class.getClassLoader(), spec.packageName,
                        declaredGuestClasses(spec));
            } else {
                loader = new GuestClassLoader(guestDexPath, optimized.getAbsolutePath(),
                        emptyToNull(nativeLibrarySearchPath), GuestRuntimeEnvironment.class.getClassLoader(),
                        spec.packageName, declaredGuestClasses(spec));
            }
            }
            loader.configureNativeCompatibility(translatedGuestAbi);
            GuestNativeBindingDiagnostic.installProcessProbes();
            GuestNativeBindingDiagnostic.recordLoader("guest.base", loader);
            GuestNativeBindingDiagnostic.recordLoader("guest.dex", loader.definingLoader());
            GuestResourceLoader.LoadedResources loadedResources;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.RESOURCES)) {
                loadedResources = spec.isolatedProcess
                        ? GuestResourceLoader.load(host, spec.apkDescriptor, spec.splitDescriptors)
                        : GuestResourceLoader.load(host, spec.apkPath, spec.splitPathArray());
            }
            PackageManager processPackageManager = host.getPackageManager();
            // VirtualPackageStateBuilder already parsed the manifest at import time and carries
            // the authoritative ApplicationInfo (including split paths and appComponentFactory).
            // Re-parsing a large commercial archive through Host PackageManager here duplicated
            // cold-start work and could disagree with the virtual package identity.  Isolated
            // descriptor requests use the same authority projection; their bytes remain verified
            // separately in verifyPackageRevision().
            ApplicationInfo parsedApplicationInfo = spec.packageState.applicationInfo();
            if (parsedApplicationInfo != null) {
                parsedApplicationInfo = new ApplicationInfo(parsedApplicationInfo);
            }
            String appComponentFactory = parsedApplicationInfo == null ? ""
                    : GuestApplicationInfoFactory.readComponentFactory(parsedApplicationInfo);
            GuestContext guestContext = new GuestContext(host, spec, loader,
                    loadedResources.resources, loadedResources.assets, processPackageManager,
                    loadedResources.manifestMetadata.application(), appComponentFactory,
                    parsedApplicationInfo);
            // Native policy also owns the process-wide framework/native boundary for a pure
            // Java Guest (not only APK-provided .so files).  Such a Guest has no package ABI,
            // but its process still has the host ABI and must not enter the policy with an
            // empty abiName.
            boolean requiresNativeHooks = !spec.isolatedProcess;
            // A foreign-ABI guest is executed by Android's native bridge. The host process
            // cannot safely rewrite that guest ELF's PLT/GOT, so the platform bridge remains
            // the loader boundary and Java framework proxies provide the compatibility path.
            // Keep a narrow host-ABI lifetime boundary even for a translated guest. It protects
            // the CAS-owned service from direct native kill/_exit/abort calls without parsing the
            // foreign Guest ELF. Java Runtime.nativeExit is handled separately by the supported
            // JNI registration boundary and is forwarded as a real process-lifetime event, just
            // as VA/NBB do; Binder death/recovery owns the resulting new process record.
            // Do not patch the translated guest ELF or its general host-ABI PLT entries: the
            // platform bridge may enter those modules with translated register state.
            boolean enableNativeHooks = requiresNativeHooks && nativePolicyConfigured
                    && !translatedGuestAbi;
            if (requiresNativeHooks && !nativePolicyConfigured) {
                throw new IllegalStateException("NATIVE_FILE_POLICY_UNAVAILABLE");
            }
            if (requiresNativeHooks) NativePolicy.setGuestProcessExitAllowed(false);
            boolean processLifetimeHooksInstalled = translatedGuestAbi && nativePolicyConfigured
                    && NativePolicy.installProcessLifetimeHooks();
            if (translatedGuestAbi && !processLifetimeHooksInstalled) {
                throw new IllegalStateException("NATIVE_PROCESS_LIFETIME_HOOK_INSTALL_FAILED:"
                        + NativePolicy.hookStatus());
            }
            boolean nativeHooksInstalled = systemIoHooksInstalled
                    || (enableNativeHooks && NativePolicy.installHooks(nativePolicyLibraryRoot));
            if (enableNativeHooks && !nativeHooksInstalled) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_INSTALL_FAILED:" + NativePolicy.hookStatus());
            }
            boolean nativeBoundaryAvailable = nativeHooksInstalled || translatedGuestAbi;
            // Runtime.nativeLoad is an ART native-method registration boundary. Replacing that
            // method in an ARM64 guest running through the x86_64 native bridge is not safe: the
            // bridge owns the foreign-ABI load path and may call it from a translated frame.
            // Native guests keep the diagnostic wrapper; translated guests use the platform
            // loader unchanged and rely on the structured loader evidence around it.
            boolean nativeLoadDiag = nativeCodePresent && !translatedGuestAbi
                    && NativePolicy.installNativeLoadDiagnostic();
            // A translated guest cannot safely receive host-ABI PLT/GOT patches.  It also must
            // not replace Runtime.nativeLoad globally: the Android foreign-ABI bridge and
            // WebView/Chromium call this entry from translated frames, and a JNI replacement
            // changes the callback ABI for every native library in the process.  The framework
            // PathClassLoader already owns the verified guest native directory, so translated
            // guests use the platform loader unchanged. Native-ABI guests keep the diagnostic
            // wrapper and the PLT/IO policy above.
            boolean nativeLoadRedirect = false;
            String nativeBoundaryMode = translatedGuestAbi
                    ? (nativeLoadRedirect ? "translated-loader-redirect" : "translated-platform-loader")
                    : (nativeHooksInstalled ? "native-plt-io" : "java-framework-only");
            if (processLifetimeHooksInstalled) nativeBoundaryMode += "+host-lifetime";
            android.util.Log.i("CS_NATIVE_BIND", "PROBE nativeLoadDiagnostic=" + nativeLoadDiag
                    + " nativeLoadRedirect=" + nativeLoadRedirect
                    + " translatedAbi=" + translatedGuestAbi
                    + " processLifetimeHooks=" + processLifetimeHooksInstalled
                    + " boundaryMode=" + nativeBoundaryMode);
            // handleBindApplication publishes process identity before LoadedApk asks the
            // AppComponentFactory to wrap the ClassLoader. The hidden-API bridge must be
            // installed first so Process.setArgV0 is visible.
            stagedProcessIdentity = GuestProcessIdentityBridge.bind(
                    guestContext.getApplicationInfo(), spec);
            VirtualPackageMetadata packageMetadata = GuestPackageMetadataMapper.fromSnapshot(
                    spec.packageState, guestContext.getApplicationInfo(), loadedResources.manifestMetadata);
            for (VirtualPackageMetadata.Component component : packageMetadata.components()) {
                if (component.type() == VirtualPackageMetadata.Type.SERVICE
                        && component.foregroundServiceType() != 0) {
                    android.util.Log.i("CS_FGS_PROJECTION", "GUEST_METADATA service="
                            + component.className() + " declaredType="
                            + component.foregroundServiceType());
                }
            }
            List<VirtualPackageMetadata> packageViews = new ArrayList<>();
            packageViews.add(packageMetadata);
            for (VirtualPackageProjectionSnapshot projection : spec.packageUniverse) {
                if (projection == null || spec.packageName.equals(
                        projection.packageState().packageName())) continue;
                packageViews.add(GuestPackageMetadataMapper.fromProjection(projection));
            }
            VirtualPackageUniverse packageUniverse = new VirtualPackageUniverse(packageViews);
            VirtualSystemServiceState virtualServices = new VirtualSystemServiceState(
                    new RemoteVirtualSystemServiceAuthority(systemServiceSession, loader));
            loader.configureDetection(virtualServices.compatibilityProfile().detection());
            WebViewProfileManager.Profile webViewProfile = WebViewProfileManager.install(
                    spec, virtualServices.compatibilityProfile().webView());
            guestContext.configureWebViewProvider(
                    virtualServices.compatibilityProfile().webView().providerPackage());
            GuestFrameworkCallRouter frameworkCallRouter = new GuestFrameworkCallRouter(
                    guestContext, spec, virtualServices.pendingIntents(),
                    new GuestPendingIntentDispatcher(guestContext, spec),
                    host.getPackageName());
            virtualServices.alarms().setRecoveredDelivery(alarm ->
                    com.warden.controlledsandbox.contract.VirtualAlarmSnapshot.PENDING_INTENT.equals(alarm.deliveryPath())
                            && !alarm.pendingIntentTokenId().isEmpty()
                            && frameworkCallRouter.sendPersistentPendingIntent(alarm.pendingIntentTokenId()));
            stagedFrameworkCallRouter = frameworkCallRouter;
            OrderedReceiverFinishInterceptor orderedReceiverFinishInterceptor =
                    frameworkCallRouter.orderedReceivers();
            VirtualPermissionPolicy permissionPolicy = permissionPolicy(spec.packageState);
            SandboxAppOpsPolicy appOpsPolicy = appOpsPolicy(spec.packageState);
            GuestCapabilityAuditLog capabilityAudit = new GuestCapabilityAuditLog();
            CapabilityLeaseRegistry capabilityLeases = new CapabilityLeaseRegistry();
            CapabilityAccessPolicy capabilityPolicy = new CapabilityAccessPolicy(permissionPolicy::isGranted, appOpsPolicy::mode);
            if (nativePolicyConfigured && !NativePolicy.configureAudioCapture(spec.sessionId, spec.generation,
                    capabilityPolicy.allowed(CapabilityAccessPolicy.MICROPHONE))) {
                throw new IllegalStateException("NATIVE_AUDIO_POLICY_UNAVAILABLE");
            }
            GuestIdentity guestIdentity = new GuestIdentity(spec.packageName, spec.virtualUid,
                    guestContext.getApplicationInfo(), new HashSet<>(spec.permissions),
                    host.getPackageName(), Process.myUid(), packageMetadata, spec.processName,
                    spec.virtualUserId, spec.generation, permissionPolicy, appOpsPolicy,
                    capabilityAudit, capabilityLeases, virtualServices, spec.packageRevision,
                    packageUniverse);
            guestIdentity.installContentObserverBridge(
                    new GuestContentObserverBridge(spec, guestContext.mainThread));
            FrameworkHooks frameworkHooks;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.FRAMEWORK_HOOK)) {
                frameworkHooks = FrameworkHooks.install(guestContext, host, processPackageManager,
                        guestIdentity, frameworkCallRouter, nativeBoundaryAvailable);
            }
            stagedHooks = frameworkHooks;
            guestContext.sealSystemServices(frameworkHooks.report().installedServices());
            frameworkHooks.report().requireMandatoryReady();
            if (!Boolean.TRUE.equals(frameworkHooks.report().installedServices().get("sms"))) {
                throw new IllegalStateException("VIRTUAL_SMS_PROXY_REQUIRED");
            }
            android.util.Log.i("CS_SMS_PROXY", "SMS_READY binding="
                    + frameworkHooks.report().bindingDetails().get("sms"));
            CapabilityProxyReadiness.require(frameworkHooks.report().installedServices(),
                    spec.packageState.permissions());
            DeviceServiceProxyReadiness.require(frameworkHooks.report().installedServices(),
                    frameworkHooks.report().bindingDetails(), frameworkHooks.report().failures(),
                    virtualServices.deviceServiceProfile());
            for (String capability : new String[] {"location", "settingsIdentity", "telephony",
                    "phoneSubInfo", "telephonyRegistry", "subscription", "wifiScanner",
                    "bluetooth", "sensorCatalog"}) {
                android.util.Log.i("CS_DEVICE_SERVICE", capability + " READY binding="
                        + frameworkHooks.report().bindingDetails().get(capability));
            }
            InteractionProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.interactionProfile());
            NetworkServiceProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.networkServiceProfile(), nativeBoundaryAvailable);
            ApplicationEnvironmentProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.applicationEnvironmentProfile());
            CompatibilityProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.compatibilityProfile(), nativePolicyConfigured);
            PolicyServicesProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.policyServicesProfile());
            MediaCommunicationProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.mediaCommunicationProfile());
            PeripheralServicesProxyReadiness.requireNfc(frameworkHooks.report().installedServices(),
                    virtualServices.peripheralServicesProfile());
            android.util.Log.i("CS_NFC_PROXY", "NFC_READY binding="
                    + frameworkHooks.report().bindingDetails().get("nfc")
                    + " feature=android.hardware.nfc");
            PeripheralServicesProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.peripheralServicesProfile());
            VirtualCameraProfileSnapshot cameraProfile =
                    virtualServices.peripheralServicesProfile().camera();
            android.util.Log.i("CS_CAMERA_PROXY", "CAMERA_READY binding="
                    + frameworkHooks.report().bindingDetails().get("camera")
                    + " feature=android.hardware.camera mode=" + cameraProfile.mode()
                    + " available=" + cameraProfile.cameraAvailable()
                    + " ids=" + cameraProfile.cameraIds().size()
                    + " frontIds=" + cameraProfile.frontCameraIds().size());
            // Camera1 is a first-use capability.  GuestClassLoader installs the adapter when the
            // Guest actually loads android.hardware.Camera; doing it here made every launch pay
            // the native symbol lookup/patch cost even for camera-free applications.
            boolean camera1AdapterInstalled = false;
            if (nativePolicyConfigured) {
                configureCamera1NativeProfile(guestContext.getFilesDir(), spec, host,
                        cameraProfile);
                android.util.Log.i("CS_CAMERA1_NATIVE", (!translatedGuestAbi
                        ? "ADAPTER_DEFERRED_CAMERA_CLASS_LOAD" : "ADAPTER_DEFERRED_TRANSLATED_ABI") + " status="
                        + NativePolicy.camera1Status());
            }
            PrivilegedServicesProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.privilegedServicesProfile());
            // LoadedApk asks AppComponentFactory after bind and after ActivityManager is
            // proxied so factory process-name reads see the Guest identity, not host:guestN.
            // Android's LoadedApk/NativeLoader path only recognizes a platform dex loader.  The
            // policy facade remains the loader used by CAS-owned lookups, but Framework-owned
            // Application/component construction must receive the actual PathClassLoader.  VA
            // and NBB both make this distinction implicitly by letting LoadedApk own the loader;
            // passing the facade here makes VMRuntime report "Unsupported class loader" and
            // breaks native-bridge startup in apps such as Quark/Tinker.
            ClassLoader frameworkLoader = loader.definingLoader();
            ClassLoader processLoader = GuestComponentFactory.instantiateClassLoader(
                    frameworkLoader, appComponentFactory, guestContext.getApplicationInfo());
            guestContext.installProcessClassLoader(processLoader);
            android.util.Log.i("CS_GUEST_FACTORY", "classLoader factory=" + appComponentFactory
                    + " base=" + loader.getClass().getName()
                    + " frameworkBase=" + frameworkLoader.getClass().getName()
                    + " process=" + processLoader.getClass().getName());
            GuestNativeBindingDiagnostic.recordLoader("guest.process", processLoader);
            // Keep the platform bindApplication ordering: ActivityThread's LoadedApk projection
            // is published before the Guest Application is constructed.  The object is still
            // instantiated through the declared AppComponentFactory and attached to GuestContext
            // because the host package owns the real Android ContextImpl.
            Session session = new Session(spec, loader, guestContext, null, loadedResources, frameworkHooks,
                    frameworkCallRouter, packageMetadata, permissionPolicy, appOpsPolicy,
                    capabilityPolicy, capabilityAudit, capabilityLeases, virtualServices, nativePolicyConfigured,
                    nativeHooksInstalled, nativeLoadRedirect, nativeBoundaryMode,
                    camera1AdapterInstalled, nativeCrashRecorderInstalled, webViewProfile,
                    stagedProcessIdentity, loaderApkDescriptor, loaderNativeArchiveDescriptor);
            stagedSession = session;
            loaderApkDescriptor = null;
            loaderNativeArchiveDescriptor = null;
            session.loadedApkBridge = GuestLoadedApkBridge.install(session);
            Application application;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.APPLICATION_ATTACH)) {
                application = guestContext.mainThread.call(
                        () -> instantiateApplication(spec, processLoader,
                                GuestApplicationInfoFactory.readComponentFactory(
                                        guestContext.getApplicationInfo())));
            }
            guestContext.application(application);
            // LoadedApk.makeApplication() is still called by the real ActivityThread when the
            // first framework-owned Activity transaction arrives. Publish the already-created
            // Guest Application into that exact LoadedApk before the session becomes READY;
            // otherwise Android will construct a second Application and apps with process-global
            // SDK state (for example Quark's platform client API) fail during Activity launch.
            session.loadedApkBridge.bindApplication(application);
            session.bindApplication(application);
            GuestNativeBindingDiagnostic.recordClass("application", application.getClass());
            stagedProcessIdentity.attachApplication(application);
            guestContext.mainThread.run(() -> invokeNearestAttachBaseContext(application, guestContext));
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_CREATE:"
                        + NativePolicy.hookStatus());
            }
            if (processLifetimeHooksInstalled && !NativePolicy.refreshProcessLifetimeHooks()) {
                throw new IllegalStateException(
                        "NATIVE_PROCESS_LIFETIME_HOOK_REFRESH_FAILED_AFTER_APPLICATION_CREATE:"
                                + NativePolicy.hookStatus());
            }
            stagedProcessIdentity = null;
            session.components = new GuestComponentRuntime(session);
            session.jobServices = new GuestJobServiceBridge(session);
            virtualServices.jobs().setExecutionListener(new com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobExecutionListener() {
                @Override public boolean onStart(int guestJobId, Object jobPayload,
                        com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobParametersRecord parameters,
                        com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobExecution execution) {
                    return session.onVirtualJobStart(guestJobId, jobPayload, parameters, execution);
                }
                @Override public boolean onStop(int guestJobId,
                        com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobParametersRecord parameters) {
                    return session.onVirtualJobStop(guestJobId, parameters);
                }
            });
            synchronized (GuestRuntimeEnvironment.class) { current = session; }
            // Service CREATE_SERVICE/BIND/SERVICE_ARGS/STOP messages are owned by Android's
            // ActivityThread. Install this before Application.onCreate so services started from
            // application bootstrap take the same framework path as Activity launches.
            session.serviceFrameworkBridge = GuestActivityThreadServiceBridge.install(session);
            guestContext.installServiceFrameworkBridge(session.serviceFrameworkBridge);
            // Install before Application.onCreate so launches triggered by Application startup
            // enter the real ActivityThread Instrumentation path as well. The bridge is restored
            // during Session.shutdown and is generation-owned just like native hooks and Binder
            // callbacks.
            session.activityThreadInstrumentation = GuestActivityThreadInstrumentation.install(session);
            stagedHooks = null;
            stagedFrameworkCallRouter = null;
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.PROVIDER_PREPARE)) {
                session.components.prepareDeclaredProviders();
            }
            try (RuntimePerformanceTrace.Stage ignored = perf.stage(RuntimePerformanceTrace.APPLICATION_ONCREATE)) {
                session.mainThread.run(application::onCreate);
            }
            // Application/Provider bootstrap is allowed to install process-local SDK hooks,
            // but it must not replace the ActivityThread transport used by the Guest lifecycle.
            // Reassert the bridge before publishing READY so the first physical Stub transaction
            // is still delivered through framework Instrumentation.
            session.activityThreadInstrumentation =
                    GuestActivityThreadInstrumentation.ensureInstalled(session);
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_ONCREATE:"
                        + NativePolicy.hookStatus());
            }
            if (processLifetimeHooksInstalled && !NativePolicy.refreshProcessLifetimeHooks()) {
                throw new IllegalStateException(
                        "NATIVE_PROCESS_LIFETIME_HOOK_REFRESH_FAILED_AFTER_APPLICATION_ONCREATE:"
                                + NativePolicy.hookStatus());
            }
            Bundle ready = session.status("READY", started);
            RuntimeEventLog.event("GUEST_PREPARED", ready);
            prepareTrace("CURRENT_THREAD_RETURN", spec,
                    ready.getString(RuntimeKeys.STATUS, ""), started, null);
            perf.close();
            stagedSession = null;
            return ready;
        } catch (Throwable error) {
            try {
                if (stagedSession != null) stagedSession.shutdown();
                else {
                    if (stagedProcessIdentity != null) stagedProcessIdentity.close();
                    if (stagedHooks != null) stagedHooks.close();
                    if (stagedFrameworkCallRouter != null) stagedFrameworkCallRouter.close();
                }
                NativePolicy.resetAudioCapture();
                NativePolicy.resetCamera1();
                NativePolicy.resetHooks();
                NativePolicy.resetPolicy();
                if (loaderApkDescriptor != null) {
                    try { loaderApkDescriptor.close(); } catch (Throwable ignored) { }
                }
                if (loaderNativeArchiveDescriptor != null) {
                    try { loaderNativeArchiveDescriptor.close(); } catch (Throwable ignored) { }
                }
                synchronized (GuestRuntimeEnvironment.class) {
                    current = null;
                }
            } finally {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            }
            result.putString(RuntimeKeys.STATUS, "FAILED");
            result.putString(RuntimeKeys.ERROR_TYPE, error.getClass().getName());
            result.putString(RuntimeKeys.ERROR_MESSAGE, String.valueOf(error.getMessage()));
            result.putString("stack", stackSummary(error));
            result.putInt("pid", Process.myPid());
            result.putLong("durationMs", android.os.SystemClock.elapsedRealtime() - started);
            android.util.Log.e("CS_RUNTIME", "GUEST_PREPARE_FAILED_STACK\n"
                    + result.getString("stack", ""));
            RuntimeEventLog.event("GUEST_PREPARE_FAILED", result);
            prepareTrace("CURRENT_THREAD_FAIL", spec,
                    result.getString(RuntimeKeys.ERROR_TYPE, "GUEST_PREPARE_FAILED"), started,
                    error);
            perf.close();
            return result;
        }
    }

    private static void verifyIsolatedArtifactCapability(GuestPackageSpec spec) {
        ParcelFileDescriptor opened = null;
        FileInputStream input = null;
        try {
            opened = spec.apkDescriptor.dup();
            input = new FileInputStream(opened.getFileDescriptor());
            // The descriptor crosses several Binder parcels before it reaches an
            // isolated process.  It may share an open-file description whose offset
            // was advanced by an earlier capability hop; capability validation must
            // inspect the APK header from the beginning of the artifact.
            input.getChannel().position(0L);
            byte[] probe = new byte[4];
            int read = input.read(probe);
            if (read != 4 || probe[0] != 'P' || probe[1] != 'K') {
                throw new IllegalStateException("ISOLATED_APK_CAPABILITY_CONTENT_INVALID");
            }
            android.util.Log.i("CS_ISOLATED_IO", "apkCapability=PASS entry=" + spec.apkEntryName);
        } catch (Throwable error) {
            android.util.Log.e("CS_ISOLATED_IO", "openatCapability=FAIL", error);
            throw new IllegalStateException("ISOLATED_APK_CAPABILITY_OPEN_FAILED", error);
        } finally {
            if (input != null) try { input.close(); } catch (Throwable ignored) { }
            if (opened != null) try { opened.close(); } catch (Throwable ignored) { }
        }
    }

    private static IVirtualSystemServiceSession requireSystemServiceSession(GuestPackageSpec spec) {
        IVirtualSystemServiceSession session = IVirtualSystemServiceSession.Stub.asInterface(
                spec.virtualSystemServiceBinder);
        if (session == null) {
            throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_INVALID");
        }
        return session;
    }

    private static NativeBootstrap prepareNativeBootstrap(Context host, GuestPackageSpec spec,
                                                           IVirtualSystemServiceSession systemServiceSession)
            throws Exception {
        // Verify every base/split byte and the deterministic set revision before configuring
        // NativeLoader, IO capabilities, crash handling, or any process-local projection. A
        // replaced split must fail before native state can observe the old revision.
        verifyPackageRevision(spec);
        VirtualNetworkServiceProfileSnapshot nativeNetworkProfile =
                systemServiceSession.getNetworkServiceProfile();
        if (nativeNetworkProfile == null) {
            throw new IllegalStateException("VIRTUAL_NETWORK_PROFILE_MISSING");
        }
        String nativeAbi = spec.nativeAbi;
        String packagedNativeLibraryDir = spec.effectiveNativeLibraryDir();
        File guestDataRoot = new File(spec.dataRootFile(), "data");
        String runtimeNativeLibraryDir = GuestNativeRuntimeProjection.select(
                spec, guestDataRoot, packagedNativeLibraryDir);
        String nativeLibrarySearchPath = GuestNativeRuntimeProjection.searchPath(
                spec, guestDataRoot, packagedNativeLibraryDir);
        String guestDexPath = spec.dexPath();
        String coreDexMode = "apk";
        int virtualPid = 20000 + (spec.virtualUserId * 100) + spec.processSlot;
        // The selected runtime directory is the authoritative CAS native root for the defining
        // ClassLoader and NativePolicy.  ApplicationInfo.nativeLibraryDir is intentionally kept
        // on the APK-owned root by GuestApplicationInfoFactory: application SDKs use that public
        // contract to resolve their own packaged libraries, while U4/WebView code resolves its
        // core through the separate runtime search path.
        String nativePolicyLibraryRoot = runtimeNativeLibraryDir.isEmpty()
                ? spec.nativeLibraryDir : runtimeNativeLibraryDir;
        if (nativePolicyLibraryRoot == null || nativePolicyLibraryRoot.trim().isEmpty()) {
            // A Java-only package has no APK lib/<abi> directory, but the process-wide native
            // boundary still needs a Guest-contained root for path policy and DNS hooks.
            nativePolicyLibraryRoot = spec.dataRoot;
        }
        boolean nativeCodePresent = spec.containsNativeCode
                && nativeAbi != null && !nativeAbi.trim().isEmpty();
        String policyAbi = nativeCodePresent ? nativeAbi : hostAbi();
        boolean nativePolicyConfigured = NativePolicy.configure(spec.sessionId,
                spec.generation, spec.packageName, spec.processName, spec.virtualUserId,
                spec.virtualUid, virtualPid, policyAbi, spec.dataRoot, spec.apkPath,
                nativePolicyLibraryRoot, true, new String[0], new String[0], new String[0], new String[0],
                new String[0], new String[0],
                nativeNetworkIdentity(spec.packageName, spec.virtualUserId, nativeNetworkProfile));
        boolean systemIoHooksInstalled = installIsolatedIoCapabilities(spec);
        RuntimeDiagnostics.install(host, "guest-slot-" + spec.processSlot,
                spec.isolatedProcess ? new File(spec.dataRootFile(), "diagnostics") : null);
        if (spec.isolatedProcess) verifyIsolatedArtifactCapability(spec);
        File nativeCrashFile = RuntimeDiagnostics.nativeCrashFile();
        // Do not install a second signal-chain handler in a foreign-ABI process.  The
        // platform bridge and Quark's CrashSDK already own that chain; CAS's recorder is
        // retained for native-ABI guests where the calling convention is ours.
        boolean nativeCrashRecorderInstalled = nativePolicyConfigured && !isTranslatedGuestAbi(spec.nativeAbi)
                && nativeCrashFile != null
                && NativePolicy.installCrashRecorder(nativeCrashFile.getAbsolutePath());
        return new NativeBootstrap(nativeAbi, packagedNativeLibraryDir, guestDataRoot,
                runtimeNativeLibraryDir, nativeLibrarySearchPath, guestDexPath, coreDexMode,
                nativePolicyLibraryRoot, nativePolicyConfigured, systemIoHooksInstalled,
                nativeCrashRecorderInstalled);
    }

    private static boolean installIsolatedIoCapabilities(GuestPackageSpec spec) {
        if (!spec.isolatedProcess) return false;
        if (spec.dataRootDescriptor == null || spec.apkDescriptor == null) {
            throw new IllegalStateException("ISOLATED_FILE_CAPABILITIES_MISSING");
        }
        if (!NativePolicy.configureFileCapabilities(spec.dataRootDescriptor,
                spec.apkDescriptor, spec.apkEntryName, spec.nativeLibraryDescriptor)) {
            throw new IllegalStateException("ISOLATED_FILE_CAPABILITIES_UNAVAILABLE");
        }
        boolean installed = NativePolicy.installSystemIoHooks();
        android.util.Log.i("CS_ISOLATED_IO", "capabilities=installed systemHooks="
                + installed + " hookStatus=" + NativePolicy.hookStatus());
        if (!installed) {
            throw new IllegalStateException("ISOLATED_SYSTEM_IO_HOOK_INSTALL_FAILED:",
                    new IllegalStateException(NativePolicy.hookStatus()));
        }
        return true;
    }

    private static void verifyPackageRevision(GuestPackageSpec spec) throws Exception {
        if (spec.isolatedProcess && spec.apkDescriptor == null) {
            throw new IllegalStateException("ISOLATED_APK_CAPABILITY_MISSING");
        }
        if (!spec.isolatedProcess && spec.packageRevisionVerifiedByBroker) {
            // RuntimeGuestRequestValidator has already hashed the complete immutable base/split
            // set and bound the resulting revision to this request.  Re-hashing the same sealed
            // content on the Guest main thread was a duplicate cold-start cost.  Isolated APKs
            // arrive through transferred descriptors and intentionally never use this shortcut.
            android.util.Log.i("CS_REVISION_VERIFY", "skipped broker-verified revision="
                    + spec.packageRevision);
            return;
        }
        com.warden.controlledsandbox.domain.session.PackageRevision verifiedRevision =
                spec.isolatedProcess
                        ? PackageRevisionSetVerifier.verify(spec.apkDescriptor,
                        spec.baseApkSha256, spec.splitDescriptorArtifacts(), spec.apkVersionCode,
                        spec.apkSha256)
                        : PackageRevisionSetVerifier.verify(spec.apkFile(), spec.baseApkSha256,
                        spec.splitArtifacts(), spec.apkVersionCode, spec.apkSha256);
        if (!verifiedRevision.canonical().equals(spec.packageRevision)) {
            throw new SecurityException("PACKAGE_REVISION_MISMATCH");
        }
    }

    public static synchronized Session require(String sessionId, long generation) {
        if (current == null) throw new IllegalStateException("GUEST_NOT_PREPARED");
        if (!current.spec.sessionId.equals(sessionId)) throw new SecurityException("SESSION_MISMATCH");
        if (current.spec.generation != generation) throw new SecurityException("GENERATION_MISMATCH");
        return current;
    }

    /**
     * Reasserts the ActivityThread bridge at the last safe boundary before a physical Stub
     * Activity is allowed to create the Guest object. Guest SDK bootstrap code is permitted to
     * install process hooks, and some SDKs replace {@code ActivityThread.mInstrumentation} after
     * the normal PREPARE return. Waiting until the next Activity launch in that case sends the
     * framework transaction through {@link com.warden.controlledsandbox.runtime.component.activity.StubActivityBase}
     * without a Guest ActivityThread route, which can leave a logical Activity RESUMED with no
     * published Surface. This method is generation-owned and fail-closed: a valid route cannot
     * proceed when the framework bridge cannot be restored.
     */
    public static synchronized void ensureFrameworkActivityInstrumentation(
            String sessionId, long generation) {
        Session session = require(sessionId, generation);
        try {
            session.activityThreadInstrumentation =
                    GuestActivityThreadInstrumentation.ensureInstalled(session);
            Bundle evidence = new Bundle();
            evidence.putString(RuntimeKeys.STATUS, "REASSERTED");
            evidence.putString(RuntimeKeys.SESSION_ID, session.sessionId());
            evidence.putLong(RuntimeKeys.GENERATION, session.generation());
            evidence.putString(RuntimeKeys.PACKAGE_NAME, session.packageName());
            evidence.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
            evidence.putInt("pid", Process.myPid());
            RuntimeEventLog.event("GUEST_INSTRUMENTATION_ROUTE_FENCE", evidence);
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "ROUTE_FENCE_REASSERTED session=" + session.sessionId()
                            + " generation=" + session.generation()
                            + " package=" + session.packageName()
                            + " pid=" + Process.myPid());
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw error instanceof RuntimeException
                    ? (RuntimeException) error
                    : new IllegalStateException("GUEST_ACTIVITY_INSTRUMENTATION_REASSERT_FAILED",
                            error);
        }
    }

    /** Delivers a Broker-owned IIntentSender send through the current Guest generation. */
    static Bundle sendPersistentPendingIntent(String sessionId, long generation,
                                              Bundle request) {
        Session session = require(sessionId, generation);
        String tokenId = request == null
                ? "" : request.getString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, "");
        if (tokenId.trim().isEmpty()) throw new SecurityException("PENDING_INTENT_TOKEN_REQUIRED");
        android.content.Intent fillIn = request == null
                || !request.getBoolean(RuntimeKeys.PENDING_INTENT_FILL_IN, false) ? null
                : RuntimeIntentWireCodec.decode(request);
        String permission = request == null ? ""
                : request.getString(RuntimeKeys.PENDING_INTENT_SENDER_PERMISSION, "");
        int flagsMask = request == null ? 0
                : request.getInt(RuntimeKeys.PENDING_INTENT_FLAGS_MASK, 0);
        int flagsValues = request == null ? 0
                : request.getInt(RuntimeKeys.PENDING_INTENT_FLAGS_VALUES, 0);
        int resultCode = request == null ? 0
                : request.getInt(RuntimeKeys.PENDING_INTENT_RESULT_CODE, 0);
        VirtualPendingIntentRegistry.SendRequest send = new VirtualPendingIntentRegistry.SendRequest(
                fillIn, flagsMask, flagsValues, permission, -1, resultCode);
        PendingIntentFrameworkInterceptor.PersistentSendResult delivery =
                session.frameworkCallRouter.sendPersistentPendingIntentResult(tokenId, send);
        if (!delivery.delivered()) {
            throw new IllegalStateException("PENDING_INTENT_DELIVERY_REJECTED");
        }
        Bundle result = new Bundle();
        result.putString(RuntimeKeys.STATUS, "PENDING_INTENT_DELIVERED");
        result.putString(RuntimeKeys.PENDING_INTENT_TOKEN_ID, tokenId);
        result.putInt("pendingIntentSendResult", delivery.resultCode());
        if (delivery.deliveredIntent() != null) {
            RuntimeIntentWireCodec.encode(result, delivery.deliveredIntent());
            result.putBoolean(RuntimeKeys.PENDING_INTENT_DELIVERED_INTENT, true);
        }
        result.putString(RuntimeKeys.SESSION_ID, session.sessionId());
        result.putLong(RuntimeKeys.GENERATION, session.generation());
        result.putInt(RuntimeKeys.PROCESS_SLOT, session.processSlot());
        return result;
    }

    static synchronized Bundle status() {
        if (current == null) {
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "IDLE");
            out.putInt("pid", Process.myPid());
            addInitializationStatus(out);
            return out;
        }
        // VA/NBB keep the process-reuse decision on the bindApplication/process record state
        // boundary.  The Broker only needs this compact readiness projection to distinguish a
        // live bound process from a stale lease; it must not synchronously rebuild diagnostics,
        // query every isolated service declaration, or marshal the package projection on every
        // hot Activity launch.
        Bundle out = current.readinessStatus(
                INITIALIZATION_GATE.initializing() ? "PREPARING" : "READY",
                android.os.SystemClock.elapsedRealtime());
        addInitializationStatus(out);
        return out;
    }

    static synchronized Bundle diagnosticStatus() {
        if (current == null) {
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "IDLE");
            out.putInt("pid", Process.myPid());
            addInitializationStatus(out);
            return out;
        }
        Bundle out = current.status(INITIALIZATION_GATE.initializing() ? "PREPARING" : "READY",
                android.os.SystemClock.elapsedRealtime());
        addInitializationStatus(out);
        return out;
    }

    private static void addInitializationStatus(Bundle out) {
        out.putString("runtimeInitializationState", INITIALIZATION_GATE.state().name());
        Throwable failure = INITIALIZATION_GATE.lastFailure();
        if (failure != null) {
            out.putString("runtimeInitializationFailure",
                    failure.getClass().getName() + ":" + String.valueOf(failure.getMessage()));
        }
    }

    static void shutdown(String sessionId, long generation) {
        Session session;
        synchronized (GuestRuntimeEnvironment.class) {
            if (INITIALIZATION_GATE.initializing()) {
                throw new IllegalStateException("GUEST_PREPARATION_IN_PROGRESS");
            }
            session = require(sessionId, generation);
            // Invalidate the lease before cleanup.  Cleanup can synchronously call a framework
            // or Broker route which re-enters this class; never hold the class monitor while
            // waiting for Guest main-thread lifecycle work to finish.
            current = null;
            INITIALIZATION_GATE.reset();
        }
        session.shutdown();
    }

    /**
     * Clears the process-local Guest lease when Android tears down its concrete Guest service.
     * A service can be stopped after its Binder binding is released without receiving the typed
     * shutdown call, so service destruction must not leave a prior session resident in the slot.
     */
    static void shutdownIfCurrent() {
        Session session;
        synchronized (GuestRuntimeEnvironment.class) {
            // A concurrent prepare owns the staged cleanup path in prepare().  Android is
            // already tearing down this process; do not publish a new current Session from
            // that path.
            if (INITIALIZATION_GATE.initializing()) return;
            session = current;
            if (session == null) return;
            current = null;
            INITIALIZATION_GATE.reset();
        }
        session.shutdown();
    }

    public static synchronized void updatePermissionState(String sessionId, long generation,
                                                          VirtualPackageStateSnapshot packageState) {
        Session session = require(sessionId, generation);
        session.updatePermissionState(packageState);
    }

    private static VirtualPermissionPolicy permissionPolicy(VirtualPackageStateSnapshot state) {
        Map<String, String> decisions = new LinkedHashMap<>();
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        java.util.Set<String> effective = new java.util.LinkedHashSet<>();
        for (VirtualPermissionSnapshot permission : state.permissions()) {
            declared.add(permission.name());
            decisions.put(permission.name(), permission.decision());
            if (permission.effectiveGranted()) effective.add(permission.name());
        }
        return new VirtualPermissionPolicy(declared, decisions, effective);
    }

    private static SandboxAppOpsPolicy appOpsPolicy(VirtualPackageStateSnapshot state) {
        Map<String, String> modes = new LinkedHashMap<>();
        for (PackageAppOpSnapshot appOp : state.appOps()) {
            modes.put(appOp.opName(), appOp.mode());
        }
        return new SandboxAppOpsPolicy(modes);
    }

    private static Application instantiateApplication(GuestPackageSpec spec, ClassLoader loader,
                                                      String appComponentFactory)
            throws Exception {
        String className = spec.applicationClass == null || spec.applicationClass.trim().isEmpty()
                ? Application.class.getName() : spec.applicationClass;
        Class<?> type = loader.loadClass(className);
        if (!Application.class.isAssignableFrom(type)) throw new IllegalArgumentException("Application class has wrong type: " + className);
        Application application = GuestComponentFactory.instantiateApplication(loader,
                appComponentFactory, className);
        android.util.Log.i("CS_GUEST_FACTORY", "application factory="
                + String.valueOf(appComponentFactory) + " class=" + className);
        return application;
    }

    /**
     * Invokes the closest guest override instead of Application.attach().  The latter unwraps
     * ContextWrapper into ContextImpl; GuestContext intentionally refuses that unwrap so the
     * host Context cannot be recovered through getBaseContext().  Walking the guest hierarchy
     * also makes TinkerApplication's attachBaseContext/onBaseContextAttached path explicit.
     */
    private static void invokeNearestAttachBaseContext(Application application, Context context)
            throws Exception {
        Method attach = null;
        for (Class<?> type = application.getClass(); type != null; type = type.getSuperclass()) {
            try {
                attach = type.getDeclaredMethod("attachBaseContext", Context.class);
                break;
            } catch (NoSuchMethodException ignored) { }
        }
        if (attach == null) throw new NoSuchMethodException("attachBaseContext");
        attach.setAccessible(true);
        attach.invoke(application, context);
    }

    /** Projects only virtual network data into native policy; no host resolver identity is read. */
    private static NativeNetworkIdentity nativeNetworkIdentity(
            String packageName, int virtualUserId, VirtualNetworkServiceProfileSnapshot profile) {
        if (profile == null) throw new IllegalArgumentException("network profile is required");
        NativeNetworkIdentity fallback = NativeNetworkIdentity.isolated(packageName, virtualUserId);
        VirtualNetworkSnapshot network = profile.connectivity().defaultNetwork();
        if (network == null) return fallback;
        String ipv4 = address(network.addresses(), false, fallback.ipv4Address());
        String ipv6 = address(network.addresses(), true, fallback.ipv6Address());
        String[] dns = profile.dns().servers().isEmpty()
                ? network.dnsServers().toArray(new String[0])
                : profile.dns().servers().toArray(new String[0]);
        if (dns.length == 0 && !profile.vpn().dnsServers().isEmpty()) {
            dns = profile.vpn().dnsServers().toArray(new String[0]);
        }
        String proxyHost = "";
        int proxyPort = 0;
        if (VirtualLocationProfileSnapshot.MODE_STATIC.equals(profile.proxy().mode())
                && !profile.proxy().host().isEmpty()) {
            proxyHost = profile.proxy().host();
            proxyPort = profile.proxy().port();
        }
        String privateDns = VirtualDnsProfileSnapshot.PRIVATE_DNS_HOSTNAME.equals(
                profile.dns().privateDnsMode()) ? profile.dns().privateDnsHostname() : "";
        boolean vpnActive = VirtualVpnProfileSnapshot.CONNECTED.equals(profile.vpn().state());
        return new NativeNetworkIdentity(
                fallback.hostname(), nonEmpty(network.interfaceName(), fallback.interfaceName()),
                ipv4, ipv6, proxyHost, proxyPort, true,
                network.networkId() > 0 ? network.networkId() : fallback.networkId(),
                transport(network.transport(), vpnActive), vpnActive, network.metered(),
                network.validated(), network.mtu() >= 576 ? network.mtu() : fallback.mtu(),
                privateDns, dns);
    }

    private static void configureCamera1NativeProfile(File guestFilesRoot, GuestPackageSpec spec,
                                                       Context host,
                                                       VirtualCameraProfileSnapshot profile)
            throws Exception {
        boolean virtualCamera = !VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode());
        boolean allowOpen = profile.cameraAvailable() && profile.allowOpen();
        VirtualCameraSourceSnapshot source = profile.source();
        boolean sourceConfigured = source != null && source.isConfigured();
        boolean replacePreview = virtualCamera && sourceConfigured;
        boolean replaceCapture = virtualCamera && sourceConfigured && profile.substituteCaptureResult();
        if (!NativePolicy.configureCamera1Identity(spec.packageName, spec.virtualUid,
                host.getPackageName(), Process.myUid(), virtualCamera, allowOpen,
                replacePreview, replaceCapture)) {
            throw new IllegalStateException("NATIVE_CAMERA1_IDENTITY_CONFIG_FAILED:"
                    + NativePolicy.camera1Status());
        }
        if (!sourceConfigured) return;
        int frameCount = VirtualCameraSourceSnapshot.VIDEO.equals(source.kind()) ? 4 : 1;
        byte[][] previewFrames = new byte[frameCount][];
        byte[][] captureFrames = new byte[frameCount][];
        for (int index = 0; index < frameCount; index++) {
            long frameTimeMs = VirtualCameraSourceSnapshot.VIDEO.equals(source.kind())
                    ? (source.durationMs() > 0L
                    ? Math.min(source.durationMs() - 1L, index * 33L) : index * 33L) : 0L;
            previewFrames[index] = VirtualCameraCaptureEngine.read(guestFilesRoot, source,
                    frameTimeMs, true);
            captureFrames[index] = VirtualCameraCaptureEngine.read(guestFilesRoot, source,
                    frameTimeMs, false);
        }
        if (!NativePolicy.configureCamera1Frames(source.kind(), source.sha256(),
                source.width(), source.height(), previewFrames, captureFrames)) {
            throw new IllegalStateException("NATIVE_CAMERA1_SOURCE_CONFIG_FAILED:"
                    + NativePolicy.camera1Status());
        }
    }

    private static String address(java.util.List<String> values, boolean ipv6, String fallback) {
        if (values != null) for (String value : values) {
            String candidate = value == null ? "" : value;
            int slash = candidate.indexOf('/');
            if (slash >= 0) candidate = candidate.substring(0, slash);
            if ((candidate.indexOf(':') >= 0) == ipv6) return candidate;
        }
        return fallback;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String transport(String value, boolean vpnActive) {
        if (vpnActive) return NativeNetworkIdentity.TRANSPORT_VPN;
        return switch (value == null ? "" : value.toUpperCase(java.util.Locale.ROOT)) {
            case VirtualNetworkSnapshot.CELLULAR -> NativeNetworkIdentity.TRANSPORT_CELLULAR;
            case VirtualNetworkSnapshot.ETHERNET -> NativeNetworkIdentity.TRANSPORT_ETHERNET;
            default -> NativeNetworkIdentity.TRANSPORT_WIFI;
        };
    }

    private static String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }

    private static String isolatedNativeLibraryPath(GuestPackageSpec spec,
                                                    ParcelFileDescriptor materializedApk,
                                                    ParcelFileDescriptor nativeArchive) {
        // DexPathList only accepts directory/zip path elements for native lookup. A directory
        // capability cannot be traversed through /proc/self/fd on the isolated_app SELinux
        // domain, so passing its fd as a directory never reaches findLibrary(). Android's
        // NativeLoader already supports an uncompressed APK entry (apk!/lib/<abi>); the base
        // APK descriptor is a direct read capability and keeps loading inside the platform
        // loader instead of copying a .so into a host-visible staging path.
        ParcelFileDescriptor apk = materializedApk != null ? materializedApk : spec.apkDescriptor;
        if (nativeArchive != null && nativeArchive.getFd() >= 0
                && spec.nativeAbi != null && !spec.nativeAbi.trim().isEmpty()) {
            return "/proc/self/fd/" + nativeArchive.getFd() + "!/lib/"
                    + spec.nativeAbi.trim();
        }
        if (apk != null && apk.getFd() >= 0
                && spec.nativeAbi != null && !spec.nativeAbi.trim().isEmpty()) {
            return "/proc/self/fd/" + apk.getFd() + "!/lib/"
                    + spec.nativeAbi.trim();
        }
        if (spec.nativeLibraryDescriptor != null && spec.nativeLibraryDescriptor.getFd() >= 0) {
            return "/proc/self/fd/" + spec.nativeLibraryDescriptor.getFd();
        }
        return emptyToNull(spec.nativeLibraryDir);
    }

    private static ParcelFileDescriptor createNativeLibraryArchive(GuestPackageSpec spec)
            throws Exception {
        if (spec.nativeLibraryDescriptors.isEmpty()) return null;
        if (spec.nativeAbi == null || spec.nativeAbi.trim().isEmpty()) {
            throw new IllegalStateException("ISOLATED_NATIVE_ABI_MISSING");
        }
        ParcelFileDescriptor archive = NativePolicy.createProcessLocalFile("cas-native-libs.apk");
        long total = 0L;
        try {
            ParcelFileDescriptor outputDescriptor = archive.dup();
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor);
                 CountingOutputStream counted = new CountingOutputStream(output);
                 ZipOutputStream zip = new ZipOutputStream(counted)) {
                for (int index = 0; index < spec.nativeLibraryDescriptors.size(); index++) {
                    ParcelFileDescriptor source = spec.nativeLibraryDescriptors.get(index);
                    String entryName = validateNativeLibraryEntry(
                            spec.nativeLibraryEntryNames.get(index));
                    long size = source.getStatSize();
                    if (size <= 0L || size > 256L * 1024L * 1024L
                            || total > 512L * 1024L * 1024L - size) {
                        throw new IllegalStateException("ISOLATED_NATIVE_LIBRARY_SIZE_INVALID:" + entryName);
                    }
                    total += size;
                    CRC32 checksum = new CRC32();
                    copyNativeLibrary(source, checksum, null);
                    ZipEntry entry = new ZipEntry("lib/" + spec.nativeAbi.trim() + "/" + entryName);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(size);
                    entry.setCompressedSize(size);
                    entry.setCrc(checksum.getValue());
                    String archiveEntryName = "lib/" + spec.nativeAbi.trim() + "/" + entryName;
                    byte[] alignment = pageAlignmentExtra(counted.count(), archiveEntryName);
                    if (alignment.length > 0) entry.setExtra(alignment);
                    zip.putNextEntry(entry);
                    copyNativeLibrary(source, null, zip);
                    zip.closeEntry();
                }
                zip.finish();
            }
            try (ZipFile verification = new ZipFile("/proc/self/fd/" + archive.getFd())) {
                for (String entryName : spec.nativeLibraryEntryNames) {
                    ZipEntry entry = verification.getEntry("lib/" + spec.nativeAbi.trim()
                            + "/" + entryName);
                    if (entry == null || entry.getMethod() != ZipEntry.STORED) {
                        throw new IllegalStateException("ISOLATED_NATIVE_LIBRARY_ARCHIVE_ENTRY_INVALID:"
                                + entryName);
                    }
                }
            }
            android.util.Log.i("CS_NATIVE_BIND", "FD_NATIVE_ARCHIVE_VERIFIED fd="
                    + archive.getFd() + " bytes=" + total);
            return archive;
        } catch (Throwable error) {
            try { archive.close(); } catch (Throwable ignored) { }
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("ISOLATED_NATIVE_LIBRARY_ARCHIVE_FAILED", error);
        }
    }

    private static void copyNativeLibrary(ParcelFileDescriptor source, CRC32 checksum,
                                          OutputStream output) throws Exception {
        ParcelFileDescriptor duplicate = source.dup();
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(duplicate)) {
            input.getChannel().position(0L);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (checksum != null) checksum.update(buffer, 0, read);
                if (output != null) output.write(buffer, 0, read);
            }
        }
    }

    private static String validateNativeLibraryEntry(String value) {
        if (value == null || value.trim().isEmpty() || !value.endsWith(".so")
                || value.contains("/") || value.contains("\\")
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("ISOLATED_NATIVE_LIBRARY_ENTRY_INVALID");
        }
        return value;
    }

    private static byte[] pageAlignmentExtra(long localHeaderOffset, String entryName) {
        int nameBytes = entryName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int padding = (int) ((4096L - ((localHeaderOffset + 30L + nameBytes) % 4096L)) % 4096L);
        if (padding == 0) return new byte[0];
        if (padding < 4) padding += 4096;
        byte[] extra = new byte[padding];
        // One private ZIP extra field. The payload is intentionally opaque padding; its only
        // purpose is to make the stored ELF data start on a linker page boundary.
        extra[0] = (byte) 0xCA;
        extra[1] = (byte) 0x53;
        int payload = padding - 4;
        extra[2] = (byte) (payload & 0xff);
        extra[3] = (byte) ((payload >>> 8) & 0xff);
        return extra;
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        long count() { return count; }

        @Override public void write(int value) throws java.io.IOException {
            delegate.write(value);
            count++;
        }

        @Override public void write(byte[] values, int offset, int length)
                throws java.io.IOException {
            delegate.write(values, offset, length);
            count += length;
        }

        @Override public void flush() throws java.io.IOException { delegate.flush(); }
        @Override public void close() throws java.io.IOException { delegate.close(); }
    }

    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    /**
     * Isolated processes cannot traverse another package's APK path.  Load the current APK and
     * every authority-resolved Java shared-library provider through the Broker's read-only FD
     * capability, then keep only direct DEX buffers in the process-local class loader.  This
     * mirrors Android's LoadedApk shared-library class path without copying a host pathname into
     * an isolated process or widening the native filesystem policy.
     */
    private static java.util.List<java.nio.ByteBuffer> loadIsolatedGuestDexBuffers(
            GuestPackageSpec spec) throws Exception {
        java.util.ArrayList<java.nio.ByteBuffer> buffers = new java.util.ArrayList<>(
                GuestDexBufferLoader.load(spec.apkDescriptor, spec.splitDescriptors));
        java.util.List<com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot>
                providers = GuestSharedLibraryPathResolver.resolvedJavaLibraryProjections(
                        spec.packageState, spec.packageUniverse);
        if (providers.isEmpty()) return java.util.List.copyOf(buffers);
        try (GuestMainThreadDispatcher resourceDispatcher = new GuestMainThreadDispatcher(
                GuestRuntimeEnvironment.class.getClassLoader())) {
            GuestRuntimeBrokerBridge bridge = new GuestRuntimeBrokerBridge(spec,
                    resourceDispatcher);
            for (com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot provider
                    : providers) {
                String packageName = provider.packageState().packageName();
                Bundle resources = bridge.openPackageResources(packageName);
                ParcelFileDescriptor base = resources.getParcelable(
                        RuntimeKeys.PACKAGE_RESOURCE_APK_FD);
                java.util.ArrayList<ParcelFileDescriptor> splits = resources
                        .getParcelableArrayList(RuntimeKeys.PACKAGE_RESOURCE_SPLIT_FDS);
                if (base == null || base.getFd() < 0) {
                    throw new IllegalStateException(
                            "SHARED_LIBRARY_PROVIDER_APK_CAPABILITY_MISSING:" + packageName);
                }
                if (splits == null) splits = new java.util.ArrayList<>();
                try {
                    buffers.addAll(GuestDexBufferLoader.load(base, splits));
                } finally {
                    try { base.close(); } catch (Throwable ignored) { }
                    for (ParcelFileDescriptor split : splits) {
                        if (split == null || split == base) continue;
                        try { split.close(); } catch (Throwable ignored) { }
                    }
                }
            }
        }
        android.util.Log.i("CS_GUEST_LOADER", "ISOLATED_SHARED_LIBRARY_DEX_READY providers="
                + providers.size() + " buffers=" + buffers.size());
        return java.util.List.copyOf(buffers);
    }

    private static boolean isTranslatedGuestAbi(String guestAbi) {
        if (guestAbi == null || guestAbi.trim().isEmpty()) return false;
        String normalizedGuest = guestAbi.trim().toLowerCase(java.util.Locale.ROOT);
        String actualHostAbi = hostAbi();
        return !actualHostAbi.isEmpty()
                && !normalizedGuest.equals(actualHostAbi.toLowerCase(java.util.Locale.ROOT));
    }

    private static String hostAbi() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("controlled_sandbox_native.so")) continue;
                int marker = line.lastIndexOf("/lib/");
                if (marker < 0) continue;
                int start = marker + "/lib/".length();
                int end = line.indexOf('/', start);
                if (end > start) return line.substring(start, end);
            }
        } catch (Throwable ignored) {
            // Fall back to the platform-reported ABI list on devices without readable maps.
        }
        String[] hostAbis = Build.SUPPORTED_ABIS;
        return hostAbis == null || hostAbis.length == 0 || hostAbis[0] == null ? "" : hostAbis[0];
    }

    /**
     * Valid APKs may use an applicationId that differs from their Java component namespace.
     * Derive compatibility exemptions only from this APK's manifest declarations; never add a
     * global package allowlist. GuestClassLoader still applies Host/internal deny-first rules.
     */
    private static java.util.List<String> declaredGuestClasses(GuestPackageSpec spec) {
        java.util.ArrayList<String> classes = new java.util.ArrayList<>();
        appendDeclaredGuestClasses(classes, spec.packageState);
        for (com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot provider
                : GuestSharedLibraryPathResolver.resolvedJavaLibraryProjections(
                        spec.packageState, spec.packageUniverse)) {
            appendDeclaredGuestClasses(classes, provider.packageState());
            // A shared-library provider may expose implementation classes that are not manifest
            // components. Add a package marker so detection filtering does not hide its package
            // namespace while the provider DEX is part of the same LoadedApk class path.
            classes.add(provider.packageState().packageName() + ".__cas_shared_library__");
        }
        return java.util.List.copyOf(classes);
    }

    private static void appendDeclaredGuestClasses(
            java.util.List<String> classes,
            com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot state) {
        if (state.applicationClass() != null && !state.applicationClass().trim().isEmpty()) {
            classes.add(state.applicationClass());
        }
        for (com.warden.controlledsandbox.contract.VirtualComponentSnapshot component
                : state.components()) {
            if (component != null && component.className() != null
                    && !component.className().trim().isEmpty()) {
                classes.add(component.className());
            }
        }
    }

    private static void ensureDirectory(File value) {
        if (!value.isDirectory() && !value.mkdirs() && !value.isDirectory()) throw new IllegalStateException("Cannot create " + value);
    }
    private static String stackSummary(Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append(error).append('\n');
        for (int i = 0; i < Math.min(error.getStackTrace().length, 24); i++) out.append("  at ").append(error.getStackTrace()[i]).append('\n');
        return out.toString();
    }

    private static final class NativeBootstrap {
        final String nativeAbi;
        final String packagedNativeLibraryDir;
        final File guestDataRoot;
        final String runtimeNativeLibraryDir;
        final String nativeLibrarySearchPath;
        final String guestDexPath;
        final String coreDexMode;
        final String nativePolicyLibraryRoot;
        final boolean nativePolicyConfigured;
        final boolean systemIoHooksInstalled;
        final boolean nativeCrashRecorderInstalled;

        NativeBootstrap(String nativeAbi, String packagedNativeLibraryDir, File guestDataRoot,
                        String runtimeNativeLibraryDir, String nativeLibrarySearchPath,
                        String guestDexPath, String coreDexMode, String nativePolicyLibraryRoot,
                        boolean nativePolicyConfigured, boolean systemIoHooksInstalled,
                        boolean nativeCrashRecorderInstalled) {
            this.nativeAbi = nativeAbi;
            this.packagedNativeLibraryDir = packagedNativeLibraryDir;
            this.guestDataRoot = guestDataRoot;
            this.runtimeNativeLibraryDir = runtimeNativeLibraryDir;
            this.nativeLibrarySearchPath = nativeLibrarySearchPath;
            this.guestDexPath = guestDexPath;
            this.coreDexMode = coreDexMode;
            this.nativePolicyLibraryRoot = nativePolicyLibraryRoot;
            this.nativePolicyConfigured = nativePolicyConfigured;
            this.systemIoHooksInstalled = systemIoHooksInstalled;
            this.nativeCrashRecorderInstalled = nativeCrashRecorderInstalled;
        }
    }

    public static final class Session {
        final GuestPackageSpec spec;
        final GuestClassLoader classLoader;
        final GuestContext context;
        final GuestMainThreadDispatcher mainThread;
        volatile Application application;
        final GuestResourceLoader.LoadedResources resources;
        final FrameworkHooks frameworkHooks;
        final GuestFrameworkCallRouter frameworkCallRouter;
        final OrderedReceiverFinishInterceptor orderedReceiverFinishInterceptor;
        final VirtualPackageMetadata packageMetadata;
        final VirtualPermissionPolicy permissionPolicy;
        final SandboxAppOpsPolicy appOpsPolicy;
        final CapabilityAccessPolicy capabilityPolicy;
        final GuestCapabilityAuditLog capabilityAudit;
        final CapabilityLeaseRegistry capabilityLeases;
        final VirtualSystemServiceState virtualServices;
        /** Immutable audit of which prepare operations are launch-critical versus first-use. */
        final GuestPreparePlan preparePlan;
        volatile VirtualPackageStateSnapshot packageState;
        final boolean nativePolicyConfigured;
        final boolean nativeHooksInstalled;
        final boolean nativeLoadRedirectInstalled;
        final String nativeBoundaryMode;
        volatile boolean camera1AdapterInstalled;
        final boolean nativeCrashRecorderInstalled;
        final WebViewProfileManager.Profile webViewProfile;
        final GuestProcessIdentityBridge processIdentity;
        final ParcelFileDescriptor loaderApkDescriptor;
        final ParcelFileDescriptor loaderNativeArchiveDescriptor;
        GuestActivityThreadInstrumentation activityThreadInstrumentation;
        GuestActivityThreadServiceBridge serviceFrameworkBridge;
        GuestLoadedApkBridge loadedApkBridge;
        GuestComponentRuntime components;
        GuestJobServiceBridge jobServices;

        Session(GuestPackageSpec spec, GuestClassLoader classLoader, GuestContext context,
                Application application, GuestResourceLoader.LoadedResources resources, FrameworkHooks frameworkHooks,
                GuestFrameworkCallRouter frameworkCallRouter,
                VirtualPackageMetadata packageMetadata, VirtualPermissionPolicy permissionPolicy,
                SandboxAppOpsPolicy appOpsPolicy, CapabilityAccessPolicy capabilityPolicy,
                GuestCapabilityAuditLog capabilityAudit, CapabilityLeaseRegistry capabilityLeases,
                VirtualSystemServiceState virtualServices,
                boolean nativePolicyConfigured, boolean nativeHooksInstalled,
                boolean nativeLoadRedirectInstalled, String nativeBoundaryMode,
                boolean camera1AdapterInstalled, boolean nativeCrashRecorderInstalled,
                WebViewProfileManager.Profile webViewProfile,
                GuestProcessIdentityBridge processIdentity,
                ParcelFileDescriptor loaderApkDescriptor,
                ParcelFileDescriptor loaderNativeArchiveDescriptor) {
            this.spec = spec;
            this.classLoader = classLoader;
            this.context = context;
            this.mainThread = context.mainThread;
            this.application = application;
            this.resources = resources;
            this.frameworkHooks = frameworkHooks;
            this.frameworkCallRouter = java.util.Objects.requireNonNull(
                    frameworkCallRouter, "frameworkCallRouter");
            this.orderedReceiverFinishInterceptor = frameworkCallRouter.orderedReceivers();
            this.packageMetadata = java.util.Objects.requireNonNull(packageMetadata, "packageMetadata");
            this.permissionPolicy = java.util.Objects.requireNonNull(permissionPolicy, "permissionPolicy");
            this.appOpsPolicy = java.util.Objects.requireNonNull(appOpsPolicy, "appOpsPolicy");
            this.capabilityPolicy = java.util.Objects.requireNonNull(capabilityPolicy, "capabilityPolicy");
            this.capabilityAudit = java.util.Objects.requireNonNull(capabilityAudit, "capabilityAudit");
            this.capabilityLeases = java.util.Objects.requireNonNull(capabilityLeases, "capabilityLeases");
            this.virtualServices = java.util.Objects.requireNonNull(virtualServices, "virtualServices");
            this.preparePlan = GuestPreparePlan.forSpec(spec, nativePolicyConfigured);
            this.packageState = spec.packageState;
            this.nativePolicyConfigured = nativePolicyConfigured;
            this.nativeHooksInstalled = nativeHooksInstalled;
            this.nativeLoadRedirectInstalled = nativeLoadRedirectInstalled;
            this.nativeBoundaryMode = nativeBoundaryMode == null ? "unknown" : nativeBoundaryMode;
            this.camera1AdapterInstalled = camera1AdapterInstalled;
            this.nativeCrashRecorderInstalled = nativeCrashRecorderInstalled;
            this.webViewProfile = webViewProfile;
            this.processIdentity = java.util.Objects.requireNonNull(processIdentity, "processIdentity");
            this.loaderApkDescriptor = loaderApkDescriptor;
            this.loaderNativeArchiveDescriptor = loaderNativeArchiveDescriptor;
        }

        public GuestPackageSpec spec() { return spec; }
        public GuestClassLoader classLoader() { return classLoader; }
        public GuestContext context() { return context; }
        public Application application() { return application; }
        public VirtualPackageMetadata packageMetadata() { return packageMetadata; }

        void bindApplication(Application application) {
            if (application == null) throw new IllegalArgumentException("application is required");
            if (this.application != null && this.application != application) {
                throw new IllegalStateException("GUEST_SESSION_APPLICATION_REBOUND");
            }
            this.application = application;
        }
        public String sessionId() { return spec.sessionId; }
        public long generation() { return spec.generation; }
        public String packageName() { return spec.packageName; }
        public int virtualUserId() { return spec.virtualUserId; }
        public int processSlot() { return spec.processSlot; }
        public Object loadedApkProjection() {
            return loadedApkBridge == null ? null : loadedApkBridge.loadedApk();
        }

        Bundle readinessStatus(String status, long started) {
            Bundle out = new Bundle();
            String effectiveStatus = status;
            if (frameworkHooks.report().readiness()
                    == com.warden.controlledsandbox.framework.core.FrameworkHookReport.Readiness.DEGRADED) {
                if ("READY".equals(status)) effectiveStatus = "DEGRADED";
                else if ("ALREADY_READY".equals(status)) effectiveStatus = "ALREADY_DEGRADED";
            }
            out.putString(RuntimeKeys.STATUS, effectiveStatus);
            out.putString(RuntimeKeys.SESSION_ID, spec.sessionId);
            out.putLong(RuntimeKeys.GENERATION, spec.generation);
            out.putInt(RuntimeKeys.PROCESS_SLOT, spec.processSlot);
            out.putString(RuntimeKeys.PACKAGE_NAME, spec.packageName);
            out.putInt(RuntimeKeys.VIRTUAL_USER_ID, spec.virtualUserId);
            out.putInt(RuntimeKeys.VIRTUAL_UID, spec.virtualUid);
            out.putString(RuntimeKeys.PROCESS_NAME, spec.processName);
            out.putString("frameworkReadiness", frameworkHooks.report().readiness().name());
            out.putInt("pid", Process.myPid());
            out.putBoolean("frameworkActivityTransportInstalled", activityThreadInstrumentation != null);
            out.putBoolean("frameworkServiceTransportInstalled", serviceFrameworkBridge != null);
            out.putBoolean("frameworkLoadedApkInstalled", loadedApkBridge != null
                    && loadedApkBridge.loadedApk() != null);
            out.putBoolean("frameworkGuestApplicationBound", application != null
                    && context.getApplicationInfo() != null
                    && spec.packageName.equals(context.getApplicationInfo().packageName));
            out.putBoolean("frameworkComponentLifecycleReady", activityThreadInstrumentation != null
                    && serviceFrameworkBridge != null && loadedApkBridge != null);
            out.putAll(preparePlan.toBundle());
            out.putLong("durationMs", Math.max(0,
                    android.os.SystemClock.elapsedRealtime() - started));
            return out;
        }

        public void bindActivityTaskHost(IBinder frameworkToken, String activityToken, int taskId,
                                         Runnable moveToFront, BooleanSupplier moveToBack,
                                         Runnable finishAffinity,
                                         Runnable finishAndRemoveTask) {
            frameworkCallRouter.activityTasks().bindHostActivity(frameworkToken, activityToken, taskId,
                    moveToFront, moveToBack, finishAffinity, finishAndRemoveTask);
        }

        public void updateActivityTaskHost(IBinder frameworkToken, String activityToken) {
            frameworkCallRouter.activityTasks().updateHostActivity(frameworkToken, activityToken);
        }

        public boolean consumeActivityTaskFinalized(IBinder frameworkToken) {
            return frameworkCallRouter.activityTasks().consumeBrokerFinalized(frameworkToken);
        }

        public void unbindActivityTaskHost(IBinder frameworkToken) {
            frameworkCallRouter.activityTasks().unbindHostActivity(frameworkToken);
        }

        synchronized void updatePermissionState(VirtualPackageStateSnapshot updated) {
            if (updated == null || !spec.packageName.equals(updated.packageName())
                    || spec.virtualUserId != updated.virtualUserId()) {
                throw new SecurityException("RUNTIME_PERMISSION_STATE_IDENTITY_MISMATCH");
            }
            if (!spec.apkSha256.equals(updated.apkSha256())) {
                throw new SecurityException("RUNTIME_PERMISSION_STATE_REVISION_MISMATCH");
            }
            CapabilityProxyReadiness.require(frameworkHooks.report().installedServices(), updated.permissions());
            VirtualPermissionPolicy nextPermissions = permissionPolicy(updated);
            SandboxAppOpsPolicy nextAppOps = appOpsPolicy(updated);
            permissionPolicy.replace(nextPermissions.declaredPermissions(), nextPermissions.decisions(),
                    nextPermissions.effectiveGrants());
            appOpsPolicy.replace(nextAppOps.modes());
            context.updatePermissionState(updated.permissions());
            capabilityLeases.revokeDenied(capabilityPolicy, capabilityAudit);
            if (nativePolicyConfigured) {
                NativePolicy.setAudioCaptureAllowed(spec.generation,
                        capabilityPolicy.allowed(CapabilityAccessPolicy.MICROPHONE));
            }
            packageState = updated;
        }

        private boolean onVirtualJobStart(int guestJobId, Object jobPayload,
                com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobParametersRecord parameters,
                com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobExecution execution) {
            if (jobServices == null) return false;
            return jobServices.start(guestJobId, jobPayload, parameters, execution);
        }

        private boolean onVirtualJobStop(int guestJobId,
                com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobParametersRecord parameters) {
            return jobServices == null || jobServices.stop(guestJobId, parameters);
        }

        Bundle status(String status, long started) {
            // VA/NBB readiness checks return process/application state, not the complete package
            // projection.  The package universe is immutable preparation state and is already
            // retained by the Broker; echoing it through this status Binder call turns every hot
            // Activity launch into a large synchronous transaction before ActivityStarter.
            Bundle out = spec.toRuntimeRequestBundle();
            String effectiveStatus = status;
            if (frameworkHooks.report().readiness() == com.warden.controlledsandbox.framework.core.FrameworkHookReport.Readiness.DEGRADED) {
                if ("READY".equals(status)) effectiveStatus = "DEGRADED";
                else if ("ALREADY_READY".equals(status)) effectiveStatus = "ALREADY_DEGRADED";
            }
            out.putString(RuntimeKeys.STATUS, effectiveStatus);
            out.putString("frameworkReadiness", frameworkHooks.report().readiness().name());
            out.putInt("pid", Process.myPid());
            // PROCESS_NAME is the logical declared owner used by the Broker session key. Keep
            // the actual Android hosting process in a separate diagnostic-only field so the
            // guest status payload cannot rewrite that identity across Binder.
            out.putString("androidProcessName", Build.VERSION.SDK_INT >= 28
                    ? Application.getProcessName() : "pid-" + Process.myPid());
            out.putString("classLoader", classLoader.getClass().getName());
            out.putString("application", application.getClass().getName());
            out.putString("dataDir", context.getApplicationInfo().dataDir);
            out.putInt(RuntimeKeys.VIRTUAL_UID, spec.virtualUid);
            out.putBoolean("packageManagerHook", frameworkHooks.report().packageManagerInstalled());
            java.util.ArrayList<String> installedHooks = new java.util.ArrayList<>();
            java.util.ArrayList<String> failedHooks = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Boolean> item : frameworkHooks.report().installedServices().entrySet()) {
                if (Boolean.TRUE.equals(item.getValue())) installedHooks.add(item.getKey());
            }
            for (java.util.Map.Entry<String, String> item : frameworkHooks.report().failures().entrySet()) {
                failedHooks.add(item.getKey() + ":" + item.getValue());
            }
            out.putStringArrayList("frameworkHooksInstalled", installedHooks);
            out.putStringArrayList("frameworkHooksFailed", failedHooks);
            out.putBoolean("frameworkActivityTransportInstalled", activityThreadInstrumentation != null);
            out.putBoolean("frameworkServiceTransportInstalled", serviceFrameworkBridge != null);
            out.putBoolean("frameworkLoadedApkInstalled", loadedApkBridge != null
                    && loadedApkBridge.loadedApk() != null);
            out.putBoolean("frameworkGuestApplicationBound", application != null
                    && context.getApplicationInfo() != null
                    && spec.packageName.equals(context.getApplicationInfo().packageName));
            out.putBoolean("frameworkComponentLifecycleReady", activityThreadInstrumentation != null
                    && serviceFrameworkBridge != null && loadedApkBridge != null);
            out.putAll(preparePlan.toBundle());
            out.putInt("virtualComponentCount", packageMetadata.components().size());
            java.util.ArrayList<String> deviceServiceBindings = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> item : frameworkHooks.report().bindingDetails().entrySet()) {
                deviceServiceBindings.add(item.getKey() + "=" + item.getValue());
            }
            out.putStringArrayList("deviceServiceBindings", deviceServiceBindings);
            out.putInt("capabilityAuditCount", capabilityAudit.size());
            out.putInt("capabilityDeniedCount", capabilityAudit.deniedCount());
            out.putInt("capabilityActiveLeases", capabilityLeases.activeCount());
            out.putStringArrayList("capabilityAudit", capabilityAudit.compactSnapshot());
            Bundle isolatedStatus = isolatedProcessStatus(context);
            out.putAll(isolatedStatus);
            out.putBoolean("nativePolicyAvailable", NativePolicy.available());
            out.putBoolean("nativePolicyConfigured", nativePolicyConfigured);
            out.putBoolean("nativeHooksInstalled", nativeHooksInstalled);
            out.putBoolean("nativeLoadRedirectInstalled", nativeLoadRedirectInstalled);
            out.putBoolean("nativeIoVirtualizationInstalled", nativeHooksInstalled);
            out.putString("nativeBoundaryMode", nativeBoundaryMode);
            out.putBoolean("nativeCamera1AdapterInstalled", camera1AdapterInstalled);
            out.putString("nativeHookStatus", NativePolicy.hookStatus());
            out.putString("nativeCamera1Status", NativePolicy.camera1Status());
            out.putString("nativeNetworkStatus", NativePolicy.networkStatus());
            out.putString("nativeLoaderStatus", NativePolicy.loaderStatus());
            out.putString("nativeAudioCaptureStatus", NativePolicy.audioCaptureStatus());
            out.putBoolean("nativeCrashRecorderInstalled", nativeCrashRecorderInstalled);
            out.putString("nativeCrashStatus", NativePolicy.crashStatus());
            out.putAll(RuntimeDiagnostics.snapshot());
            // RuntimeDiagnostics also exposes a STATUS field; keep the guest-operation
            // status authoritative for the broker transport after merging diagnostics.
            out.putString(RuntimeKeys.STATUS, effectiveStatus);
            out.putAll(webViewProfile.toBundle());
            out.putString("nativePolicyLoadError", NativePolicy.loadError());
            out.putString("frameworkHookError", frameworkHooks.report().errorType() + ":" + frameworkHooks.report().errorMessage());
            out.putLong("durationMs", Math.max(0, android.os.SystemClock.elapsedRealtime() - started));
            return out;
    }

    private static Bundle isolatedProcessStatus(GuestContext guestContext) {
        Bundle out = new Bundle();
        int verified = 0;
        PackageManager packageManager = guestContext.hostServiceContext().getPackageManager();
        String packageName = guestContext.hostServiceContext().getPackageName();
        for (int slot = 0; slot < ProcessSlotContract.ISOLATED_SLOT_COUNT; slot++) {
            String worker = ProcessSlotContract.isolatedServiceClassName(slot);
            try {
                ServiceInfo info = packageManager.getServiceInfo(
                        new ComponentName(packageName, worker), 0);
                if (info != null && (info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
                    verified++;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // A merged manifest without every predeclared slot is not a usable isolated
                // process pool; the coordinator will fail closed when that slot is requested.
            }
        }
        boolean supported = verified == ProcessSlotContract.ISOLATED_SLOT_COUNT;
        out.putBoolean("isolatedProcessSupported", supported);
        out.putInt("isolatedProcessManifestSlots", verified);
        out.putInt("isolatedProcessManifestSlotCapacity",
                ProcessSlotContract.ISOLATED_SLOT_COUNT);
        out.putBoolean("isolatedProcessActive", Process.myUid()
                != guestContext.hostServiceContext().getApplicationInfo().uid);
        out.putString("isolatedProcessPolicy", supported
                ? "DEDICATED_PLATFORM_ISOLATED_SERVICE_WITH_PID_UID_EVIDENCE"
                : "MANIFEST_ISOLATED_SERVICE_SLOTS_UNAVAILABLE");
        return out;
    }

    void shutdown() {
            if (jobServices != null) jobServices.close();
            // Publish the Guest-side component fence before asking ActivityThread to destroy
            // framework-owned Activities. finishAndRemoveTask() schedules destruction and
            // onDestroy can arrive after it returns; those callbacks must not synchronously
            // re-enter the Broker while the process-service lease is being retired.
            context.beginComponentTeardown();
            // Finish framework-owned activities while ActivityThread still owns the Guest
            // instrumentation.  The broker clears its task ledger as part of the same stop
            // transaction, but Android must also observe the concrete host StubActivity task
            // finishing or it may recreate the slot process after clear/delete.
            if (activityThreadInstrumentation != null) {
                mainThread.run(activityThreadInstrumentation::finishAllActivities);
            }
            // Complete the resource teardown after ActivityThread has received the finish request.
            // Chromium may issue one final unbind asynchronously; the component fence above
            // makes that callback local and idempotent.
            context.closeComponentServices();
            if (components != null) components.shutdown();
            capabilityLeases.close(capabilityAudit);
            webViewProfile.renderers.close();
            webViewProfile.storage.close();
            context.closeWebViewProviderServices();
            if (activityThreadInstrumentation != null) activityThreadInstrumentation.close();
            if (serviceFrameworkBridge != null) serviceFrameworkBridge.close();
            if (loadedApkBridge != null) loadedApkBridge.close();
            // AppComponentFactory is LoadedApk/ClassLoader scoped.  Retire every loader used
            // during this generation so a later recovery/reinstall cannot reuse a factory that
            // still closes over the previous Guest dex namespace.
            GuestComponentFactory.clearCacheForLoader(context.getClassLoader());
            GuestComponentFactory.clearCacheForLoader(classLoader);
            GuestComponentFactory.clearCacheForLoader(classLoader.definingLoader());
            resources.close();
            virtualServices.close();
            frameworkCallRouter.close();
            frameworkHooks.close();
            processIdentity.close();
            NativePolicy.resetAudioCapture();
            NativePolicy.resetCamera1();
            NativePolicy.resetHooks();
            NativePolicy.resetPolicy();
            NativePolicy.resetCrashRecorder();
            if (loaderApkDescriptor != null) {
                try { loaderApkDescriptor.close(); } catch (Throwable ignored) { }
            }
            if (loaderNativeArchiveDescriptor != null) {
                try { loaderNativeArchiveDescriptor.close(); } catch (Throwable ignored) { }
            }
            mainThread.close();
        }

        public String instanceId() { return "u" + spec.virtualUserId + ":" + spec.packageName; }
    }
}
