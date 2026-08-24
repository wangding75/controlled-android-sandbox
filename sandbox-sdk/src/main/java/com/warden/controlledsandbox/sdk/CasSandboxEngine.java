package com.warden.controlledsandbox.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SX {@code SandboxEngine} mapping onto {@link SandboxSdk}.
 * Catalog and runtime status are always re-read from the SDK; this class does not
 * own package, user, or process authority.
 */
public final class CasSandboxEngine {
    public static final String PACKAGE_REQUIRED = "PACKAGE_REQUIRED";
    public static final String PACKAGE_NOT_INSTALLED = "PACKAGE_NOT_INSTALLED";
    public static final String IDENTITY_REQUIRED = "IDENTITY_REQUIRED";
    public static final String CLONE_FAILED = "CLONE_FAILED";
    public static final String SOURCE_REQUIRED = "SOURCE_REQUIRED";

    private final SandboxSdk sdk;
    private final List<SandboxEngineObserver> observers = new CopyOnWriteArrayList<>();

    public CasSandboxEngine(SandboxSdk sdk) {
        this.sdk = Objects.requireNonNull(sdk, "sdk");
    }

    public void addObserver(SandboxEngineObserver observer) {
        if (observer != null) observers.add(observer);
    }

    public void removeObserver(SandboxEngineObserver observer) {
        observers.remove(observer);
    }

    public SandboxOperationResult initialize() throws Exception {
        return publish(sdk.status());
    }

    /** DELETE for CAS: no BlackBox ClassLoader hook. */
    public SandboxOperationResult onAttachBaseContext() {
        return publish(SandboxOperationResult.success("onAttachBaseContext", "NO_OP_CAS_HOST",
                null, Map.of("disposition", "DELETE")));
    }

    public SandboxOperationResult onAppCreate() throws Exception {
        return publish(sdk.status());
    }

    public boolean isReady() throws Exception {
        SandboxOperationResult status = sdk.status();
        return status.successful();
    }

    public SandboxOperationResult installFromHost(String packageName, String nativeGuestTrust)
            throws Exception {
        return installFromHost(packageName, nativeGuestTrust,
                java.util.UUID.randomUUID().toString());
    }

    public SandboxOperationResult installFromHost(String packageName, String nativeGuestTrust,
            String requestId) throws Exception {
        if (blank(packageName)) {
            return publish(SandboxOperationResult.failure("installFromHost", PACKAGE_REQUIRED,
                    "packageName is required", null, Map.of()));
        }
        SandboxOperationResult imported = sdk.importInstalledApplication(packageName,
                nativeGuestTrust == null ? "" : nativeGuestTrust,
                requestId == null ? "" : requestId);
        if (!imported.successful()) return publish(imported);
        // The production CAS adapter publishes record + initial instance in one Package Service
        // transaction and proves that with an operation trace. Keep the legacy ensure fallback
        // only for SDK implementations that do not yet expose that combined contract.
        if (!imported.diagnostics().getOrDefault("operationTrace", "").isEmpty()) {
            return publish(imported);
        }
        SandboxOperationResult ready = sdk.ensureInstance(packageName, 0);
        return publish(ready.successful() ? imported : ready);
    }

    public SandboxOperationResult installFromApk(String apkPath) throws Exception {
        if (blank(apkPath)) {
            return publish(SandboxOperationResult.failure("installFromApk", SOURCE_REQUIRED,
                    "APK source is required", null, Map.of()));
        }
        return publish(sdk.importPackage(apkPath));
    }

    public SandboxOperationResult uninstall(String packageName, int userId) throws Exception {
        SandboxIdentity identity = lookupIdentity(packageName, userId);
        if (identity == null) {
            return publish(SandboxOperationResult.failure("uninstall", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of("packageName",
                            packageName == null ? "" : packageName,
                            "virtualUserId", Integer.toString(userId))));
        }
        return publish(sdk.deleteInstance(identity));
    }

    public SandboxOperationResult clearData(String packageName, int userId) throws Exception {
        SandboxIdentity identity = lookupIdentity(packageName, userId);
        if (identity == null) {
            return publish(SandboxOperationResult.failure("clearData", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of()));
        }
        return publish(sdk.clearData(identity));
    }

    public List<SandboxInstance> listInstalled() throws Exception {
        return List.copyOf(sdk.catalog().instances());
    }

    public SandboxInstance get(String packageName, int userId) throws Exception {
        for (SandboxInstance instance : sdk.catalog().instances()) {
            if (instance.packageName().equals(packageName) && instance.virtualUserId() == userId) {
                return instance;
            }
        }
        return null;
    }

    public boolean isInstalled(String packageName, int userId) throws Exception {
        return get(packageName, userId) != null;
    }

