package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import com.warden.controlledsandbox.framework.core.FrameworkHooks;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;
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
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Process-local runtime. One Android guest process hosts exactly one generation at a time. */
public final class GuestRuntimeEnvironment {
    private static Session current;
    private static boolean preparing;

    private GuestRuntimeEnvironment() { }

    static Bundle prepare(Context host, GuestPackageSpec spec) {
        if (Looper.myLooper() == Looper.getMainLooper()) return prepareOnCurrentThread(host, spec);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        if (!mainHandler.post(() -> {
            try {
                result.set(prepareOnCurrentThread(host, spec));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                complete.countDown();
            }
        })) {
            throw new IllegalStateException("GUEST_PREPARE_MAIN_HANDLER_REJECTED");
        }
        try {
            if (!complete.await(60L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("GUEST_PREPARE_MAIN_THREAD_TIMEOUT");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GUEST_PREPARE_MAIN_THREAD_INTERRUPTED", error);
        }
        Throwable error = failure.get();
        if (error != null) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error);
        }
        return result.get();
    }

    private static Bundle prepareOnCurrentThread(Context host, GuestPackageSpec spec) {
        synchronized (GuestRuntimeEnvironment.class) {
            if (preparing) throw new IllegalStateException("GUEST_PREPARATION_IN_PROGRESS");
            preparing = true;
        }
        long started = android.os.SystemClock.elapsedRealtime();
        Bundle result = new Bundle();
        FrameworkHooks stagedHooks = null;
        GuestFrameworkCallRouter stagedFrameworkCallRouter = null;
        Session stagedSession = null;
        GuestProcessIdentityBridge stagedProcessIdentity = null;
        try {
            RuntimeDiagnostics.install(host, "guest-slot-" + spec.processSlot);
            boolean nativeCrashRecorderInstalled = RuntimeDiagnostics.nativeCrashFile() != null
                    && NativePolicy.installCrashRecorder(RuntimeDiagnostics.nativeCrashFile().getAbsolutePath());
            com.warden.controlledsandbox.domain.session.PackageRevision verifiedRevision =
                    PackageRevisionSetVerifier.verify(spec.apkFile(), spec.baseApkSha256,
                            spec.splitArtifacts(), spec.apkVersionCode, spec.apkSha256);
            if (!verifiedRevision.canonical().equals(spec.packageRevision)) {
                throw new SecurityException("PACKAGE_REVISION_MISMATCH");
            }
            if (current != null) {
                if (current.spec.sessionId.equals(spec.sessionId)
                        && current.spec.generation == spec.generation
                        && current.spec.packageRevision.equals(spec.packageRevision)) {
                    Bundle alreadyReady = current.status("ALREADY_READY", started);
                    synchronized (GuestRuntimeEnvironment.class) { preparing = false; }
                    return alreadyReady;
                }
                if (spec.generation <= current.spec.generation) throw new IllegalStateException("STALE_GUEST_GENERATION");
                current.shutdown();
            }
            File optimized = new File(host.getCodeCacheDir(), "guest/" + safe(spec.packageName)
                    + "/" + safe(spec.packageRevision) + "/" + spec.generation);
            ensureDirectory(optimized);
            GuestClassLoader loader = new GuestClassLoader(spec.dexPath(), optimized.getAbsolutePath(),
                    emptyToNull(spec.nativeLibraryDir), GuestRuntimeEnvironment.class.getClassLoader(),
                    spec.packageName, declaredGuestClasses(spec));
            GuestResourceLoader.LoadedResources loadedResources = GuestResourceLoader.load(
                    host, spec.apkPath, spec.splitPathArray());
            PackageManager processPackageManager = host.getPackageManager();
            GuestContext guestContext = new GuestContext(host, spec, loader,
                    loadedResources.resources, loadedResources.assets, processPackageManager);
            IVirtualSystemServiceSession systemServiceSession = IVirtualSystemServiceSession.Stub.asInterface(
                    spec.virtualSystemServiceBinder);
            if (systemServiceSession == null) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_INVALID");
            VirtualNetworkServiceProfileSnapshot nativeNetworkProfile =
                    systemServiceSession.getNetworkServiceProfile();
            if (nativeNetworkProfile == null) throw new IllegalStateException("VIRTUAL_NETWORK_PROFILE_MISSING");
            String nativeAbi = spec.nativeAbi;
            int virtualPid = 20000 + (spec.virtualUserId * 100) + spec.processSlot;
            boolean nativePolicyConfigured = NativePolicy.configure(spec.sessionId, spec.generation,
                    spec.packageName, spec.processName, spec.virtualUserId, spec.virtualUid,
                    virtualPid, nativeAbi, spec.dataRoot, spec.apkPath,
                    spec.nativeLibraryDir, true, new String[0], new String[0], new String[0], new String[0],
                    new String[0], new String[0],
                    nativeNetworkIdentity(spec.packageName, spec.virtualUserId, nativeNetworkProfile));
            boolean requiresNativeHooks = spec.nativeLibraryDir != null && !spec.nativeLibraryDir.trim().isEmpty();
            if (requiresNativeHooks && !nativePolicyConfigured) {
                throw new IllegalStateException("NATIVE_FILE_POLICY_UNAVAILABLE");
            }
            boolean nativeHooksInstalled = requiresNativeHooks && NativePolicy.installHooks(spec.nativeLibraryDir);
            if (requiresNativeHooks && !nativeHooksInstalled) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_INSTALL_FAILED:" + NativePolicy.hookStatus());
            }
            if (Build.VERSION.SDK_INT >= 29 && !NativePolicy.installHiddenApiBridge()) {
                throw new IllegalStateException("HIDDEN_API_BRIDGE_UNAVAILABLE");
            }
            VirtualPackageMetadata packageMetadata = GuestPackageMetadataMapper.fromSnapshot(
                    spec.packageState, guestContext.getApplicationInfo(), loadedResources.manifestMetadata);
            VirtualSystemServiceState virtualServices = new VirtualSystemServiceState(
                    new RemoteVirtualSystemServiceAuthority(systemServiceSession, loader));
            loader.configureDetection(virtualServices.compatibilityProfile().detection());
            WebViewProfileManager.Profile webViewProfile = WebViewProfileManager.install(
                    spec, virtualServices.compatibilityProfile().webView());
            guestContext.configureWebViewProvider(
                    virtualServices.compatibilityProfile().webView().providerPackage());
            GuestFrameworkCallRouter frameworkCallRouter = new GuestFrameworkCallRouter(
                    guestContext, spec, virtualServices.pendingIntents(),
                    new GuestPendingIntentDispatcher(guestContext, spec));
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
            FrameworkHooks frameworkHooks = FrameworkHooks.install(guestContext, host,
                    processPackageManager,
                    new GuestIdentity(spec.packageName, spec.virtualUid, guestContext.getApplicationInfo(),
                            new HashSet<>(spec.permissions), host.getPackageName(), Process.myUid(),
                            packageMetadata, spec.processName, spec.virtualUserId, spec.generation,
                            permissionPolicy, appOpsPolicy, capabilityAudit, capabilityLeases, virtualServices,
                            spec.packageRevision),
                    frameworkCallRouter, nativeHooksInstalled);
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
                    virtualServices.networkServiceProfile(), nativeHooksInstalled);
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
            boolean camera1AdapterInstalled = nativePolicyConfigured
                    && NativePolicy.installCamera1Adapter();
            if (nativePolicyConfigured) {
                configureCamera1NativeProfile(guestContext.getFilesDir(), spec, host,
                        cameraProfile);
                android.util.Log.i("CS_CAMERA1_NATIVE", (camera1AdapterInstalled
                        ? "ADAPTER_READY" : "ADAPTER_DEFERRED_SYSTEM_LIBRARY_LOAD") + " status="
                        + NativePolicy.camera1Status());
            }
            PrivilegedServicesProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.privilegedServicesProfile());
            // Android creates Application instances and calls attachBaseContext/onCreate on the
            // process main thread.  Binder-driven preparation must preserve that contract because
            // real APK constructors commonly allocate a Handler/Looper during initialization.
            // Project the Guest process identity before attachBaseContext: Tinker-style
            // Application delegates cache ActivityThread's process name during that hook, and
            // observing the Host process there silently skips normal startup tasks.
            Application application = guestContext.mainThread.call(
                    () -> instantiateApplication(spec, loader));
            guestContext.application(application);
            stagedProcessIdentity = GuestProcessIdentityBridge.install(
                    application, guestContext.getApplicationInfo(), spec);
            guestContext.mainThread.run(() -> invokeNearestAttachBaseContext(application, guestContext));
            if (nativePolicyConfigured && !camera1AdapterInstalled) {
                camera1AdapterInstalled = NativePolicy.installCamera1Adapter();
                android.util.Log.i("CS_CAMERA1_NATIVE", "ADAPTER_RETRY_AFTER_ATTACH installed="
                        + camera1AdapterInstalled + " status=" + NativePolicy.camera1Status());
            }
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_CREATE:"
                        + NativePolicy.hookStatus());
            }
            Session session = new Session(spec, loader, guestContext, application, loadedResources, frameworkHooks,
                    frameworkCallRouter, packageMetadata, permissionPolicy, appOpsPolicy,
                    capabilityPolicy, capabilityAudit, capabilityLeases, virtualServices, nativePolicyConfigured,
                    nativeHooksInstalled, camera1AdapterInstalled, nativeCrashRecorderInstalled, webViewProfile,
                    stagedProcessIdentity);
            stagedSession = session;
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
            stagedHooks = null;
            stagedFrameworkCallRouter = null;
            session.components.prepareDeclaredProviders();
            session.mainThread.run(application::onCreate);
            if (nativePolicyConfigured && !camera1AdapterInstalled) {
                camera1AdapterInstalled = NativePolicy.installCamera1Adapter();
                session.camera1AdapterInstalled = camera1AdapterInstalled;
                android.util.Log.i("CS_CAMERA1_NATIVE", "ADAPTER_RETRY_AFTER_APPLICATION installed="
                        + camera1AdapterInstalled + " status=" + NativePolicy.camera1Status());
            }
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_ONCREATE:"
                        + NativePolicy.hookStatus());
            }
            Bundle ready = session.status("READY", started);
            RuntimeEventLog.event("GUEST_PREPARED", ready);
            stagedSession = null;
            synchronized (GuestRuntimeEnvironment.class) { preparing = false; }
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
                synchronized (GuestRuntimeEnvironment.class) {
                    current = null;
                    preparing = false;
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
            RuntimeEventLog.event("GUEST_PREPARE_FAILED", result);
            return result;
        }
    }

    public static synchronized Session require(String sessionId, long generation) {
        if (current == null) throw new IllegalStateException("GUEST_NOT_PREPARED");
        if (!current.spec.sessionId.equals(sessionId)) throw new SecurityException("SESSION_MISMATCH");
        if (current.spec.generation != generation) throw new SecurityException("GENERATION_MISMATCH");
        return current;
    }

    static synchronized Bundle status() {
        if (current == null) {
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "IDLE");
            out.putInt("pid", Process.myPid());
            return out;
        }
        return current.status(preparing ? "PREPARING" : "READY",
                android.os.SystemClock.elapsedRealtime());
    }

    static void shutdown(String sessionId, long generation) {
        Session session;
        synchronized (GuestRuntimeEnvironment.class) {
            if (preparing) throw new IllegalStateException("GUEST_PREPARATION_IN_PROGRESS");
            session = require(sessionId, generation);
            // Invalidate the lease before cleanup.  Cleanup can synchronously call a framework
            // or Broker route which re-enters this class; never hold the class monitor while
            // waiting for Guest main-thread lifecycle work to finish.
            current = null;
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
            if (preparing) return;
            session = current;
            if (session == null) return;
            current = null;
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

    private static Application instantiateApplication(GuestPackageSpec spec, ClassLoader loader)
            throws Exception {
        String className = spec.applicationClass == null || spec.applicationClass.trim().isEmpty()
                ? Application.class.getName() : spec.applicationClass;
        Class<?> type = loader.loadClass(className);
        if (!Application.class.isAssignableFrom(type)) throw new IllegalArgumentException("Application class has wrong type: " + className);
        return (Application) type.getDeclaredConstructor().newInstance();
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
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    /**
     * Valid APKs may use an applicationId that differs from their Java component namespace.
     * Derive compatibility exemptions only from this APK's manifest declarations; never add a
     * global package allowlist. GuestClassLoader still applies Host/internal deny-first rules.
     */
    private static java.util.List<String> declaredGuestClasses(GuestPackageSpec spec) {
        java.util.ArrayList<String> classes = new java.util.ArrayList<>();
        if (spec.applicationClass != null && !spec.applicationClass.trim().isEmpty()) {
            classes.add(spec.applicationClass);
        }
        for (com.warden.controlledsandbox.contract.VirtualComponentSnapshot component
                : spec.packageState.components()) {
            classes.add(component.className());
        }
        return java.util.List.copyOf(classes);
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

    public static final class Session {
        final GuestPackageSpec spec;
        final GuestClassLoader classLoader;
        final GuestContext context;
        final GuestMainThreadDispatcher mainThread;
        final Application application;
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
        volatile VirtualPackageStateSnapshot packageState;
        final boolean nativePolicyConfigured;
        final boolean nativeHooksInstalled;
        volatile boolean camera1AdapterInstalled;
        final boolean nativeCrashRecorderInstalled;
        final WebViewProfileManager.Profile webViewProfile;
        final GuestProcessIdentityBridge processIdentity;
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
                boolean camera1AdapterInstalled, boolean nativeCrashRecorderInstalled,
                WebViewProfileManager.Profile webViewProfile,
                GuestProcessIdentityBridge processIdentity) {
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
            this.packageState = spec.packageState;
            this.nativePolicyConfigured = nativePolicyConfigured;
            this.nativeHooksInstalled = nativeHooksInstalled;
            this.camera1AdapterInstalled = camera1AdapterInstalled;
            this.nativeCrashRecorderInstalled = nativeCrashRecorderInstalled;
            this.webViewProfile = webViewProfile;
            this.processIdentity = java.util.Objects.requireNonNull(processIdentity, "processIdentity");
        }

        public GuestPackageSpec spec() { return spec; }
        public GuestClassLoader classLoader() { return classLoader; }
        public GuestContext context() { return context; }
        public Application application() { return application; }
        public String sessionId() { return spec.sessionId; }
        public long generation() { return spec.generation; }
        public String packageName() { return spec.packageName; }
        public int virtualUserId() { return spec.virtualUserId; }
        public int processSlot() { return spec.processSlot; }

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
            Bundle out = spec.toBundle();
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
            java.util.ArrayList<String> deviceServiceBindings = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> item : frameworkHooks.report().bindingDetails().entrySet()) {
                deviceServiceBindings.add(item.getKey() + "=" + item.getValue());
            }
            out.putStringArrayList("deviceServiceBindings", deviceServiceBindings);
            out.putInt("capabilityAuditCount", capabilityAudit.size());
            out.putInt("capabilityDeniedCount", capabilityAudit.deniedCount());
            out.putInt("capabilityActiveLeases", capabilityLeases.activeCount());
            out.putStringArrayList("capabilityAudit", capabilityAudit.compactSnapshot());
            out.putBoolean("isolatedProcessSupported", false);
            out.putString("isolatedProcessPolicy", "FAIL_CLOSED_UNTIL_REAL_ANDROID_UID_SLOT_IS_VERIFIED");
            out.putBoolean("nativePolicyAvailable", NativePolicy.available());
            out.putBoolean("nativePolicyConfigured", nativePolicyConfigured);
            out.putBoolean("nativeHooksInstalled", nativeHooksInstalled);
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

        void shutdown() {
            if (jobServices != null) jobServices.close();
            // Stop accepting Guest-side component unbinds before the Broker-side component
            // runtime and WebView provider are torn down.  Chromium may issue one final unbind
            // asynchronously; GuestContextComponentRouter handles that late call explicitly.
            context.closeComponentServices();
            if (components != null) components.shutdown();
            capabilityLeases.close(capabilityAudit);
            webViewProfile.renderers.close();
            context.closeWebViewProviderServices();
            virtualServices.close();
            frameworkCallRouter.close();
            frameworkHooks.close();
            processIdentity.close();
            NativePolicy.resetAudioCapture();
            NativePolicy.resetCamera1();
            NativePolicy.resetHooks();
            NativePolicy.resetPolicy();
            NativePolicy.resetCrashRecorder();
            mainThread.close();
        }

        public String instanceId() { return "u" + spec.virtualUserId + ":" + spec.packageName; }
    }
}
