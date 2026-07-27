package com.warden.controlledsandbox;

import org.json.JSONException;
import org.json.JSONObject;

final class SandboxRecord {
    final String packageName;
    final String label;
    final String versionName;
    final long versionCode;
    final String signatureSha256;
    final String apkPath;
    final String nativeLibraryDir;
    final String launchActivity;
    final String launchProcess;
    final String applicationClass;
    final String serviceClass;
    final String serviceProcess;
    final String receiverClass;
    final String receiverProcess;
    final String receiverAction;
    final String providerClass;
    final String providerProcess;
    final String providerAuthority;
    final String permissions;
    final String sha256;
    final long importedAt;
    String lastProbeStatus;
    long lastProbeAt;

    SandboxRecord(String packageName, String label, String versionName, long versionCode,
                  String signatureSha256, String apkPath, String nativeLibraryDir,
                  String launchActivity, String launchProcess, String applicationClass,
                  String serviceClass, String serviceProcess, String receiverClass,
                  String receiverProcess, String receiverAction, String providerClass,
                  String providerProcess, String providerAuthority, String permissions,
                  String sha256, long importedAt, String lastProbeStatus, long lastProbeAt) {
        this.packageName = packageName;
        this.label = label;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.signatureSha256 = signatureSha256;
        this.apkPath = apkPath;
        this.nativeLibraryDir = nativeLibraryDir;
        this.launchActivity = launchActivity;
        this.launchProcess = processOrMain(launchProcess, packageName);
        this.applicationClass = applicationClass;
        this.serviceClass = serviceClass;
        this.serviceProcess = processOrMain(serviceProcess, packageName);
        this.receiverClass = receiverClass;
        this.receiverProcess = processOrMain(receiverProcess, packageName);
        this.receiverAction = receiverAction;
        this.providerClass = providerClass;
        this.providerProcess = processOrMain(providerProcess, packageName);
        this.providerAuthority = providerAuthority;
        this.permissions = permissions;
        this.sha256 = sha256;
        this.importedAt = importedAt;
        this.lastProbeStatus = lastProbeStatus;
        this.lastProbeAt = lastProbeAt;
    }

    SandboxRecord withStoragePaths(String newApkPath, String newNativeLibraryDir) {
        return new SandboxRecord(packageName, label, versionName, versionCode,
                signatureSha256, newApkPath, newNativeLibraryDir, launchActivity,
                launchProcess, applicationClass, serviceClass, serviceProcess,
                receiverClass, receiverProcess, receiverAction, providerClass,
                providerProcess, providerAuthority, permissions, sha256, importedAt,
                lastProbeStatus, lastProbeAt);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("packageName", packageName).put("label", label).put("versionName", versionName)
                .put("versionCode", versionCode).put("signatureSha256", signatureSha256)
                .put("apkPath", apkPath).put("nativeLibraryDir", nativeLibraryDir).put("launchActivity", launchActivity)
                .put("launchProcess", launchProcess).put("applicationClass", applicationClass)
                .put("serviceClass", serviceClass).put("serviceProcess", serviceProcess)
                .put("receiverClass", receiverClass).put("receiverProcess", receiverProcess)
                .put("receiverAction", receiverAction).put("providerClass", providerClass)
                .put("providerProcess", providerProcess).put("providerAuthority", providerAuthority)
                .put("permissions", permissions).put("sha256", sha256).put("importedAt", importedAt)
                .put("lastProbeStatus", lastProbeStatus).put("lastProbeAt", lastProbeAt);
    }

    static SandboxRecord fromJson(JSONObject o) throws JSONException {
        String packageName = o.getString("packageName");
        return new SandboxRecord(packageName, o.optString("label"), o.optString("versionName"),
                o.optLong("versionCode"), o.optString("signatureSha256"),
                o.getString("apkPath"), o.optString("nativeLibraryDir"), o.optString("launchActivity"),
                o.optString("launchProcess", packageName), o.optString("applicationClass"),
                o.optString("serviceClass"), o.optString("serviceProcess", packageName),
                o.optString("receiverClass"), o.optString("receiverProcess", packageName),
                o.optString("receiverAction"), o.optString("providerClass"),
                o.optString("providerProcess", packageName), o.optString("providerAuthority"),
                o.optString("permissions"), o.optString("sha256"), o.optLong("importedAt"),
                o.optString("lastProbeStatus", "NOT_TESTED"), o.optLong("lastProbeAt"));
    }

    private static String processOrMain(String processName, String packageName) {
        return processName == null || processName.trim().isEmpty() ? packageName : processName;
    }
}
