package com.warden.controlledsandbox.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SandboxSdkSelfTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        SandboxIdentity first = SandboxIdentity.forInstance("com.example.app", 0,
                "com.example.app", 1, DIGEST);
        SandboxIdentity second = SandboxIdentity.forInstance("com.example.app", 1,
                "com.example.app", 1, DIGEST);
        require(!first.storageNamespace().equals(second.storageNamespace()), "instances share storage namespace");
        require(!first.androidIdentityProfile().equals(second.androidIdentityProfile()), "instances share identity profile");

        CompatibilityPatchRegistry registry = new CompatibilityPatchRegistry();
        registry.register(new CompatibilityPatch() {
            @Override public String id() { return "DINGTALK_PRIVATE_V7"; }
            @Override public boolean matches(CompatibilityContext context) {
                return "com.alibaba.android.rimet".equals(context.packageName())
                        && context.versionCode() >= 1178 && context.versionCode() <= 1178
                        && context.hasCapability("framework-routing");
            }
            @Override public String reason() { return "DingTalk private behavior requires evidence-backed gate"; }
            @Override public String whyNotGeneral() { return "Only the target app/version and capability set match"; }
        });
        CompatibilityContext context = new CompatibilityContext("com.alibaba.android.rimet", "7.8.10",
                1178, Set.of("framework-routing"));
        require(!registry.decide(context).enabled(), "patch must be disabled by default");
        registry.enable("DINGTALK_PRIVATE_V7");
        require(registry.decide(context).enabled(), "explicitly enabled patch did not match");

        FakeSdk sdk = new FakeSdk();
        CasSandboxEngine engine = new CasSandboxEngine(sdk);
        RecordingObserver observer = new RecordingObserver();
        engine.addObserver(observer);

        require(engine.onAttachBaseContext().status().equals("NO_OP_CAS_HOST"),
                "attach must remain a CAS no-op");
        require(engine.initialize().successful(), "initialize/status failed");
        require(engine.isReady(), "engine should be ready");
        require(engine.installFromHost("com.example.app", "UNTRUSTED").successful(),
                "installFromHost failed");
        require(engine.isInstalled("com.example.app", 0), "primary instance missing");
        require(engine.launch("com.example.app", 0).successful(), "launch failed");
        SandboxOperationResult cloned = engine.clone("com.example.app");
        require(cloned.successful(), "clone failed");
        require("1".equals(cloned.diagnostics().get("virtualUserId")), "clone user should be 1");
        require(engine.kill("com.example.app", 0).successful(), "kill failed");
        require(engine.killAll().successful(), "killAll failed");
        require(engine.clearData("com.example.app", 0).successful(), "clearData failed");
        require(engine.createShortcut("com.example.app", 0).successful(), "shortcut identity failed");
        SandboxOperationResult missing = engine.launch("com.missing.app", 0);
        require(!missing.successful(), "missing launch must fail");
        require(CasSandboxEngine.PACKAGE_NOT_INSTALLED.equals(missing.errorCode()),
                "missing launch must be diagnosable");

        sdk.failCloneAfterAdd = true;
        int before = sdk.instances.size();
        SandboxOperationResult failedClone = engine.clone("com.example.app");
        require(!failedClone.successful(), "failed clone should not succeed");
        require(sdk.instances.size() == before, "failed clone must roll back extra instances");
        require(observer.operations >= 8, "observer did not receive engine operations");
        require(observer.catalogs >= 8, "observer did not re-read catalog");
        System.out.println("PASS sandbox-sdk identity, compatibility, and CasSandboxEngine self-test");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingObserver implements SandboxEngineObserver {
        int operations;
        int catalogs;
        int statuses;

        @Override public void onOperation(SandboxOperationResult result) { operations++; }
        @Override public void onCatalogChanged(SandboxCatalog catalog) { catalogs++; }
        @Override public void onStatusChanged(SandboxOperationResult status) { statuses++; }
    }

    private static final class FakeSdk implements SandboxSdk {
        private final List<SandboxPackage> packages = new ArrayList<>();
        private final List<SandboxInstance> instances = new ArrayList<>();
        boolean failCloneAfterAdd;

        @Override public SandboxCatalog catalog() {
            return new SandboxCatalog(packages, instances, "");
        }

        @Override public SandboxOperationResult importPackage(String source) {
            return importInstalledApplication("com.example.app", "");
        }

        @Override public SandboxOperationResult importInstalledApplication(String packageName,
                String nativeGuestTrust) {
            SandboxPackage pkg = new SandboxPackage(packageName, packageName, "1", 1, DIGEST,
                    "", "", false);
            if (packages.stream().noneMatch(item -> item.packageName().equals(packageName))) {
                packages.add(pkg);
            }
            ensure(packageName, 0);
            return SandboxOperationResult.success("importInstalledApplication", "IMPORTED",
                    identity(packageName, 0), Map.of());
        }

        @Override public SandboxOperationResult ensureInstance(String packageName, int virtualUserId) {
            ensure(packageName, virtualUserId);
            return SandboxOperationResult.success("ensureInstance", "READY",
                    identity(packageName, virtualUserId), Map.of());
        }

        @Override public SandboxOperationResult cloneInstance(String packageName) {
            int next = instances.stream()
                    .filter(item -> item.packageName().equals(packageName))
                    .mapToInt(SandboxInstance::virtualUserId)
                    .max().orElse(-1) + 1;
            ensure(packageName, next);
            if (failCloneAfterAdd) {
                return SandboxOperationResult.failure("cloneInstance", "CLONE_FAILED",
                        "injected clone failure", identity(packageName, next),
                        Map.of("virtualUserId", Integer.toString(next)));
            }
            return SandboxOperationResult.success("cloneInstance", "CREATED",
                    identity(packageName, next), Map.of("virtualUserId", Integer.toString(next)));
        }

        @Override public SandboxOperationResult launch(SandboxIdentity identity) {
            return SandboxOperationResult.success("launch", "LAUNCH_PASS", identity,
                    Map.of("sessionId", "s1"));
        }

        @Override public SandboxOperationResult stop(SandboxIdentity identity) {
            return SandboxOperationResult.success("stop", "STOPPED", identity, Map.of());
        }

        @Override public SandboxOperationResult stopAll() {
            return SandboxOperationResult.success("stopAll", "STOPPED", null,
                    Map.of("stopped", Integer.toString(instances.size()), "failed", "0"));
        }

        @Override public SandboxOperationResult clearData(SandboxIdentity identity) {
            return SandboxOperationResult.success("clearData", "CLEARED", identity, Map.of());
        }

        @Override public SandboxOperationResult deleteInstance(SandboxIdentity identity) {
            instances.removeIf(item -> item.packageName().equals(identity.packageName())
                    && item.virtualUserId() == identity.virtualUserId());
            return SandboxOperationResult.success("deleteInstance", "DELETED", identity, Map.of());
        }

        @Override public SandboxOperationResult status() {
            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("runtimeStatus", "READY");
            return SandboxOperationResult.success("status", "READY", null, diagnostics);
        }

        @Override public void close() { }

        private void ensure(String packageName, int userId) {
            if (instances.stream().anyMatch(item -> item.packageName().equals(packageName)
                    && item.virtualUserId() == userId)) {
                return;
            }
            instances.add(new SandboxInstance(packageName, userId, "Instance " + userId,
                    0L, "", 0L));
        }

        private SandboxIdentity identity(String packageName, int userId) {
            return SandboxIdentity.forInstance(packageName, userId, packageName, 1, DIGEST);
        }
    }
}