    public SandboxOperationResult launch(String packageName, int userId) throws Exception {
        SandboxIdentity identity = lookupIdentity(packageName, userId);
        if (identity == null) {
            return publish(SandboxOperationResult.failure("launch", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of("packageName",
                            packageName == null ? "" : packageName,
                            "virtualUserId", Integer.toString(userId))));
        }
        return publish(sdk.launch(identity));
    }

    public SandboxOperationResult kill(String packageName, int userId) throws Exception {
        SandboxIdentity identity = lookupIdentity(packageName, userId);
        if (identity == null) {
            return publish(SandboxOperationResult.failure("kill", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of()));
        }
        return publish(sdk.stop(identity));
    }

    public SandboxOperationResult killAll() throws Exception {
        return publish(sdk.stopAll());
    }

    public SandboxOperationResult clone(String packageName) throws Exception {
        if (blank(packageName)) {
            return publish(SandboxOperationResult.failure("clone", PACKAGE_REQUIRED,
                    "packageName is required", null, Map.of()));
        }
        SandboxCatalog before = sdk.catalog();
        SandboxOperationResult result = sdk.cloneInstance(packageName);
        if (!result.successful()) {
            rollbackNewInstances(packageName, before);
            if (result.errorCode().isEmpty()) {
                result = SandboxOperationResult.failure("clone", CLONE_FAILED,
                        result.errorMessage(), result.identity(), result.diagnostics());
            }
        }
        return publish(result);
    }

    public SandboxOperationResult createShortcut(String packageName, int userId) throws Exception {
        SandboxIdentity identity = lookupIdentity(packageName, userId);
        if (identity == null) {
            return publish(SandboxOperationResult.failure("createShortcut", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of()));
        }
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("instanceId", identity.instanceId());
        diagnostics.put("shortcutAction", "LAUNCH_INSTANCE");
        diagnostics.put("packageName", identity.packageName());
        diagnostics.put("virtualUserId", Integer.toString(identity.virtualUserId()));
        return publish(SandboxOperationResult.success("createShortcut", "IDENTITY_READY",
                identity, diagnostics));
    }

    public SandboxOperationResult setDisplayName(String packageName, int userId, String name)
            throws Exception {
        SandboxInstance instance = get(packageName, userId);
        if (instance == null) {
            return publish(SandboxOperationResult.failure("setDisplayName", PACKAGE_NOT_INSTALLED,
                    "instance is not installed", null, Map.of()));
        }
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("requestedName", name == null ? "" : name);
        diagnostics.put("currentDisplayName", instance.displayName());
        diagnostics.put("persistence", "C4-T03");
        return publish(SandboxOperationResult.success("setDisplayName",
                "NOT_PERSISTED_UNTIL_C4_T03", lookupIdentity(packageName, userId), diagnostics));
    }

    public SandboxOperationResult status() throws Exception {
        return publish(sdk.status());
    }

    public SandboxCatalog catalog() throws Exception {
        return sdk.catalog();
    }

    private SandboxIdentity lookupIdentity(String packageName, int userId) throws Exception {
        if (blank(packageName)) return null;
        SandboxCatalog catalog = sdk.catalog();
        SandboxPackage pkg = null;
        for (SandboxPackage candidate : catalog.packages()) {
            if (candidate.packageName().equals(packageName)) {
                pkg = candidate;
                break;
            }
        }
        SandboxInstance instance = null;
        for (SandboxInstance candidate : catalog.instances()) {
            if (candidate.packageName().equals(packageName) && candidate.virtualUserId() == userId) {
                instance = candidate;
                break;
            }
        }
        if (pkg == null || instance == null) return null;
        return instance.identity(pkg);
    }

    private void rollbackNewInstances(String packageName, SandboxCatalog before) {
        try {
            List<String> known = new ArrayList<>();
            for (SandboxInstance instance : before.instances()) {
                if (packageName.equals(instance.packageName())) {
                    known.add(instance.packageName() + "#" + instance.virtualUserId());
                }
            }
            for (SandboxInstance instance : sdk.catalog().instances()) {
                if (!packageName.equals(instance.packageName())) continue;
                String key = instance.packageName() + "#" + instance.virtualUserId();
                if (known.contains(key)) continue;
                SandboxIdentity identity = lookupIdentity(instance.packageName(),
                        instance.virtualUserId());
                if (identity != null) sdk.deleteInstance(identity);
            }
        } catch (Exception ignored) {
            // Rollback is best-effort; the failed clone result remains the caller-visible status.
        }
    }

    private SandboxOperationResult publish(SandboxOperationResult result) {
        SandboxOperationResult value = result == null
                ? SandboxOperationResult.failure("engine", IDENTITY_REQUIRED,
                "engine returned no result", null, Map.of())
                : result;
        for (SandboxEngineObserver observer : observers) {
            try {
                observer.onOperation(value);
                observer.onCatalogChanged(sdk.catalog());
                observer.onStatusChanged(sdk.status());
            } catch (Exception ignored) {
            }
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
