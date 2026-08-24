package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SX business adapter. UI calls this class; runtime internals stop here. */
final class SxSandboxAdapter implements SandboxSdk {
    private final Context context;
    private final PackageServiceClient packageService;
    private final RuntimeClient runtime;

    SxSandboxAdapter(Context context) {
        this.context = context.getApplicationContext();
        packageService = new PackageServiceClient(this.context);
        runtime = new RuntimeClient(this.context);
    }

    SandboxCatalogState load() throws Exception { return packageService.load(); }
    SandboxRecord findRecord(String packageName) throws Exception { return packageService.findRecord(packageName); }
    SandboxRecord importApk(Uri uri) throws Exception { return packageService.importApk(uri); }
    SandboxRecord importApk(Uri uri, String nativeGuestTrust) throws Exception {
        return packageService.importApk(uri, nativeGuestTrust);
    }
    List<InstalledApplication> installedApplications() throws Exception {
        PackageManager packageManager = context.getPackageManager();
        SandboxCatalogState state = packageService.load();
        List<InstalledApplication> applications = new ArrayList<>();
        List<ApplicationInfo> installed = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA);
        for (ApplicationInfo application : installed) {
            if (!isCloneCandidate(application, packageManager)) continue;
            PackageInfo info;
            try {
                int flags = Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                info = packageManager.getPackageInfo(application.packageName, flags);
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }
            int instanceCount = 0;
            for (com.warden.controlledsandbox.SandboxInstance instance : state.instances()) {
                if (application.packageName.equals(instance.packageName)) instanceCount++;
            }
            applications.add(InstalledApplication.from(application, info, packageManager, instanceCount));
        }
        applications.sort(Comparator.comparing((InstalledApplication item) -> item.label,
                String.CASE_INSENSITIVE_ORDER).thenComparing(item -> item.packageName));
        return applications;
    }

    private boolean isCloneCandidate(ApplicationInfo application, PackageManager packageManager) {
        String packageName = application.packageName == null ? "" : application.packageName;
        if (packageName.isEmpty() || packageName.equals(context.getPackageName())
                || packageName.equals("com.warden.controlledsandbox.companion32")
                || packageName.equals("com.warden.controlledsandbox.companion32.debug")) return false;
        if ((application.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
        if (application.sourceDir == null || !new java.io.File(application.sourceDir).isFile()) return false;
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) {
                if (split == null || !new java.io.File(split).isFile()) return false;
            }
        }
        return packageManager.getLaunchIntentForPackage(packageName) != null;
    }
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
    com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot resetNetworkServiceProfile(
            String packageName, int virtualUserId) throws Exception {
        return packageService.resetNetworkServiceProfile(packageName, virtualUserId);
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
        try {
            if (source == null || source.trim().isEmpty()) {
                return SandboxOperationResult.failure("importPackage", "SOURCE_REQUIRED",
                        "APK source is required", null, Map.of());
            }
            SandboxRecord record = source.startsWith("content://")
                    ? packageService.importApk(Uri.parse(source))
                    : packageService.importApkFile(new File(source));
            return SandboxOperationResult.success("importPackage", "IMPORTED",
                    identityFor(record, 0), diagnostics(record));
        } catch (Exception error) {
            return SandboxOperationResult.failure("importPackage", code("IMPORT_FAILED", error),
                    String.valueOf(error.getMessage()), null, Map.of());
        }
    }

    @Override public SandboxOperationResult importInstalledApplication(String packageName,
            String nativeGuestTrust) throws Exception {
        return importInstalledApplication(packageName, nativeGuestTrust,
                java.util.UUID.randomUUID().toString());
    }

    @Override public SandboxOperationResult importInstalledApplication(String packageName,
            String nativeGuestTrust, String requestId) throws Exception {
        try {
            if (packageName == null || packageName.trim().isEmpty()) {
                return SandboxOperationResult.failure("importInstalledApplication",
                        "PACKAGE_REQUIRED", "packageName is required", null, Map.of());
            }
            PackageImportResult imported = packageService.importInstalledApplicationAndEnsure(
                    requestId, packageName, nativeGuestTrust == null ? "" : nativeGuestTrust, 0);
            SandboxRecord record = imported.record();
            return SandboxOperationResult.success("importInstalledApplication", "IMPORTED",
                    identityFor(record, 0), operationDiagnostics(record,
                            imported.operationTraceJson()));
        } catch (PackageMutationFailureException error) {
            return SandboxOperationResult.failure("importInstalledApplication", error.code,
                    String.valueOf(error.getMessage()), null,
                    operationDiagnostics(null, error.operationTraceJson));
        } catch (Exception error) {
            String code = code("IMPORT_FAILED", error);
            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("requestId", requestId == null ? "" : requestId);
            diagnostics.put("stage", "BIND");
            diagnostics.put("attempt", "1");
            diagnostics.put("retryBudget", "0");
            diagnostics.put("retryable", Boolean.toString(
                    "PACKAGE_SERVICE_UNAVAILABLE".equals(code)));
            return SandboxOperationResult.failure("importInstalledApplication",
                    code, String.valueOf(error.getMessage()), null, diagnostics);
        }
    }

    @Override public SandboxOperationResult ensureInstance(String packageName, int virtualUserId)
            throws Exception {
        try {
            if (packageName == null || packageName.trim().isEmpty()) {
                return SandboxOperationResult.failure("ensureInstance", "PACKAGE_REQUIRED",
                        "packageName is required", null, Map.of());
            }
            packageService.ensureInstance(packageName, virtualUserId);
            SandboxRecord record = packageService.findRecord(packageName);
            if (record == null) {
                return SandboxOperationResult.failure("ensureInstance", "PACKAGE_NOT_INSTALLED",
                        "Package is not installed: " + packageName, null, Map.of());
            }
            return SandboxOperationResult.success("ensureInstance", "READY",
                    identityFor(record, virtualUserId), Map.of());
        } catch (Exception error) {
            return SandboxOperationResult.failure("ensureInstance", code("ENSURE_FAILED", error),
                    String.valueOf(error.getMessage()), null, Map.of());
        }
    }

    @Override public SandboxOperationResult cloneInstance(String packageName) throws Exception {
        int userId = -1;
        try {
            if (packageName == null || packageName.trim().isEmpty()) {
                return SandboxOperationResult.failure("cloneInstance", "PACKAGE_REQUIRED",
                        "packageName is required", null, Map.of());
            }
            userId = packageService.createClone(packageName);
            SandboxRecord record = packageService.findRecord(packageName);
            if (record == null) {
                rollbackClone(packageName, userId);
                return SandboxOperationResult.failure("cloneInstance", "CLONE_FAILED",
                        "clone succeeded without a package record", null,
                        Map.of("virtualUserId", Integer.toString(userId)));
            }
            return SandboxOperationResult.success("cloneInstance", "CREATED",
                    identityFor(record, userId), Map.of("virtualUserId", Integer.toString(userId)));
        } catch (Exception error) {
            rollbackClone(packageName, userId);
            return SandboxOperationResult.failure("cloneInstance", code("CLONE_FAILED", error),
                    String.valueOf(error.getMessage()), null, Map.of());
        }
    }

    @Override public SandboxOperationResult launch(SandboxIdentity identity) throws Exception {
        try {
            SandboxRecord record = requireRecord(identity);
            if (record == null) {
                return SandboxOperationResult.failure("launch", identity == null
                                ? "IDENTITY_REQUIRED" : "PACKAGE_NOT_INSTALLED",
                        identity == null ? "identity is required"
                                : "Package is not installed: " + identity.packageName(),
                        identity, Map.of());
            }
            return bundleResult("launch", identity, runtime.launch(record, identity.virtualUserId()));
        } catch (Exception error) {
            return SandboxOperationResult.failure("launch", code("LAUNCH_FAILED", error),
                    String.valueOf(error.getMessage()), identity, Map.of());
        }
    }

    @Override public SandboxOperationResult stop(SandboxIdentity identity) throws Exception {
        try {
            SandboxRecord record = requireRecord(identity);
            if (record == null) {
                return SandboxOperationResult.failure("stop", identity == null
                                ? "IDENTITY_REQUIRED" : "PACKAGE_NOT_INSTALLED",
                        identity == null ? "identity is required"
                                : "Package is not installed: " + identity.packageName(),
                        identity, Map.of());
            }
            runtime.stop(record, identity.virtualUserId());
            return SandboxOperationResult.success("stop", "STOPPED", identity, Map.of());
        } catch (Exception error) {
            return SandboxOperationResult.failure("stop", code("STOP_FAILED", error),
                    String.valueOf(error.getMessage()), identity, Map.of());
        }
    }

    @Override public SandboxOperationResult stopAll() throws Exception {
        try {
            SandboxCatalogState state = packageService.load();
            int stopped = 0;
            int failed = 0;
            for (com.warden.controlledsandbox.SandboxInstance instance : state.instances()) {
                SandboxRecord record = packageService.findRecord(instance.packageName);
                if (record == null) {
                    failed++;
                    continue;
                }
                try {
                    runtime.stop(record, instance.virtualUserId);
                    stopped++;
                } catch (Exception ignored) {
                    failed++;
                }
            }
            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("stopped", Integer.toString(stopped));
            diagnostics.put("failed", Integer.toString(failed));
            if (failed > 0) {
                return SandboxOperationResult.failure("stopAll", "STOP_FAILED",
                        "one or more instances failed to stop", null, diagnostics);
            }
            return SandboxOperationResult.success("stopAll", "STOPPED", null, diagnostics);
        } catch (Exception error) {
            return SandboxOperationResult.failure("stopAll", code("STOP_FAILED", error),
                    String.valueOf(error.getMessage()), null, Map.of());
        }
    }

    @Override public SandboxOperationResult clearData(SandboxIdentity identity) throws Exception {
        try {
            if (identity == null) {
                return SandboxOperationResult.failure("clearData", "IDENTITY_REQUIRED",
                        "identity is required", null, Map.of());
            }
            packageService.clearInstanceData(identity.packageName(), identity.virtualUserId());
            return SandboxOperationResult.success("clearData", "CLEARED", identity, Map.of());
        } catch (Exception error) {
            return SandboxOperationResult.failure("clearData", code("CLEAR_FAILED", error),
                    String.valueOf(error.getMessage()), identity, Map.of());
        }
    }

    @Override public SandboxOperationResult deleteInstance(SandboxIdentity identity) throws Exception {
        try {
            if (identity == null) {
                return SandboxOperationResult.failure("deleteInstance", "IDENTITY_REQUIRED",
                        "identity is required", null, Map.of());
            }
            // PackageManagementSession is the single lifecycle authority.  It performs the stop
            // barrier and data/catalog transaction atomically for every caller, including SDK calls.
            packageService.deleteInstance(identity.packageName(), identity.virtualUserId());
            return SandboxOperationResult.success("deleteInstance", "DELETED", identity, Map.of());
        } catch (Exception error) {
            return SandboxOperationResult.failure("deleteInstance", code("DELETE_FAILED", error),
                    String.valueOf(error.getMessage()), identity, Map.of());
        }
    }

    @Override public SandboxOperationResult status() throws Exception {
        try {
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
        } catch (Exception error) {
            return SandboxOperationResult.failure("status", code("STATUS_FAILED", error),
                    String.valueOf(error.getMessage()), null, Map.of());
        }
    }

    private SandboxRecord requireRecord(SandboxIdentity identity) throws Exception {
        if (identity == null) return null;
        return packageService.findRecord(identity.packageName());
    }

    private void rollbackClone(String packageName, int userId) {
        if (packageName == null || packageName.isBlank() || userId < 0) return;
        try {
            packageService.deleteInstance(packageName, userId);
        } catch (Exception ignored) {
        }
    }

    private static String code(String fallback, Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("not installed")) return "PACKAGE_NOT_INSTALLED";
        if (message.contains("required")) return "SOURCE_REQUIRED";
        if (message.contains("Package management service is unavailable")) {
            return "PACKAGE_SERVICE_UNAVAILABLE";
        }
        int separator = message.indexOf(':');
        String prefix = separator < 0 ? message : message.substring(0, separator);
        if (prefix.matches("[A-Z][A-Z0-9_]+")) return prefix;
        return fallback;
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

    private Map<String, String> operationDiagnostics(SandboxRecord record, String traceJson) {
        Map<String, String> values = new LinkedHashMap<>();
        if (record != null) values.putAll(diagnostics(record));
        String raw = traceJson == null ? "" : traceJson;
        values.put("operationTrace", raw);
        if (!raw.isEmpty()) {
            try {
                org.json.JSONObject trace = new org.json.JSONObject(raw);
                values.put("requestId", trace.optString("requestId", ""));
                values.put("operationId", trace.optString("operationId", ""));
                values.put("stage", trace.optString("stage", ""));
                values.put("elapsedMs", Long.toString(trace.optLong("elapsedMs", -1L)));
                values.put("attempt", Integer.toString(trace.optInt("attempt", 1)));
                values.put("retryBudget", Integer.toString(trace.optInt("retryBudget", 0)));
                values.put("retryable", Boolean.toString(trace.optBoolean("retryable", false)));
                values.put("stageTimingsMs", trace.optJSONObject("stageTimingsMs") == null
                        ? "{}" : trace.optJSONObject("stageTimingsMs").toString());
            } catch (org.json.JSONException malformed) {
                values.put("traceParseError", "MALFORMED_OPERATION_TRACE");
            }
        }
        return values;
    }

    @Override public void close() {
        runtime.close();
        packageService.close();
    }
}
