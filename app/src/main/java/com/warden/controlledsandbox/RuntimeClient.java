package com.warden.controlledsandbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.contract.IRuntimeBroker;
import com.warden.controlledsandbox.contract.RuntimeStatusRequest;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.domain.protocol.RuntimeProtocol;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RuntimeClient implements AutoCloseable {
    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IRuntimeBroker broker;
    private final PackageServiceClient packageService;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { broker = IRuntimeBroker.Stub.asInterface(service); connected.countDown(); }
        @Override public void onServiceDisconnected(ComponentName name) { broker = null; }
    };

    RuntimeClient(Context context) {
        this.context = context.getApplicationContext();
        this.packageService = new PackageServiceClient(this.context);
        if (!this.context.bindService(new Intent(this.context, RuntimeBrokerService.class), connection, Context.BIND_AUTO_CREATE)) connected.countDown();
    }

    RuntimeStatusResult status() throws Exception {
        RuntimeStatusResult result = requireBroker().runtimeStatusV2(
                new RuntimeStatusRequest(RuntimeProtocol.CURRENT, UUID.randomUUID().toString()));
        if (result == null) throw new IllegalStateException("Runtime broker returned no status result");
        return result;
    }
    Bundle prepare(SandboxRecord record) throws Exception { return prepare(record, 0); }
    Bundle prepare(SandboxRecord record, int virtualUserId) throws Exception { return requireBroker().prepareGuest(request(record, virtualUserId, record.launchProcess)); }
    Bundle launch(SandboxRecord record) throws Exception { return launch(record, 0); }
    Bundle launch(SandboxRecord record, int virtualUserId) throws Exception { return requireBroker().launchActivity(request(record, virtualUserId, record.launchProcess)); }
    Bundle startService(SandboxRecord record) throws Exception { return startService(record, 0); }
    Bundle startService(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.START_SERVICE, record.serviceClass, record.serviceProcess, "", ""); }
    Bundle stopService(SandboxRecord record) throws Exception { return stopService(record, 0); }
    Bundle stopService(SandboxRecord record, int virtualUserId) throws Exception { return component(record, virtualUserId, ComponentOperations.STOP_SERVICE, record.serviceClass, record.serviceProcess, "", ""); }
    Bundle startForegroundService(SandboxRecord record, int virtualUserId) throws Exception {
        return component(record, virtualUserId, ComponentOperations.START_FOREGROUND_SERVICE,
                record.serviceClass, record.serviceProcess, "", "");
    }
    Bundle stopServiceStartId(SandboxRecord record, int virtualUserId, int startId) throws Exception {
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.STOP_SERVICE_START_ID,
                record.serviceClass, record.serviceProcess, "", "");
        request.putInt(RuntimeKeys.SERVICE_START_ID, startId);
        return requireBroker().invokeComponent(request);
    }
    Bundle setServiceForeground(SandboxRecord record, int virtualUserId, boolean foreground) throws Exception {
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.SET_SERVICE_FOREGROUND,
                record.serviceClass, record.serviceProcess, "", "");
        request.putBoolean(RuntimeKeys.SERVICE_FOREGROUND_REQUESTED, foreground);
        return requireBroker().invokeComponent(request);
    }
    BoundServiceLease bindService(SandboxRecord record, int virtualUserId, String connectionId) throws Exception {
        Binder clientToken = new Binder();
        Bundle request = componentRequest(record, virtualUserId, ComponentOperations.BIND_SERVICE,
                record.serviceClass, record.serviceProcess, "", "");
        request.putString(RuntimeKeys.CONNECTION_ID, connectionId);
        request.putBinder(RuntimeKeys.SERVICE_CONNECTION_BINDER, clientToken);
        Bundle result = requireBroker().invokeComponent(request);
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
    void stop(SandboxRecord record, int virtualUserId) throws Exception { requireBroker().stopGuest(record.packageName, virtualUserId); }

    private Bundle component(SandboxRecord record, int virtualUserId, String operation, String component,
                             String processName, String action, String authority) throws Exception {
        if (component == null || component.trim().isEmpty()) {
            Bundle out = new Bundle();
            out.putString(RuntimeKeys.STATUS, "SKIPPED_NO_COMPONENT");
            return out;
        }
        return requireBroker().invokeComponent(componentRequest(record, virtualUserId, operation,
                component, processName, action, authority));
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

    private Bundle request(SandboxRecord record, int virtualUserId, String processName) throws Exception {
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
        request.putString(RuntimeKeys.APPLICATION_CLASS, record.applicationClass);
        request.putString(RuntimeKeys.COMPONENT_CLASS, record.launchActivity);
        ArrayList<String> permissions = new ArrayList<>();
        if (record.permissions != null && !record.permissions.trim().isEmpty()) permissions.addAll(Arrays.asList(record.permissions.split(",")));
        request.putStringArrayList(RuntimeKeys.PERMISSIONS, permissions);
        request.putParcelable(RuntimeKeys.PACKAGE_STATE, packageState);
        return request;
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
                requireBroker().invokeComponent(request);
            } catch (Exception error) {
                throw new IllegalStateException("SERVICE_UNBIND_FAILED", error);
            }
        }
    }

    private IRuntimeBroker requireBroker() throws Exception {
        if (!connected.await(10, TimeUnit.SECONDS) || broker == null) throw new IllegalStateException("Runtime broker is unavailable");
        return broker;
    }

    @Override public void close() {
        try { context.unbindService(connection); } catch (Exception ignored) { }
        packageService.close();
        broker = null;
    }
}
