package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.session.SessionState;
import java.util.ArrayList;

public final class BrokerManifestReceiverRuntimeSelfTest {
    private BrokerManifestReceiverRuntimeSelfTest() { }

    public static void main(String[] args) throws Exception {
        testIndexResolveAndActivation();
        testPermissionAndUserIsolation();
        testGenerationBinding();
        testImplicitMatchingAndOrdering();
        testBroadcastPayloadLimit();
        testConcurrentResolution();
        System.out.println("PASS broker manifest Receiver runtime self-test");
    }

    private static void testIndexResolveAndActivation() {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(senderManifest(true), template("com.example.sender", 0));
        runtime.indexManifest(targetManifest(), template("com.example.target", 0));
        GuestSession sender = session("sender-session", "com.example.sender", 0,
                "com.example.sender", 0, 1);
        Bundle request = explicit("com.example.target", 0,
                "com.example.target.RemoteReceiver");
        BrokerManifestReceiverRuntime.Route route = runtime.routeExplicit(request, sender);
        require(route.requiresProcessStart(), "unbound Receiver must require process activation");
        Bundle activation = runtime.activationRequest(route);
        require("com.example.target:receiver".equals(activation.getString(RuntimeKeys.PROCESS_NAME, "")),
                "manifest Receiver process activation");
        require("com.example.target.RemoteReceiver".equals(
                activation.getString(RuntimeKeys.COMPONENT_CLASS, "")), "manifest Receiver component activation");
        require("u0:com.example.target#com.example.target:receiver".equals(
                activation.getString(RuntimeKeys.RECEIVER_ACTIVATION_KEY, "")),
                "deterministic manifest Receiver activation key");
        require(runtime.packageCount() == 2 && runtime.receiverCount() == 2,
                "manifest Receiver index counts");
    }

    private static void testPermissionAndUserIsolation() {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(senderManifest(false), template("com.example.sender", 0));
        runtime.indexManifest(targetManifest(), template("com.example.target", 0));
        GuestSession sender = session("sender-session", "com.example.sender", 0,
                "com.example.sender", 0, 1);
        boolean permissionDenied = false;
        try {
            runtime.routeExplicit(explicit("com.example.target", 0,
                    "com.example.target.RemoteReceiver"), sender);
        } catch (SecurityException expected) {
            permissionDenied = expected.getMessage().startsWith("MANIFEST_RECEIVER_PERMISSION_DENIED");
        }
        require(permissionDenied, "manifest Receiver permission bypass");
        boolean crossUserDenied = false;
        try {
            runtime.routeExplicit(explicit("com.example.target", 1,
                    "com.example.target.RemoteReceiver"), sender);
        } catch (SecurityException expected) {
            crossUserDenied = "RECEIVER_CROSS_USER_DENIED".equals(expected.getMessage());
        }
        require(crossUserDenied, "manifest Receiver cross-user bypass");
        boolean privateDenied = false;
        try {
            runtime.routeExplicit(explicit("com.example.target", 0,
                    "com.example.target.PrivateReceiver"), sender);
        } catch (SecurityException expected) {
            privateDenied = "MANIFEST_RECEIVER_NOT_EXPORTED".equals(expected.getMessage());
        }
        require(privateDenied, "manifest Receiver exported policy bypass");
    }

    private static void testGenerationBinding() {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(senderManifest(true), template("com.example.sender", 0));
        runtime.indexManifest(targetManifest(), template("com.example.target", 0));
        GuestSession sender = session("sender-session", "com.example.sender", 0,
                "com.example.sender", 0, 1);
        GuestSession targetV1 = session("target-session", "com.example.target", 0,
                "com.example.target:receiver", 1, 1);
        runtime.bindSession(targetV1);
        BrokerManifestReceiverRuntime.Route route = runtime.routeExplicit(
                explicit("com.example.target", 0, "com.example.target.RemoteReceiver"), sender);
        require(!route.requiresProcessStart()
                        && route.resolution().binding().get().generation() == 1,
                "manifest Receiver generation binding");
        require(runtime.removeSessionCount(targetV1) == 1, "stale Receiver binding cleanup count");
        require(runtime.routeExplicit(explicit("com.example.target", 0,
                "com.example.target.RemoteReceiver"), sender).requiresProcessStart(),
                "stale manifest Receiver binding retained");
        GuestSession targetV2 = session("target-session", "com.example.target", 0,
                "com.example.target:receiver", 1, 2);
        runtime.bindSession(targetV2);
        route = runtime.routeExplicit(explicit("com.example.target", 0,
                "com.example.target.RemoteReceiver"), sender);
        require(route.resolution().binding().get().generation() == 2,
                "manifest Receiver recovery generation");
    }

