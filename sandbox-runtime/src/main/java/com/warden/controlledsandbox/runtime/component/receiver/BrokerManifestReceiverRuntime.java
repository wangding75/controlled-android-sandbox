package com.warden.controlledsandbox.runtime.component.receiver;

import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import android.os.Bundle;
import com.warden.controlledsandbox.domain.component.receiver.BroadcastIntent;
import com.warden.controlledsandbox.domain.component.receiver.ManifestReceiverRegistry;
import com.warden.controlledsandbox.domain.packageinfo.manifest.BinaryXmlManifestParser;
import com.warden.controlledsandbox.domain.packageinfo.manifest.ManifestModel;
import com.warden.controlledsandbox.domain.session.GuestSession;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runtime wrapper that indexes manifest receivers and preserves deterministic activation templates. */
public final class BrokerManifestReceiverRuntime {
    private final ManifestReceiverRegistry registry = new ManifestReceiverRegistry();
    private final Map<String, Bundle> startupTemplates = new LinkedHashMap<>();

    public synchronized void indexPackage(Bundle input) throws Exception {
        if (input == null) throw new IllegalArgumentException("request is required");
        File apk = new File(required(input, RuntimeKeys.APK_PATH));
        ManifestModel manifest;
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry("AndroidManifest.xml");
            if (entry == null) throw new IllegalArgumentException("APK manifest is missing");
            try (InputStream stream = archive.getInputStream(entry)) {
                manifest = new BinaryXmlManifestParser().parse(stream);
            }
        }
        indexManifest(manifest, input);
    }

    synchronized void indexManifest(ManifestModel manifest, Bundle input) {
        if (manifest == null || input == null) throw new IllegalArgumentException("manifest and input are required");
        String packageName = required(input, RuntimeKeys.PACKAGE_NAME);
        int userId = input.getInt(RuntimeKeys.VIRTUAL_USER_ID, -1);
        if (userId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (!packageName.equals(manifest.packageName())) throw new SecurityException("MANIFEST_PACKAGE_MISMATCH");
        List<ManifestReceiverRegistry.Receiver> receivers = new ArrayList<>();
        for (ManifestModel.Component component : manifest.receivers()) {
            List<ManifestReceiverRegistry.Filter> filters = new ArrayList<>();
            for (ManifestModel.IntentFilter filter : component.intentFilters()) {
                List<ManifestReceiverRegistry.DataRule> dataRules = new ArrayList<>();
                for (ManifestModel.DataRule rule : filter.dataRules()) {
                    dataRules.add(new ManifestReceiverRegistry.DataRule(rule.scheme(), rule.host(),
                            rule.path(), rule.pathPrefix(), rule.pathPattern(), rule.mimeType()));
                }
                filters.add(new ManifestReceiverRegistry.Filter(filter.priority(), filter.actions(),
                        filter.categories(), dataRules));
            }
            if (filters.isEmpty() && !component.actions().isEmpty()) {
                filters.add(new ManifestReceiverRegistry.Filter(0, new LinkedHashSet<>(component.actions()),
                        Collections.emptySet(), Collections.emptyList()));
            }
            receivers.add(new ManifestReceiverRegistry.Receiver(packageName, component.className(),
                    processName(packageName, component.processName()), component.exported(),
                    component.enabled(), component.permission(), filters));
        }
        registry.registerPackage(packageName, userId, new LinkedHashSet<>(manifest.permissions()), receivers);
        Bundle template = new Bundle(input);
        template.putString(RuntimeKeys.PACKAGE_NAME, packageName);
        template.putInt(RuntimeKeys.VIRTUAL_USER_ID, userId);
        template.putString(RuntimeKeys.APPLICATION_CLASS, manifest.applicationClass());
        template.putStringArrayList(RuntimeKeys.PERMISSIONS, new ArrayList<>(manifest.permissions()));
        startupTemplates.put(instanceKey(packageName, userId), template);
    }

    synchronized ManifestReceiverRegistry.Resolution resolveExplicit(Bundle request, GuestSession sender) {
        if (request == null || sender == null) throw new IllegalArgumentException("request and sender are required");
        int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, sender.virtualUserId());
        String targetPackage = request.getString(RuntimeKeys.TARGET_PACKAGE_NAME, sender.packageName());
        String component = normalizeClass(targetPackage, required(request, RuntimeKeys.COMPONENT_CLASS));
        return registry.resolveExplicit(sender.packageName(), sender.virtualUserId(), targetPackage,
                targetUser, component);
    }

    public synchronized List<Route> routeImplicit(Bundle request, GuestSession sender) {
        if (request == null || sender == null) throw new IllegalArgumentException("request and sender are required");
        int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, sender.virtualUserId());
        if (targetUser != sender.virtualUserId()) throw new SecurityException("RECEIVER_CROSS_USER_DENIED");
        Set<String> categories = new LinkedHashSet<>();
        ArrayList<String> requestedCategories = request.getStringArrayList(RuntimeKeys.BROADCAST_CATEGORIES);
        if (requestedCategories != null) categories.addAll(requestedCategories);
        BroadcastIntent intent = new BroadcastIntent(required(request, ComponentOperations.ACTION), categories,
                request.getString(RuntimeKeys.BROADCAST_SCHEME, ""),
                request.getString(RuntimeKeys.BROADCAST_HOST, ""),
                request.getString(RuntimeKeys.BROADCAST_PATH, ""),
                request.getString(RuntimeKeys.BROADCAST_MIME_TYPE, ""));
        String requiredPermission = request.getString(RuntimeKeys.BROADCAST_REQUIRED_RECEIVER_PERMISSION, "");
        String targetPackage = request.getString(RuntimeKeys.TARGET_PACKAGE_NAME, "").trim();
        ArrayList<Route> routes = new ArrayList<>();
        for (ManifestReceiverRegistry.Resolution resolution : registry.resolveImplicit(
                sender.packageName(), sender.virtualUserId(), intent, requiredPermission, targetPackage)) {
            routes.add(new Route(resolution, targetUser));
        }
        return Collections.unmodifiableList(routes);
    }

    public synchronized int bindSession(GuestSession session) {
        if (session == null) throw new IllegalArgumentException("session is required");
        return registry.bindSession(session.packageName(), session.virtualUserId(), session.processName(),
                session.sessionId(), session.generation()) == null ? 0 : 1;
    }

    public synchronized int removeSessionCount(GuestSession session) {
        return session == null ? 0 : registry.removeSession(session.sessionId(), session.generation());
    }

    public synchronized Snapshot removeInstance(String packageName, int userId) {
        Snapshot before = snapshot();
        startupTemplates.remove(instanceKey(packageName, userId));
        registry.removePackage(packageName, userId);
        return before.minus(snapshot());
    }

    public synchronized Snapshot clear() {
        Snapshot before = snapshot();
        startupTemplates.clear();
        registry.clear();
        return before;
    }

    public synchronized Snapshot snapshot() {
        ManifestReceiverRegistry.Snapshot value = registry.snapshot();
        return new Snapshot(value.packages(), value.receivers(), value.bindings(),
                value.actionIndexKeys(), value.actionIndexEntries(), startupTemplates.size());
    }

    public synchronized int packageCount() { return registry.packageCount(); }
    public synchronized int receiverCount() { return registry.receiverCount(); }
    public synchronized int bindingCount() { return registry.bindingCount(); }


    public record Snapshot(int packages, int receivers, int bindings,
                           int actionIndexKeys, int actionIndexEntries,
                           int startupTemplates) {
        public Snapshot {
            if (packages < 0 || receivers < 0 || bindings < 0 || actionIndexKeys < 0
                    || actionIndexEntries < 0 || startupTemplates < 0) {
                throw new IllegalArgumentException("manifest Receiver runtime snapshot counts must be non-negative");
            }
        }

        Snapshot minus(Snapshot current) {
            return new Snapshot(Math.max(0, packages - current.packages),
                    Math.max(0, receivers - current.receivers),
                    Math.max(0, bindings - current.bindings),
                    Math.max(0, actionIndexKeys - current.actionIndexKeys),
                    Math.max(0, actionIndexEntries - current.actionIndexEntries),
                    Math.max(0, startupTemplates - current.startupTemplates));
        }
    }

    /** The target user is carried separately because a Receiver descriptor is package-global. */
    public static final class Route {
        private final ManifestReceiverRegistry.Resolution resolution;
        private final int virtualUserId;

        Route(ManifestReceiverRegistry.Resolution resolution, int virtualUserId) {
            this.resolution = resolution;
            this.virtualUserId = virtualUserId;
        }

        public ManifestReceiverRegistry.Resolution resolution() { return resolution; }
        public ManifestReceiverRegistry.Receiver receiver() { return resolution.receiver(); }
        public int virtualUserId() { return virtualUserId; }
        public int priority() { return resolution.priority(); }
        public boolean requiresProcessStart() { return resolution.requiresProcessStart(); }
    }

    public synchronized Route routeExplicit(Bundle request, GuestSession sender) {
        int targetUser = request.getInt(RuntimeKeys.TARGET_VIRTUAL_USER_ID, sender.virtualUserId());
        return new Route(resolveExplicit(request, sender), targetUser);
    }

    public synchronized Bundle activationRequest(Route route) {
        ManifestReceiverRegistry.Receiver receiver = route.receiver();
        Bundle template = startupTemplates.get(instanceKey(receiver.packageName(), route.virtualUserId()));
        if (template == null) throw new IllegalStateException("MANIFEST_RECEIVER_START_TEMPLATE_MISSING");
        Bundle request = new Bundle(template);
        request.putString(RuntimeKeys.PACKAGE_NAME, receiver.packageName());
        request.putInt(RuntimeKeys.VIRTUAL_USER_ID, route.virtualUserId());
        request.putString(RuntimeKeys.PROCESS_NAME, receiver.processName());
        request.putString(RuntimeKeys.COMPONENT_CLASS, receiver.className());
        request.putInt(RuntimeKeys.BROADCAST_PRIORITY, route.priority());
        request.putString(RuntimeKeys.RECEIVER_ACTIVATION_KEY,
                processKey(receiver.packageName(), route.virtualUserId(), receiver.processName()));
        return request;
    }

    private static String processName(String packageName, String declared) {
        if (declared == null || declared.trim().isEmpty()) return packageName;
        return declared.startsWith(":") ? packageName + declared : declared;
    }

    private static String normalizeClass(String packageName, String raw) {
        if (raw.startsWith(".")) return packageName + raw;
        if (raw.indexOf('.') < 0) return packageName + "." + raw;
        return raw;
    }

    private static String instanceKey(String packageName, int userId) {
        return "u" + userId + ":" + packageName;
    }

    private static String processKey(String packageName, int userId, String processName) {
        return instanceKey(packageName, userId) + "#" + processName;
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }
}
