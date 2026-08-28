package com.warden.controlledsandbox;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import com.warden.controlledsandbox.compatibility.dingtalk.DingTalkCompatibilityManager;
import com.warden.controlledsandbox.contract.ApplicationEnvironmentProfileSnapshot;
import com.warden.controlledsandbox.contract.RuntimeStatusResult;
import com.warden.controlledsandbox.contract.VirtualDeviceServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import com.warden.controlledsandbox.sdk.CasSandboxEngine;
import com.warden.controlledsandbox.sdk.SandboxOperationResult;
import java.util.List;
import java.util.Map;

/** Application-facing use cases. Screens never cross this boundary into Binder or runtime code. */
final class SandboxApplicationLayer implements AutoCloseable {
    private final Context context;
    private final SxSandboxAdapter adapter;
    private final CasSandboxEngine engine;

    SandboxApplicationLayer(Context context) {
        this.context = context.getApplicationContext();
        this.adapter = new SxSandboxAdapter(this.context);
        this.engine = new CasSandboxEngine(this.adapter);
    }

    SandboxCatalogState load() throws Exception { return adapter.load(); }
    SandboxRecord findRecord(String packageName) throws Exception { return adapter.findRecord(packageName); }
    SandboxRecord importApk(Uri uri) throws Exception {
        return importApk(uri, "");
    }
    SandboxRecord importApk(Uri uri, String nativeGuestTrust) throws Exception {
        if (nativeGuestTrust != null && !nativeGuestTrust.isBlank()) {
            return adapter.importApk(uri, nativeGuestTrust);
        }
        SandboxOperationResult result = engine.installFromApk(uri == null ? "" : uri.toString());
        requireSuccess(result);
        String packageName = result.identity() == null ? "" : result.identity().packageName();
        return requireRecord(packageName);
    }
    List<InstalledApplication> installedApplications() throws Exception {
        return adapter.installedApplications();
    }
    SandboxRecord importInstalledApplication(String packageName, String nativeGuestTrust)
            throws Exception {
        return importInstalledApplication(packageName, nativeGuestTrust,
                java.util.UUID.randomUUID().toString());
    }
    SandboxRecord importInstalledApplication(String packageName, String nativeGuestTrust,
            String requestId) throws Exception {
        ImportResult imported = importInstalledApplicationOperation(packageName,
                nativeGuestTrust, requestId);
        requireSuccess(imported.operation());
        return imported.record();
    }
    ImportResult importInstalledApplicationOperation(String packageName, String nativeGuestTrust,
            String requestId) throws Exception {
        SandboxOperationResult result = engine.installFromHost(packageName, nativeGuestTrust,
                requestId);
        return new ImportResult(result.successful() ? requireRecord(packageName) : null, result);
    }
    int createClone(String packageName) throws Exception {
        SandboxOperationResult result = engine.clone(packageName);
        requireSuccess(result);
        String user = result.diagnostics().get("virtualUserId");
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("CLONE_FAILED:missing virtualUserId");
        }
        return Integer.parseInt(user);
    }
    void updateInstanceStatus(String packageName, int userId, String status) throws Exception {
        adapter.updateInstanceStatus(packageName, userId, status);
    }
    void clearData(String packageName, int userId) throws Exception {
        // PackageManagementSession owns the destructive transaction, including the runtime
        // stop/death barrier.  Keeping the stop here would create a second, non-transactional
        // lifecycle edge before the authoritative package-service operation.
        requireSuccess(engine.clearData(packageName, userId));
    }
    void deleteInstance(String packageName, int userId) throws Exception {
        // Deletion is serialized by PackageManagementSession.  It must stop the generation and
        // clear all virtual-service state before mutating the catalog or instance directory.
        requireSuccess(engine.uninstall(packageName, userId));
        SandboxShortcutManager.disable(context, packageName, userId);
    }
    Bundle launch(SandboxRecord record, int userId) throws Exception {
        return toBundle(requireSuccess(engine.launch(record.packageName, userId)));
    }
    Bundle launchAndAwaitReadiness(SandboxRecord record, int userId) throws Exception {
        return toBundle(requireSuccess(engine.launchAndAwaitReadiness(
                record.packageName, userId)));
    }
    Bundle prepare(SandboxRecord record, int userId) throws Exception { return adapter.prepare(record, userId); }
    Bundle startService(SandboxRecord record, int userId) throws Exception { return adapter.startService(record, userId); }
    Bundle sendBroadcast(SandboxRecord record, int userId) throws Exception { return adapter.sendBroadcast(record, userId); }
    Bundle prepareProvider(SandboxRecord record, int userId) throws Exception { return adapter.prepareProvider(record, userId); }
    Bundle stopService(SandboxRecord record, int userId) throws Exception { return adapter.stopService(record, userId); }
    void stop(SandboxRecord record, int userId) throws Exception {
        requireSuccess(engine.kill(record.packageName, userId));
    }
    RuntimeStatusResult runtimeStatus() throws Exception { return adapter.runtimeStatus(); }
    String maintenanceWarning() throws Exception { return adapter.maintenanceWarning(); }

    VirtualDeviceServiceProfileSnapshot deviceProfile(String packageName, int userId) throws Exception {
        return adapter.deviceServiceProfile(packageName, userId);
    }
    VirtualDeviceServiceProfileSnapshot saveDeviceProfile(String packageName, int userId,
            VirtualDeviceServiceProfileSnapshot profile) throws Exception {
        return adapter.setDeviceServiceProfile(packageName, userId, profile);
    }
    VirtualDeviceServiceProfileSnapshot resetDeviceProfile(String packageName, int userId) throws Exception {
        return adapter.resetDeviceServiceProfile(packageName, userId);
    }
    VirtualDeviceServiceProfileSnapshot defaultDeviceProfile(String packageName, int userId) throws Exception {
        VirtualDeviceServiceProfileSnapshot current = deviceProfile(packageName, userId);
        return VirtualDeviceServiceDefaults.create(packageName, userId,
                current.policyVersion(), current.updatedAtMs());
    }
    VirtualNetworkServiceProfileSnapshot networkProfile(String packageName, int userId) throws Exception {
        return adapter.networkServiceProfile(packageName, userId);
    }
    VirtualNetworkServiceProfileSnapshot saveNetworkProfile(String packageName, int userId,
            VirtualNetworkServiceProfileSnapshot profile) throws Exception {
        return adapter.setNetworkServiceProfile(packageName, userId, profile);
    }
    VirtualNetworkServiceProfileSnapshot resetNetworkProfile(String packageName, int userId) throws Exception {
        return adapter.resetNetworkServiceProfile(packageName, userId);
    }
    VirtualPeripheralServicesProfileSnapshot peripheralProfile(String packageName, int userId) throws Exception {
        return adapter.peripheralServicesProfile(packageName, userId);
    }
    VirtualPeripheralServicesProfileSnapshot savePeripheralProfile(String packageName, int userId,
            VirtualPeripheralServicesProfileSnapshot profile) throws Exception {
        return adapter.setPeripheralServicesProfile(packageName, userId, profile);
    }
    ApplicationEnvironmentProfileSnapshot applicationProfile(String packageName, int userId) throws Exception {
        return adapter.applicationEnvironmentProfile(packageName, userId);
    }
    Context context() { return context; }

    record ImportResult(SandboxRecord record, SandboxOperationResult operation) { }

    Bundle componentSmoke(String packageName, int userId) throws Exception {
        SandboxRecord record = requireRecord(packageName);
        Bundle out = new Bundle();
        out.putBundle("prepare", adapter.prepare(record, userId));
        out.putBundle("service", adapter.startService(record, userId));
        out.putBundle("receiver", adapter.sendBroadcast(record, userId));
        out.putBundle("provider", adapter.prepareProvider(record, userId));
        out.putBundle("stop", adapter.stopService(record, userId));
        return out;
    }

    DingTalkCompatibilityManager.Target dingTalkTarget(SandboxRecord record) {
        return new DingTalkCompatibilityManager().identify(record.packageName, record.versionName,
                record.versionCode);
    }
    void setDingTalkEnabled(DingTalkCompatibilityManager.Target target, int userId, boolean enabled) {
        DingTalkCompatibilityManager manager = new DingTalkCompatibilityManager();
        if (enabled) manager.enable(context, target, userId);
        else manager.disable(context, target.packageName(), userId);
    }
    boolean dingTalkEnabled(String packageName, int userId) {
        return new DingTalkCompatibilityManager().enabled(context, packageName, userId);
    }

    VirtualCameraSource importCameraSource(String packageName, int userId, Uri uri, String kind)
            throws Exception {
        return new VirtualCameraSource(VirtualCameraMediaStore.importSource(context, packageName, userId, uri, kind));
    }

    private SandboxRecord requireRecord(String packageName) throws Exception {
        SandboxRecord record = findRecord(packageName);
        if (record == null) throw new IllegalArgumentException("Package is not installed: " + packageName);
        return record;
    }

    private static SandboxOperationResult requireSuccess(SandboxOperationResult result) throws Exception {
        if (result == null || !result.successful()) {
            String code = result == null ? "NO_RESULT" : result.errorCode();
            String message = result == null ? "engine returned no result" : result.errorMessage();
            throw new IllegalStateException(code + ":" + message);
        }
        return result;
    }

    private static Bundle toBundle(SandboxOperationResult result) {
        Bundle out = new Bundle();
        out.putString(RuntimeKeys.STATUS, result.status());
        if (result.identity() != null) {
            out.putString("packageName", result.identity().packageName());
            out.putInt("virtualUserId", result.identity().virtualUserId());
            out.putString("instanceId", result.identity().instanceId());
        }
        for (Map.Entry<String, String> entry : result.diagnostics().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.putString(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    @Override public void close() { adapter.close(); }

    /** Small wrapper keeps the media import boundary explicit for the screen layer. */
    record VirtualCameraSource(com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot snapshot) { }
}
