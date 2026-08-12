package com.warden.controlledsandbox;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.sdk.SandboxCatalog;
import com.warden.controlledsandbox.sdk.SandboxIdentity;
import com.warden.controlledsandbox.sdk.SandboxInstance;
import com.warden.controlledsandbox.sdk.SandboxOperationResult;
import com.warden.controlledsandbox.sdk.SandboxPackage;
import com.warden.controlledsandbox.sdk.SandboxSdk;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SX business adapter. UI calls this class; runtime internals stop here. */
final class SxSandboxAdapter implements SandboxSdk {
    private final PackageServiceClient packageService;
    private final RuntimeClient runtime;

    SxSandboxAdapter(Context context) {
        packageService = new PackageServiceClient(context);
        runtime = new RuntimeClient(context);
    }

    SandboxCatalogState load() throws Exception { return packageService.load(); }
    SandboxRecord findRecord(String packageName) throws Exception { return packageService.findRecord(packageName); }
    SandboxRecord importApk(Uri uri) throws Exception { return packageService.importApk(uri); }
    int createInstallSession(String expectedPackageName) throws Exception {
        return packageService.createInstallSession(expectedPackageName);
    }
    String addInstallArtifact(int sessionId, Uri source) throws Exception {
        return packageService.addInstallArtifact(sessionId, source);
    }
    SandboxRecord commitInstallSession(int sessionId) throws Exception {
        return packageService.commitInstallSession(sessionId);
    }
    void abandonInstallSession(int sessionId) throws Exception { packageService.abandonInstallSession(sessionId); }
    void updateInstanceStatus(String packageName, int virtualUserId, String status) throws Exception {
        packageService.updateInstanceStatus(packageName, virtualUserId, status);
    }
    String maintenanceWarning() throws Exception { return packageService.maintenanceWarning(); }
    int createClone(String packageName) throws Exception { return packageService.createClone(packageName); }
    SandboxCatalogState deleteInstance(String packageName, int virtualUserId) throws Exception {
        return packageService.deleteInstance(packageName, virtualUserId);
    }
    void clearInstanceData(String packageName, int virtualUserId) throws Exception {
        packageService.clearInstanceData(packageName, virtualUserId);
    }

    RuntimeStatusResult runtimeStatus() throws Exception { return runtime.status(); }
    Bundle prepare(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.prepare(record, virtualUserId);
    }
    Bundle launchBundle(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.launch(record, virtualUserId);
    }
    Bundle startService(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.startService(record, virtualUserId);
    }
    Bundle sendBroadcast(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.sendBroadcast(record, virtualUserId);
    }
    Bundle prepareProvider(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.prepareProvider(record, virtualUserId);
    }
    Bundle stopService(SandboxRecord record, int virtualUserId) throws Exception {
        return runtime.stopService(record, virtualUserId);
    }
    void stopRuntime(SandboxRecord record, int virtualUserId) throws Exception {
        runtime.stop(record, virtualUserId);
    }

