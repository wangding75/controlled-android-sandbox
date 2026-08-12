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

/** Application-facing use cases. Screens never cross this boundary into Binder or runtime code. */
final class SandboxApplicationLayer implements AutoCloseable {
    private final Context context;
    private final SxSandboxAdapter adapter;

    SandboxApplicationLayer(Context context) {
        this.context = context.getApplicationContext();
        this.adapter = new SxSandboxAdapter(this.context);
    }

    SandboxCatalogState load() throws Exception { return adapter.load(); }
    SandboxRecord findRecord(String packageName) throws Exception { return adapter.findRecord(packageName); }
    SandboxRecord importApk(Uri uri) throws Exception { return adapter.importApk(uri); }
    SandboxRecord importApk(Uri uri, String nativeGuestTrust) throws Exception {
        return adapter.importApk(uri, nativeGuestTrust);
    }
    int createClone(String packageName) throws Exception { return adapter.createClone(packageName); }
    void updateInstanceStatus(String packageName, int userId, String status) throws Exception {
        adapter.updateInstanceStatus(packageName, userId, status);
    }
    void clearData(String packageName, int userId) throws Exception { adapter.clearInstanceData(packageName, userId); }
    void deleteInstance(String packageName, int userId) throws Exception {
        adapter.stopRuntime(requireRecord(packageName), userId);
        adapter.deleteInstance(packageName, userId);
    }
    Bundle launch(SandboxRecord record, int userId) throws Exception { return adapter.launchBundle(record, userId); }
    Bundle prepare(SandboxRecord record, int userId) throws Exception { return adapter.prepare(record, userId); }
    Bundle startService(SandboxRecord record, int userId) throws Exception { return adapter.startService(record, userId); }
    Bundle sendBroadcast(SandboxRecord record, int userId) throws Exception { return adapter.sendBroadcast(record, userId); }
    Bundle prepareProvider(SandboxRecord record, int userId) throws Exception { return adapter.prepareProvider(record, userId); }
    Bundle stopService(SandboxRecord record, int userId) throws Exception { return adapter.stopService(record, userId); }
    void stop(SandboxRecord record, int userId) throws Exception { adapter.stopRuntime(record, userId); }
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

    @Override public void close() { adapter.close(); }

    /** Small wrapper keeps the media import boundary explicit for the screen layer. */
    record VirtualCameraSource(com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot snapshot) { }
}
