package com.warden.controlledsandbox;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.RuntimeOperationRequest;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.runtime.protocol.RuntimeOperationTransport;
import com.warden.controlledsandbox.runtime.protocol.RebindableServiceConnector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

final class RuntimeClient implements AutoCloseable {
    private final Context context;
    private final RebindableServiceConnector<IRuntimeBroker> brokerConnection;
    private final PackageServiceClient packageService;
    private final NativeCompanionClient nativeCompanion;

    RuntimeClient(Context context) {
        this.context = context.getApplicationContext();
        this.packageService = new PackageServiceClient(this.context);
        this.nativeCompanion = new NativeCompanionClient(this.context);
        this.brokerConnection = new RebindableServiceConnector<>(this.context,
                new Intent(this.context, RuntimeBrokerService.class),
                IRuntimeBroker.Stub::asInterface, ignored -> { }, "Runtime broker");
    }

    RuntimeStatusResult status() throws Exception {
        RuntimeStatusResult result = requireBroker().runtimeStatusV2(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, UUID.randomUUID().toString()));
        if (result == null) throw new IllegalStateException("Runtime broker returned no status result");
        return result;
    }
    Bundle prepare(SandboxRecord record) throws Exception { return prepare(record, 0); }
    Bundle prepare(SandboxRecord record, int virtualUserId) throws Exception {
        Bundle request = request(record, virtualUserId, record.launchProcess);
        return companionRoute(record)
                ? nativeCompanion.prepare(record, virtualUserId, request)
                : execute(RuntimeOperationRequest.PREPARE_GUEST, request);
    }
    Bundle launch(SandboxRecord record) throws Exception { return launch(record, 0); }
    Bundle launch(SandboxRecord record, int virtualUserId) throws Exception {
        Bundle request = request(record, virtualUserId, record.launchProcess);
        return companionRoute(record)
                ? nativeCompanion.launchActivity(record, virtualUserId, request)
                : execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
    }
    Bundle launchComponent(SandboxRecord record, int virtualUserId, String component)
            throws Exception {
        if (component == null || component.trim().isEmpty()) {
            throw new IllegalArgumentException("activity component is required");
        }
        Bundle request = request(record, virtualUserId, record.launchProcess);
        request.putString(RuntimeKeys.COMPONENT_CLASS, component.trim());
        return companionRoute(record)
                ? nativeCompanion.launchActivity(record, virtualUserId, request)
                : execute(RuntimeOperationRequest.LAUNCH_ACTIVITY, request);
    }
    Bundle startService(SandboxRecord record) throws Exception { return startService(record, 0); }
    Bundle startService(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.START_SERVICE, record.serviceClass, record.serviceProcess, "", ""); }
    Bundle stopService(SandboxRecord record) throws Exception { return stopService(record, 0); }
    Bundle stopService(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.STOP_SERVICE, record.serviceClass, record.serviceProcess, "", ""); }
    Bundle startForegroundService(SandboxRecord record, int virtualUserId) throws Exception {
        return startForegroundService(record, virtualUserId, true, "", 0, 5_000L);
    }

    Bundle startForegroundService(SandboxRecord record, int virtualUserId,
                                  boolean backgroundStartAllowed, String exemptionReason,
                                  int declaredTypeMask, long promotionTimeoutMs) throws Exception {
        Bundle request = componentRequest(record, virtualUserId,
                ComponentOperations.START_FOREGROUND_SERVICE,
                record.serviceClass, record.serviceProcess, "", "");
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_BACKGROUND_ALLOWED,
                backgroundStartAllowed);
        request.putString(RuntimeKeys.SERVICE_FOREGROUND_EXEMPTION_REASON,
                exemptionReason == null ? "" : exemptionReason);
        request.putLong(RuntimeKeys.SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS,
                promotionTimeoutMs);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_DECLARED_TYPE_MASK,
                declaredTypeMask);
        return invoke(record, virtualUserId, request);
    }
    Bundle stopServiceStartId(SandboxRecord record, int virtualUserId, int startId) throws Exception {
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.STOP_SERVICE_START_ID,
                record.serviceClass, record.serviceProcess, "", "");
        request.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        return invoke(record, virtualUserId, request);
    }
    Bundle setServiceForeground(SandboxRecord record, int virtualUserId, boolean foreground) throws Exception {
        return setServiceForeground(record, virtualUserId, foreground, 0, 1, "sandbox", true);
    }

    Bundle setServiceForeground(SandboxRecord record, int virtualUserId, boolean foreground,
                                int typeMask, int notificationId, String notificationTag,
                                boolean removeNotification) throws Exception {
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.SET_SERVICE_FOREGROUND,
                record.serviceClass, record.serviceProcess, "", "");
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, foreground);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED_TYPE_MASK, typeMask);
        request.putInt(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_ID, notificationId);
        request.putString(RuntimeKeys.SERVICE_FOREGROUND_NOTIFICATION_TAG,
                notificationTag == null ? "" : notificationTag);
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REMOVE_NOTIFICATION, removeNotification);
        return invoke(record, virtualUserId, request);
    }
    BoundServiceLease bindService(SandboxRecord record, int virtualUserId, String connectionId) throws Exception {
        Binder clientToken = new Binder();
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.BIND_SERVICE,
                record.serviceClass, record.serviceProcess, "", "");
        request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        request.putBinder(RuntimeKeys.SERVICE_CONNECTION_BINDER, clientToken);
        Bundle result = invoke(record, virtualUserId, request);
        if ("FAILED".equals(result.getString(RuntimeKeys.STATUS, ""))) {
            throw new IllegalStateException(result.getString(RuntimeKeys.ERROR_TYPE, "SERVICE_BIND_FAILED"));
        }
        return new BoundServiceLease(record, virtualUserId, connectionId, clientToken,
                result.getBinder(RuntimeKeys.BINDER));
    }
    Bundle sendBroadcast(SandboxRecord record) throws Exception { return sendBroadcast(record, 0); }
    Bundle sendBroadcast(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.SEND_BROADCAST, record.receiverClass, record.receiverProcess, record.receiverAction, ""); }
    Bundle prepareProvider(SandboxRecord record) throws Exception { return prepareProvider(record, 0); }
    Bundle prepareProvider(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.PREPARE_PROVIDER, record.providerClass, record.providerProcess, "", record.providerAuthority); }
    void stop(SandboxRecord record) throws Exception { stop(record, 0); }
    void stop(SandboxRecord record, int virtualUserId) throws Exception {
        if (companionRoute(record)) nativeCompanion.stopGuest(record, virtualUserId);
        else requireBroker().stopGuest(record.packageName, virtualUserId);
    }

    private Bundle component(SandboxRecord record, int virtualUserId, String operation, String component,
                             String processName, String action, String authority) throws Exception {
        if (component == null || component.trim().isEmpty()) {
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "SKIPPED_NO_COMPONENT");
            return out;
        }
        Bundle request = componentRequest(record, virtualUserId, operation,
                component, processName, action, authority);
        return invoke(record, virtualUserId, request);
    }

    private Bundle componentRequest(SandboxRecord record, int virtualUserId, String operation, String component,
                                    String processName, String action, String authority) throws Exception {
        if (component == null || component.trim().isEmpty()) throw new IllegalArgumentException("component is required");
        Bundle request = request(record, virtualUserId, processName);
        request.putString(ComponentOperations.OPERATION, operation);
        request.putString(RuntimeKeys.COMPONENT_CLASS, component);
        request.putString(ComponentOperations.ACTION, action == null ? "" : action);
        request.putString(ComponentOperations.AUTHORITY, authority == null ? "" : authority);
        return request;
    }

    private boolean companionRoute(SandboxRecord record) {
        return NativeAbiRoutePlanner.requiresCompanion(record.nativeAbi);
    }

    private Bundle invoke(SandboxRecord record, int virtualUserId, Bundle request) throws Exception {
        return companionRoute(record)
                ? nativeCompanion.invokeComponent(record, virtualUserId, request)
                : execute(RuntimeOperationRequest.INVOKE_COMPONENT, request);
    }

    private Bundle execute(String operation, Bundle request) throws Exception {
        return RuntimeOperationTransport.toLegacyBundle(
                RuntimeOperationTransport.execute(requireBroker(), operation, request));
    }

    private Bundle request(SandboxRecord record, int virtualUserId, String processName) throws Exception {
        NativeGuestExecutionPolicy.requireRuntimeAllowed(record);
        VirtualPackageStateSnapshot packageState = packageService.virtualPackageState(
                record.packageName, virtualUserId);
        if (!record.sha256.equals(packageState.apkSha256())) {
            throw new SecurityException("PACKAGE_STATE_REVISION_MISMATCH");
        }
        Bundle request = new Bundle();
        request.putInt(RuntimeKeys.PROTOCOL, RuntimeProtocol.CURRENT);
        request.putString(RuntimeKeys.PACKAGE_NAME, record.packageName);
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, virtualUserId);
        request.putString(RuntimeKeys.PROCESS_NAME, processName == null || processName.trim().isEmpty()
                ? record.packageName : processName);
        request.putString(RuntimeKeys.APK_PATH, record.apkPath);
        request.putString(RuntimeKeys.APK_SHA256, record.sha256);
        request.putString(RuntimeKeys.BASE_APK_SHA256, record.baseApkSha256);
        ArrayList<String> splitNames = new ArrayList<>();
        ArrayList<String> splitTypes = new ArrayList<>();
        ArrayList<String> splitConfigFor = new ArrayList<>();
        ArrayList<String> splitUses = new ArrayList<>();
        ArrayList<String> splitPaths = new ArrayList<>();
        ArrayList<String> splitSha256s = new ArrayList<>();
        for (PackageArtifactRecord artifact : record.artifacts) {
            if (artifact.base()) continue;
            splitNames.add(artifact.splitName); splitTypes.add(artifact.type);
            splitConfigFor.add(artifact.configForSplit); splitUses.add(artifact.usesSplit);
            splitPaths.add(artifact.path); splitSha256s.add(artifact.sha256);
        }
        request.putStringArrayList(RuntimeKeys.SPLIT_NAMES, splitNames);
        request.putStringArrayList(RuntimeKeys.SPLIT_TYPES, splitTypes);
        request.putStringArrayList(RuntimeKeys.SPLIT_CONFIG_FOR, splitConfigFor);
        request.putStringArrayList(RuntimeKeys.SPLIT_USES, splitUses);
        request.putStringArrayList(RuntimeKeys.SPLIT_PATHS, splitPaths);
        request.putStringArrayList(RuntimeKeys.SPLIT_SHA256S, splitSha256s);
        request.putString(RuntimeKeys.SHARED_LIBRARIES, record.sharedLibraries);
        request.putLong(RuntimeKeys.APK_VERSION_CODE, record.versionCode);
        request.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, record.nativeLibraryDir);
        request.putString(RuntimeKeys.NATIVE_ABI, record.nativeAbi);
        request.putBoolean(RuntimeKeys.NATIVE_CODE_PRESENT, record.containsNativeCode);
        request.putString(RuntimeKeys.NATIVE_GUEST_TRUST, record.nativeGuestTrust);
        request.putString(RuntimeKeys.NATIVE_EXECUTION_MODE, record.nativeExecutionMode());
        request.putString(RuntimeKeys.APPLICATION_CLASS, record.applicationClass);
        request.putString(RuntimeKeys.COMPONENT_CLASS, record.launchActivity);
        ArrayList<String> permissions = new ArrayList<>();
        if (record.permissions != null && !record.permissions.trim().isEmpty()) permissions.addAll(Arrays.asList(record.permissions.split(",")));
        request.putStringArrayList(RuntimeKeys.PERMISSIONS, permissions);
        request.putParcelable(RuntimeKeys.PACKAGE_STATE, packageState);
        request.putParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE,
                packageUniverse(record, virtualUserId));
        return request;
    }

    private ArrayList<VirtualPackageProjectionSnapshot> packageUniverse(
            SandboxRecord current, int virtualUserId) throws Exception {
        ArrayList<VirtualPackageProjectionSnapshot> result = new ArrayList<>();
        int nextUid = 10000;
        for (SandboxRecord record : packageService.load().records()) {
            if (current.packageName.equals(record.packageName)) continue;
            VirtualPackageStateSnapshot state;
            try {
                state = packageService.virtualPackageState(record.packageName, virtualUserId);
            } catch (Exception unavailableForUser) {
                // A package without an instance for this virtual user is not installed in that
                // user's virtual PMS view. Do not fall back to Host PMS metadata.
                android.util.Log.i("CS_PM_UNIVERSE_SKIP", "package=" + record.packageName
                        + " user=" + virtualUserId + " reason="
                        + unavailableForUser.getClass().getSimpleName());
                continue;
            }
            android.content.pm.ApplicationInfo parsedApplicationInfo = null;
            try {
                android.content.pm.PackageInfo packageInfo = context.getPackageManager()
                        .getPackageArchiveInfo(record.apkPath, android.content.pm.PackageManager.GET_META_DATA);
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    parsedApplicationInfo = new android.content.pm.ApplicationInfo(packageInfo.applicationInfo);
                }
            } catch (RuntimeException ignored) {
                // The package authority state remains authoritative if the platform parser
                // cannot read an optional projection APK on this device image.
            }
            result.add(new VirtualPackageProjectionSnapshot(state, record.apkPath,
                    record.nativeLibraryDir, nextUid++, parsedApplicationInfo));
        }
        return result;
    }

    final class BoundServiceLease implements AutoCloseable {
        private final SandboxRecord record;
        private final int virtualUserId;
        private final String connectionId;
        @SuppressWarnings("unused") private final IBinder clientToken;
        private final IBinder serviceBinder;
        private boolean closed;

        BoundServiceLease(SandboxRecord record, int virtualUserId, String connectionId,
                          IBinder clientToken, IBinder serviceBinder) {
            this.record = record;
            this.virtualUserId = virtualUserId;
            this.connectionId = connectionId;
            this.clientToken = clientToken;
            this.serviceBinder = serviceBinder;
        }

        IBinder binder() { return serviceBinder; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                Bundle request = componentRequest(record, virtualUserId, ComponentOperations.UNBIND_SERVICE,
                        record.serviceClass, record.serviceProcess, "", "");
                request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
                invoke(record, virtualUserId, request);
            } catch (Exception error) {
                throw new IllegalStateException("SERVICE_UNBIND_FAILED", error);
            }
        }
    }

    private IRuntimeBroker requireBroker() throws Exception {
        return brokerConnection.require();
    }

    @Override public void close() {
        brokerConnection.close();
        nativeCompanion.close();
        packageService.close();
    }
}
