package com.warden.controlledsandbox.runtime.guest;

import com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.PackageRevisionSetVerifier;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import com.warden.controlledsandbox.nativebridge.NativeNetworkIdentity;
import com.warden.controlledsandbox.runtime.systemservice.RemoteVirtualSystemServiceAuthority;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Process-local runtime. One Android guest process hosts exactly one generation at a time. */
public final class GuestRuntimeEnvironment {
    private static Session current;

    private GuestRuntimeEnvironment() { }

    static synchronized Bundle prepare(Context host, GuestPackageSpec spec) {
        long started = android.os.SystemClock.elapsedRealtime();
        Bundle result = new Bundle();
        FrameworkHooks stagedHooks = null;
        GuestFrameworkCallRouter stagedFrameworkCallRouter = null;
        Session stagedSession = null;
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
                    return current.status("ALREADY_READY", started);
                }
                if (spec.generation <= current.spec.generation) throw new IllegalStateException("STALE_GUEST_GENERATION");
                current.shutdown();
            }
            File optimized = new File(host.getCodeCacheDir(), "guest/" + safe(spec.packageName)
                    + "/" + safe(spec.packageRevision) + "/" + spec.generation);
            ensureDirectory(optimized);
            GuestClassLoader loader = new GuestClassLoader(spec.dexPath(), optimized.getAbsolutePath(),
                    emptyToNull(spec.nativeLibraryDir), GuestRuntimeEnvironment.class.getClassLoader());
            GuestResourceLoader.LoadedResources loadedResources = GuestResourceLoader.load(
                    host, spec.apkPath, spec.splitPathArray());
            GuestContext guestContext = new GuestContext(host, spec, loader, loadedResources.resources, loadedResources.assets);
            String nativeAbi = spec.nativeAbi;
            int virtualPid = 20000 + (spec.virtualUserId * 100) + spec.processSlot;
            boolean nativePolicyConfigured = NativePolicy.configure(spec.sessionId, spec.generation,
                    spec.packageName, spec.processName, spec.virtualUserId, spec.virtualUid,
                    virtualPid, nativeAbi, spec.dataRoot, spec.apkPath,
                    spec.nativeLibraryDir, true, new String[0], new String[0], new String[0], new String[0],
                    new String[0], new String[0],
                    NativeNetworkIdentity.isolated(spec.packageName, spec.virtualUserId));
            boolean requiresNativeHooks = spec.nativeLibraryDir != null && !spec.nativeLibraryDir.trim().isEmpty();
            if (requiresNativeHooks && !nativePolicyConfigured) {
                throw new IllegalStateException("NATIVE_FILE_POLICY_UNAVAILABLE");
            }
            boolean nativeHooksInstalled = requiresNativeHooks && NativePolicy.installHooks(spec.nativeLibraryDir);
            if (requiresNativeHooks && !nativeHooksInstalled) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_INSTALL_FAILED:" + NativePolicy.hookStatus());
            }
            WebViewProfileManager.Profile webViewProfile = WebViewProfileManager.install(spec);
            VirtualPackageMetadata packageMetadata = GuestPackageMetadataMapper.fromSnapshot(
                    spec.packageState, guestContext.getApplicationInfo());
            IVirtualSystemServiceSession systemServiceSession = IVirtualSystemServiceSession.Stub.asInterface(
                    spec.virtualSystemServiceBinder);
            if (systemServiceSession == null) throw new IllegalStateException("VIRTUAL_SYSTEM_SERVICE_CAPABILITY_INVALID");
            VirtualSystemServiceState virtualServices = new VirtualSystemServiceState(
                    new RemoteVirtualSystemServiceAuthority(systemServiceSession, loader));
            GuestFrameworkCallRouter frameworkCallRouter = new GuestFrameworkCallRouter(
                    spec, virtualServices.pendingIntents(), new GuestPendingIntentDispatcher(guestContext, spec));
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
                    new GuestIdentity(spec.packageName, spec.virtualUid, guestContext.getApplicationInfo(),
                            new HashSet<>(spec.permissions), host.getPackageName(), Process.myUid(),
                            packageMetadata, spec.processName, spec.virtualUserId, spec.generation,
                            permissionPolicy, appOpsPolicy, capabilityAudit, capabilityLeases, virtualServices,
                            spec.packageRevision),
                    frameworkCallRouter);
            stagedHooks = frameworkHooks;
            frameworkHooks.report().requireMandatoryReady();
            CapabilityProxyReadiness.require(frameworkHooks.report().installedServices(),
                    spec.packageState.permissions());
            DeviceServiceProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.deviceServiceProfile());
            InteractionProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.interactionProfile());
            NetworkServiceProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.networkServiceProfile());
            ApplicationEnvironmentProxyReadiness.require(frameworkHooks.report().installedServices(),
                    virtualServices.applicationEnvironmentProfile());
            Application application = createApplication(spec, loader, guestContext);
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_CREATE:"
                        + NativePolicy.hookStatus());
            }
            guestContext.application(application);
            Session session = new Session(spec, loader, guestContext, application, loadedResources, frameworkHooks,
                    frameworkCallRouter, packageMetadata, permissionPolicy, appOpsPolicy,
                    capabilityPolicy, capabilityAudit, capabilityLeases, virtualServices, nativePolicyConfigured,
                    nativeHooksInstalled, nativeCrashRecorderInstalled, webViewProfile);
            stagedSession = session;
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
            current = session;
            stagedHooks = null;
            stagedFrameworkCallRouter = null;
            Thread.currentThread().setContextClassLoader(loader);
            application.onCreate();
            if (nativeHooksInstalled && !NativePolicy.refreshHooks()) {
                throw new IllegalStateException("NATIVE_FILE_HOOK_REFRESH_FAILED_AFTER_APPLICATION_ONCREATE:"
                        + NativePolicy.hookStatus());
            }
            Bundle ready = session.status("READY", started);
            RuntimeEventLog.event("GUEST_PREPARED", ready);
            stagedSession = null;
            return ready;
        } catch (Throwable error) {
            if (stagedSession != null) stagedSession.shutdown();
            else {
                if (stagedHooks != null) stagedHooks.close();
                if (stagedFrameworkCallRouter != null) stagedFrameworkCallRouter.close();
            }
            NativePolicy.resetAudioCapture();
            NativePolicy.resetHooks();
            NativePolicy.resetPolicy();
            current = null;
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
        return current.status("READY", android.os.SystemClock.elapsedRealtime());
    }

    static synchronized void shutdown(String sessionId, long generation) {
        Session session = require(sessionId, generation);
        session.shutdown();
        current = null;
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

    private static Application createApplication(GuestPackageSpec spec, ClassLoader loader,
                                                 GuestContext context) throws Exception {
        String className = spec.applicationClass == null || spec.applicationClass.trim().isEmpty()
                ? Application.class.getName() : spec.applicationClass;
        Class<?> type = loader.loadClass(className);
        if (!Application.class.isAssignableFrom(type)) throw new IllegalArgumentException("Application class has wrong type: " + className);
        Application application = (Application) type.getDeclaredConstructor().newInstance();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, context);
        return application;
    }

    private static String emptyToNull(String value) { return value == null || value.trim().isEmpty() ? null : value; }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
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
        final boolean nativeCrashRecorderInstalled;
        final WebViewProfileManager.Profile webViewProfile;
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
                boolean nativeCrashRecorderInstalled, WebViewProfileManager.Profile webViewProfile) {
            this.spec = spec;
            this.classLoader = classLoader;
            this.context = context;
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
            this.nativeCrashRecorderInstalled = nativeCrashRecorderInstalled;
            this.webViewProfile = webViewProfile;
        }

        public GuestPackageSpec spec() { return spec; }
        public GuestClassLoader classLoader() { return classLoader; }
        public GuestContext context() { return context; }
        public Application application() { return application; }

        public void bindActivityTaskHost(IBinder frameworkToken, String activityToken, int taskId,
                                         Runnable moveToFront, BooleanSupplier moveToBack,
                                         Runnable finishAffinity, Runnable finishAndRemoveTask) {
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

        private synchronized boolean onVirtualJobStart(int guestJobId, Object jobPayload,
                com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobParametersRecord parameters,
                com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority.JobExecution execution) {
            if (jobServices == null) return false;
            return jobServices.start(guestJobId, jobPayload, parameters, execution);
        }

        private synchronized boolean onVirtualJobStop(int guestJobId,
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
            out.putString("processName", Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "pid-" + Process.myPid());
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
            out.putInt("capabilityAuditCount", capabilityAudit.size());
            out.putInt("capabilityDeniedCount", capabilityAudit.deniedCount());
            out.putInt("capabilityActiveLeases", capabilityLeases.activeCount());
            out.putStringArrayList("capabilityAudit", capabilityAudit.compactSnapshot());
            out.putBoolean("isolatedProcessSupported", false);
            out.putString("isolatedProcessPolicy", "FAIL_CLOSED_UNTIL_REAL_ANDROID_UID_SLOT_IS_VERIFIED");
            out.putBoolean("nativePolicyAvailable", NativePolicy.available());
            out.putBoolean("nativePolicyConfigured", nativePolicyConfigured);
            out.putBoolean("nativeHooksInstalled", nativeHooksInstalled);
            out.putString("nativeHookStatus", NativePolicy.hookStatus());
            out.putString("nativeNetworkStatus", NativePolicy.networkStatus());
            out.putString("nativeLoaderStatus", NativePolicy.loaderStatus());
            out.putString("nativeAudioCaptureStatus", NativePolicy.audioCaptureStatus());
            out.putBoolean("nativeCrashRecorderInstalled", nativeCrashRecorderInstalled);
            out.putString("nativeCrashStatus", NativePolicy.crashStatus());
            out.putAll(RuntimeDiagnostics.snapshot());
            out.putAll(webViewProfile.toBundle());
            out.putString("nativePolicyLoadError", NativePolicy.loadError());
            out.putString("frameworkHookError", frameworkHooks.report().errorType() + ":" + frameworkHooks.report().errorMessage());
            out.putLong("durationMs", Math.max(0, android.os.SystemClock.elapsedRealtime() - started));
            return out;
        }

        void shutdown() {
            if (jobServices != null) jobServices.close();
            if (components != null) components.shutdown();
            capabilityLeases.close(capabilityAudit);
            virtualServices.close();
            frameworkCallRouter.close();
            frameworkHooks.close();
            NativePolicy.resetHooks();
            NativePolicy.resetPolicy();
            NativePolicy.resetCrashRecorder();
            Thread.currentThread().setContextClassLoader(GuestRuntimeEnvironment.class.getClassLoader());
        }

        public String instanceId() { return "u" + spec.virtualUserId + ":" + spec.packageName; }
    }
}