    private static void testImplicitMatchingAndOrdering() throws Exception {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(senderManifest(true), template("com.example.sender", 0));
        runtime.indexManifest(implicitTargetManifest("com.example.high", "HighReceiver", 600, true),
                template("com.example.high", 0));
        runtime.indexManifest(implicitTargetManifest("com.example.low", "LowReceiver", 20, true),
                template("com.example.low", 0));
        runtime.indexManifest(implicitTargetManifest("com.example.no.permission", "DeniedReceiver", 900, false),
                template("com.example.no.permission", 0));
        GuestSession sender = session("sender-session", "com.example.sender", 0,
                "com.example.sender", 0, 1);
        Bundle request = implicit("com.example.DATA", "android.intent.category.DEFAULT",
                "content", "example.test", "/items/42", "text/plain");
        java.util.List<BrokerManifestReceiverRuntime.Route> routes = runtime.routeImplicit(request, sender);
        require(routes.size() == 2, "implicit Receiver permission filtering");
        require(routes.get(0).priority() == 600
                        && routes.get(0).receiver().className().endsWith("HighReceiver")
                        && routes.get(1).priority() == 20,
                "implicit Receiver priority ordering");
        require(routes.get(0).requiresProcessStart(), "implicit Receiver activation required");
        Bundle activation = runtime.activationRequest(routes.get(0));
        require(activation.getInt(RuntimeKeys.BROADCAST_PRIORITY, -1) == 600,
                "implicit Receiver activation priority");
        Bundle wrongCategory = implicit("com.example.DATA", "com.example.WRONG",
                "content", "example.test", "/items/42", "text/plain");
        require(runtime.routeImplicit(wrongCategory, sender).isEmpty(),
                "implicit Receiver category mismatch");
        Bundle wrongMime = implicit("com.example.DATA", "android.intent.category.DEFAULT",
                "content", "example.test", "/items/42", "image/png");
        require(runtime.routeImplicit(wrongMime, sender).isEmpty(),
                "implicit Receiver MIME mismatch");
        Bundle targetPackage = new Bundle(request);
        targetPackage.putString(RuntimeKeys.TARGET_PACKAGE_NAME, "com.example.low");
        routes = runtime.routeImplicit(targetPackage, sender);
        require(routes.size() == 1 && routes.get(0).receiver().packageName().equals("com.example.low"),
                "implicit Receiver package restriction");
        Bundle crossUser = new Bundle(request);
        crossUser.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, 1);
        boolean denied = false;
        try { runtime.routeImplicit(crossUser, sender); }
        catch (SecurityException expected) { denied = "RECEIVER_CROSS_USER_DENIED".equals(expected.getMessage()); }
        require(denied, "implicit Receiver cross-user bypass");
    }

    private static void testBroadcastPayloadLimit() {
        Bundle small = new Bundle();
        small.putString(ComponentOperations.ACTION, "com.example.SMALL");
        int bytes = BroadcastPayloadEstimator.requireWithinLimit(small);
        require(bytes > 0 && bytes < BroadcastPayloadEstimator.MAX_BROADCAST_BYTES,
                "broadcast payload estimate");
        Bundle large = new Bundle();
        large.putString(ComponentOperations.ACTION, "com.example.LARGE");
        large.putByteArray("payload", new byte[BroadcastPayloadEstimator.MAX_BROADCAST_BYTES]);
        boolean denied = false;
        try { BroadcastPayloadEstimator.requireWithinLimit(large); }
        catch (IllegalArgumentException expected) { denied = "BROADCAST_PAYLOAD_TOO_LARGE".equals(expected.getMessage()); }
        require(denied, "broadcast payload limit");
        Bundle root = new Bundle();
        Bundle cursor = root;
        for (int index = 0; index < 6; index++) {
            Bundle child = new Bundle();
            cursor.putBundle("nested", child);
            cursor = child;
        }
        boolean tooDeep = false;
        try { BroadcastPayloadEstimator.requireWithinLimit(root); }
        catch (IllegalArgumentException expected) { tooDeep = "BROADCAST_PAYLOAD_TOO_DEEP".equals(expected.getMessage()); }
        require(tooDeep, "broadcast payload depth limit");
    }

    private static void testConcurrentResolution() throws Exception {
        BrokerManifestReceiverRuntime runtime = new BrokerManifestReceiverRuntime();
        runtime.indexManifest(senderManifest(true), template("com.example.sender", 0));
        runtime.indexManifest(targetManifest(), template("com.example.target", 0));
        GuestSession sender = session("sender-session", "com.example.sender", 0,
                "com.example.sender", 0, 1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int index = 0; index < 64; index++) {
            futures.add(pool.submit(() -> runtime.activationRequest(runtime.routeExplicit(
                    explicit("com.example.target", 0, "com.example.target.RemoteReceiver"), sender))
                    .getString(RuntimeKeys.RECEIVER_ACTIVATION_KEY, "")));
        }
        pool.shutdown();
        require(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
                "manifest Receiver concurrent resolution timeout");
        for (java.util.concurrent.Future<String> future : futures) {
            require("u0:com.example.target#com.example.target:receiver".equals(future.get()),
                    "manifest Receiver activation is not deterministic");
        }
    }

    private static ManifestModel senderManifest(boolean permission) {
        ManifestModel model = new ManifestModel();
        model.packageName("com.example.sender");
        if (permission) model.addPermission("com.example.SEND_SECURE");
        return model;
    }

    private static ManifestModel targetManifest() {
        ManifestModel model = new ManifestModel();
        model.packageName("com.example.target");
        ManifestModel.Component remote = new ManifestModel.Component(
                "com.example.target.RemoteReceiver", ":receiver", true, true,
                false, "", "com.example.SEND_SECURE");
        remote.addAction("com.example.SECURE");
        model.addReceiver(remote);
        model.addReceiver(new ManifestModel.Component("com.example.target.PrivateReceiver",
                "", false, true, false, "", ""));
        return model;
    }

    private static ManifestModel implicitTargetManifest(String packageName, String receiverName,
                                                       int priority, boolean holdsRequiredPermission) {
        ManifestModel model = new ManifestModel();
        model.packageName(packageName);
        if (holdsRequiredPermission) model.addPermission("com.example.RECEIVE_SECURE");
        ManifestModel.Component receiver = new ManifestModel.Component(
                packageName + "." + receiverName, ":receiver", true, true, false, "", "");
        ManifestModel.IntentFilter filter = receiver.addIntentFilter(priority);
        filter.addAction("com.example.DATA");
        filter.addCategory("android.intent.category.DEFAULT");
        filter.addDataRule(new ManifestModel.DataRule("content", "example.test", "", "",
                "", ""));
        filter.addDataRule(new ManifestModel.DataRule("", "", "", "/items",
                "", "text/*"));
        model.addReceiver(receiver);
        return model;
    }

    private static Bundle implicit(String action, String category, String scheme, String host,
                                   String path, String mimeType) {
        Bundle bundle = new Bundle();
        bundle.putString(ComponentOperations.ACTION, action);
        ArrayList<String> categories = new ArrayList<>();
        categories.add(category);
        bundle.putStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES, categories);
        bundle.putString(RuntimeKeys.BROADCAST_SCHEME, scheme);
        bundle.putString(RuntimeKeys.BROADCAST_HOST, host);
        bundle.putString(RuntimeKeys.BROADCAST_PATH, path);
        bundle.putString(RuntimeKeys.BROADCAST_MIME_TYPE, mimeType);
        bundle.putString(RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION,
                "com.example.RECEIVE_SECURE");
        return bundle;
    }

    private static Bundle template(String packageName, int userId) {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        bundle.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
        bundle.putString(RuntimeKeys.APK_PATH, "/private/" + packageName + ".apk");
        bundle.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
        bundle.putString(RuntimeKeys.APPLICATION_CLASS, "");
        bundle.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>());
        return bundle;
    }

    private static Bundle explicit(String targetPackage, int targetUser, String receiverClass) {
        Bundle bundle = new Bundle();
        bundle.putString(RuntimeKeys.TARGET_PACKAGE_NAME, targetPackage);
        bundle.putInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, targetUser);
        bundle.putString(RuntimeKeys.COMPONENT_CLASS, receiverClass);
        bundle.putString(ComponentOperations.ACTION, "com.example.SECURE");
        return bundle;
    }

    private static GuestSession session(String id, String packageName, int userId,
                                        String processName, int slot, long generation) {
        return new GuestSession(id, packageName, userId, processName, slot, generation,
                SessionState.READY, 1, "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