    com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot deviceServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.deviceServiceProfile(packageName, virtualUserId);
    }
    com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot setDeviceServiceProfile(
            String packageName, int virtualUserId,
            com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot profile) throws Exception {
        return packageService.setDeviceServiceProfile(packageName, virtualUserId, profile);
    }
    com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot resetDeviceServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.resetDeviceServiceProfile(packageName, virtualUserId);
    }
    com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot networkServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.networkServiceProfile(packageName, virtualUserId);
    }
    com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot setNetworkServiceProfile(
            String packageName, int virtualUserId,
            com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot profile) throws Exception {
        return packageService.setNetworkServiceProfile(packageName, virtualUserId, profile);
    }
    com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot peripheralServicesProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.peripheralServicesProfile(packageName, virtualUserId);
    }
    com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot setPeripheralServicesProfile(
            String packageName, int virtualUserId,
            com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot profile) throws Exception {
        return packageService.setPeripheralServicesProfile(packageName, virtualUserId, profile);
    }
    com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot applicationEnvironmentProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.applicationEnvironmentProfile(packageName, virtualUserId);
    }

    @Override public SandboxCatalog catalog() throws Exception {
        return toSdkCatalog(packageService.load());
    }

    @Override public SandboxOperationResult importPackage(String source) throws Exception {
        if (source == null || source.trim().isEmpty()) {
            return SandboxOperationResult.failure("importPackage", "SOURCE_REQUIRED",
                    "APK source is required", null, Map.of());
        }
        SandboxRecord record = source.startsWith("content://")
                ? packageService.importApk(Uri.parse(source))
                : packageService.importApkFile(new File(source));
        return SandboxOperationResult.success("importPackage", "IMPORTED",
                identityFor(record, 0), diagnostics(record));
    }

    @Override public SandboxOperationResult ensureInstance(String packageName, int virtualUserId)
            throws Exception {
        packageService.ensureInstance(packageName, virtualUserId);
        SandboxRecord record = packageService.findRecord(packageName);
        return SandboxOperationResult.success("ensureInstance", "READY",
                identityFor(record, virtualUserId), Map.of());
    }

    @Override public SandboxOperationResult cloneInstance(String packageName) throws Exception {
        int userId = packageService.createClone(packageName);
        SandboxRecord record = packageService.findRecord(packageName);
        return SandboxOperationResult.success("cloneInstance", "CREATED",
                identityFor(record, userId), Map.of("virtualUserId", Integer.toString(userId)));
    }

    @Override public SandboxOperationResult launch(SandboxIdentity identity) throws Exception {
        SandboxRecord record = requireRecord(identity);
        return bundleResult("launch", identity, runtime.launch(record, identity.virtualUserId()));
    }

    @Override public SandboxOperationResult stop(SandboxIdentity identity) throws Exception {
        SandboxRecord record = requireRecord(identity);
        runtime.stop(record, identity.virtualUserId());
        return SandboxOperationResult.success("stop", "STOPPED", identity, Map.of());
    }

    @Override public SandboxOperationResult clearData(SandboxIdentity identity) throws Exception {
        packageService.clearInstanceData(identity.packageName(), identity.virtualUserId());
        return SandboxOperationResult.success("clearData", "CLEARED", identity, Map.of());
    }

    @Override public SandboxOperationResult deleteInstance(SandboxIdentity identity) throws Exception {
        SandboxRecord record = requireRecord(identity);
        runtime.stop(record, identity.virtualUserId());
        packageService.deleteInstance(identity.packageName(), identity.virtualUserId());
        return SandboxOperationResult.success("deleteInstance", "DELETED", identity, Map.of());
    }

    @Override public SandboxOperationResult status() throws Exception {
        RuntimeStatusResult result = runtime.status();
        if (!result.successful()) {
            return SandboxOperationResult.failure("status", result.error().code(),
                    result.error().message(), null, Map.of());
        }
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("runtimeStatus", result.status());
        diagnostics.put("capability", result.capability());
        if (result.snapshot() != null) {
            diagnostics.put("slotCapacity", Integer.toString(result.snapshot().slotCapacity()));
            diagnostics.put("slotUsed", Integer.toString(result.snapshot().slotUsed()));
            diagnostics.put("sessionCount", Integer.toString(result.snapshot().sessionCount()));
        }
        return SandboxOperationResult.success("status", result.status(), null, diagnostics);
    }

    private SandboxRecord requireRecord(SandboxIdentity identity) throws Exception {
        if (identity == null) throw new IllegalArgumentException("identity is required");
        SandboxRecord record = packageService.findRecord(identity.packageName());
        if (record == null) throw new IllegalArgumentException("Package is not installed: " + identity.packageName());
        return record;
    }

    private SandboxOperationResult bundleResult(String operation, SandboxIdentity identity, Bundle result) {
        String status = result == null ? "FAILED" : result.getString(RuntimeKeys.STATUS, "UNKNOWN");
        Map<String, String> diagnostics = bundleDiagnostics(result);
        if ("FAILED".equals(status)) {
            return SandboxOperationResult.failure(operation,
                    result == null ? "NO_RESULT" : result.getString(RuntimeKeys.ERROR_TYPE, "RUNTIME_FAILURE"),
                    result == null ? "Runtime returned no result" : result.getString(RuntimeKeys.ERROR_MESSAGE, ""),
                    identity, diagnostics);
        }
        return SandboxOperationResult.success(operation, status, identity, diagnostics);
    }

    private Map<String, String> bundleDiagnostics(Bundle result) {
        if (result == null) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionId", result.getString(RuntimeKeys.SESSION_ID, ""));
        values.put("generation", Long.toString(result.getLong(RuntimeKeys.GENERATION, 0L)));
        values.put("processSlot", Integer.toString(result.getInt(RuntimeKeys.PROCESS_SLOT, -1)));
        values.put("pid", Integer.toString(result.getInt("pid", -1)));
        values.put("durationMs", Long.toString(result.getLong("durationMs", -1L)));
        return values;
    }

    private SandboxCatalog toSdkCatalog(SandboxCatalogState state) throws Exception {
        List<SandboxPackage> packages = new ArrayList<>();
        for (SandboxRecord record : state.records()) {
            packages.add(new SandboxPackage(record.packageName, record.label, record.versionName,
                    record.versionCode, record.sha256, record.launchActivity,
                    record.nativeAbi, record.containsNativeCode));
        }
        List<SandboxInstance> instances = new ArrayList<>();
        for (com.warden.controlledsandbox.SandboxInstance instance : state.instances()) {
            instances.add(new SandboxInstance(instance.packageName, instance.virtualUserId,
                    instance.displayName, instance.createdAt, instance.lastRuntimeStatus,
                    instance.lastRuntimeAt));
        }
        return new SandboxCatalog(packages, instances, packageService.maintenanceWarning());
    }

    private SandboxIdentity identityFor(SandboxRecord record, int virtualUserId) {
        if (record == null) return null;
        return SandboxIdentity.forInstance(record.packageName, virtualUserId,
                record.launchProcess, record.versionCode, record.sha256);
    }

    private Map<String, String> diagnostics(SandboxRecord record) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("packageName", record.packageName);
        values.put("versionName", record.versionName);
        values.put("versionCode", Long.toString(record.versionCode));
        values.put("nativeAbi", record.nativeAbi);
        return values;
    }

    @Override public void close() {
        runtime.close();
        packageService.close();
    }
}
