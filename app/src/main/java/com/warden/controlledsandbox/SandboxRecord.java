package com.warden.controlledsandbox;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
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
    final String sharedLibraries;
    /** Immutable package-revision digest. Equal to baseApkSha256 for legacy single APKs. */
    final String sha256;
    final String baseApkSha256;
    final List<PackageArtifactRecord> artifacts;
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
        this(packageName, label, versionName, versionCode, signatureSha256, apkPath,
                nativeLibraryDir, launchActivity, launchProcess, applicationClass,
                serviceClass, serviceProcess, receiverClass, receiverProcess, receiverAction,
                providerClass, providerProcess, providerAuthority, permissions, "", sha256,
                sha256, List.of(PackageArtifactRecord.legacyBase(apkPath, sha256)), importedAt,
                lastProbeStatus, lastProbeAt);
    }

    SandboxRecord(String packageName, String label, String versionName, long versionCode,
                  String signatureSha256, String apkPath, String nativeLibraryDir,
                  String launchActivity, String launchProcess, String applicationClass,
                  String serviceClass, String serviceProcess, String receiverClass,
                  String receiverProcess, String receiverAction, String providerClass,
                  String providerProcess, String providerAuthority, String permissions,
                  String sharedLibraries, String revisionSha256, String baseApkSha256,
                  List<PackageArtifactRecord> artifacts, long importedAt,
                  String lastProbeStatus, long lastProbeAt) {
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
        this.permissions = permissions == null ? "" : permissions;
        this.sharedLibraries = sharedLibraries == null ? "" : sharedLibraries;
        this.sha256 = revisionSha256;
        this.baseApkSha256 = baseApkSha256;
        ArrayList<PackageArtifactRecord> copy = new ArrayList<>(artifacts == null ? List.of() : artifacts);
        if (copy.isEmpty()) copy.add(PackageArtifactRecord.legacyBase(apkPath, baseApkSha256));
        this.artifacts = PackageArtifactOrder.runtimeOrder(copy);
        this.importedAt = importedAt;
        this.lastProbeStatus = lastProbeStatus;
        this.lastProbeAt = lastProbeAt;
    }

    SandboxRecord withStoragePaths(String newApkPath, String newNativeLibraryDir) {
        List<PackageArtifactRecord> moved = new ArrayList<>();
        for (PackageArtifactRecord artifact : artifacts) {
            String newPath = artifact.base() ? newApkPath : artifact.path;
            moved.add(artifact.withPath(newPath));
        }
        return withStorage(newApkPath, newNativeLibraryDir, moved);
    }

    SandboxRecord withStorage(String newApkPath, String newNativeLibraryDir,
                              List<PackageArtifactRecord> newArtifacts) {
        return new SandboxRecord(packageName, label, versionName, versionCode,
                signatureSha256, newApkPath, newNativeLibraryDir, launchActivity,
                launchProcess, applicationClass, serviceClass, serviceProcess,
                receiverClass, receiverProcess, receiverAction, providerClass,
                providerProcess, providerAuthority, permissions, sharedLibraries, sha256,
                baseApkSha256, newArtifacts, importedAt, lastProbeStatus, lastProbeAt);
    }

    List<String> splitApkPaths() {
        List<String> values = new ArrayList<>();
        for (PackageArtifactRecord artifact : artifacts) if (!artifact.base()) values.add(artifact.path);
        return values;
    }

    List<String> splitNames() {
        List<String> values = new ArrayList<>();
        for (PackageArtifactRecord artifact : artifacts) if (!artifact.base()) values.add(artifact.splitName);
        return values;
    }

    JSONObject toJson() throws JSONException {
        JSONArray artifactArray = new JSONArray();
        for (PackageArtifactRecord artifact : artifacts) artifactArray.put(artifact.toJson());
        return new JSONObject().put("packageName", packageName).put("label", label).put("versionName", versionName)
                .put("versionCode", versionCode).put("signatureSha256", signatureSha256)
                .put("apkPath", apkPath).put("nativeLibraryDir", nativeLibraryDir).put("launchActivity", launchActivity)
                .put("launchProcess", launchProcess).put("applicationClass", applicationClass)
                .put("serviceClass", serviceClass).put("serviceProcess", serviceProcess)
                .put("receiverClass", receiverClass).put("receiverProcess", receiverProcess)
                .put("receiverAction", receiverAction).put("providerClass", providerClass)
                .put("providerProcess", providerProcess).put("providerAuthority", providerAuthority)
                .put("permissions", permissions).put("sharedLibraries", sharedLibraries)
                .put("sha256", sha256).put("baseApkSha256", baseApkSha256)
                .put("artifacts", artifactArray).put("importedAt", importedAt)
                .put("lastProbeStatus", lastProbeStatus).put("lastProbeAt", lastProbeAt);
    }

    static SandboxRecord fromJson(JSONObject o) throws JSONException {
        String packageName = o.getString("packageName");
        String apkPath = o.getString("apkPath");
        String revisionSha = o.optString("sha256");
        String baseSha = o.optString("baseApkSha256", revisionSha);
        List<PackageArtifactRecord> artifacts = new ArrayList<>();
        JSONArray artifactArray = o.optJSONArray("artifacts");
        if (artifactArray != null) {
            for (int index = 0; index < artifactArray.length(); index++) {
                artifacts.add(PackageArtifactRecord.fromJson(artifactArray.getJSONObject(index)));
            }
        }
        if (artifacts.isEmpty()) artifacts.add(PackageArtifactRecord.legacyBase(apkPath, baseSha));
        return new SandboxRecord(packageName, o.optString("label"), o.optString("versionName"),
                o.optLong("versionCode"), o.optString("signatureSha256"), apkPath,
                o.optString("nativeLibraryDir"), o.optString("launchActivity"),
                o.optString("launchProcess", packageName), o.optString("applicationClass"),
                o.optString("serviceClass"), o.optString("serviceProcess", packageName),
                o.optString("receiverClass"), o.optString("receiverProcess", packageName),
                o.optString("receiverAction"), o.optString("providerClass"),
                o.optString("providerProcess", packageName), o.optString("providerAuthority"),
                o.optString("permissions"), o.optString("sharedLibraries"), revisionSha,
                baseSha, artifacts, o.optLong("importedAt"),
                o.optString("lastProbeStatus", "NOT_TESTED"), o.optLong("lastProbeAt"));
    }

    private static String processOrMain(String processName, String packageName) {
        return processName == null || processName.trim().isEmpty() ? packageName : processName;
    }
}
