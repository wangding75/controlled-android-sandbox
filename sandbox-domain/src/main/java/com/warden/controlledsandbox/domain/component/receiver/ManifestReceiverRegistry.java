package com.warden.controlledsandbox.domain.component.receiver;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Broker-owned index of manifest-declared receivers and their active process generation. */
public final class ManifestReceiverRegistry {
    public static final int MAX_PACKAGES = 4096;
    public static final int MAX_RECEIVERS_PER_PACKAGE = 1024;
    public static final int MAX_IMPLICIT_MATCHES = 128;
    public static final int MAX_FILTERS_PER_RECEIVER = 128;
    public static final int MAX_ACTIONS_PER_RECEIVER = 128;
    public static final int MAX_CATEGORIES_PER_FILTER = 128;
    /**
     * Large production manifests commonly enumerate many scheme/authority/path combinations in
     * one filter. Keep a hard cap for bounded broker state, but do not reject those valid static
     * filters at the much smaller legacy fixture-oriented limit.
     */
    public static final int MAX_DATA_RULES_PER_FILTER = 1024;

    private final Map<String, PackageRecord> packages = new LinkedHashMap<>();
    private final Map<String, List<ReceiverRef>> actionIndex = new LinkedHashMap<>();
    private final Map<String, SessionBinding> bindings = new LinkedHashMap<>();

    public synchronized void registerPackage(String packageName, int virtualUserId,
                                             Set<String> requestedPermissions,
                                             List<Receiver> receivers) {
        String packageKey = packageKey(packageName, virtualUserId);
        List<Receiver> copy = new ArrayList<>(receivers == null ? Collections.emptyList() : receivers);
        if (copy.size() > MAX_RECEIVERS_PER_PACKAGE) {
            throw new IllegalArgumentException("Manifest Receiver count exceeds limit");
        }
        Map<String, Receiver> byClass = new LinkedHashMap<>();
        for (Receiver receiver : copy) {
            Objects.requireNonNull(receiver, "receiver");
            if (!packageName.equals(receiver.packageName())) {
                throw new IllegalArgumentException("Receiver package mismatch: " + receiver.className());
            }
            if (byClass.put(receiver.className(), receiver) != null) {
                throw new IllegalArgumentException("Duplicate manifest Receiver " + receiver.className());
            }
        }
        if (!packages.containsKey(packageKey) && packages.size() >= MAX_PACKAGES) {
            throw new IllegalStateException("Manifest Receiver package index is full");
        }
        PackageRecord record = new PackageRecord(packageName, virtualUserId,
                requestedPermissions, byClass);
        removeActionIndex(packageKey);
        packages.put(packageKey, record);
        addActionIndex(packageKey, record);
        Set<String> validProcesses = new LinkedHashSet<>();
        for (Receiver receiver : byClass.values()) validProcesses.add(receiver.processName());
        String prefix = packageKey + "#";
        bindings.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix)
                && !validProcesses.contains(entry.getValue().processName()));
    }

    public synchronized SessionBinding bindSession(String packageName, int virtualUserId,
                                                   String processName, String sessionId,
                                                   long generation) {
        String packageKey = packageKey(packageName, virtualUserId);
        PackageRecord record = packages.get(packageKey);
        if (record == null) throw new IllegalStateException("MANIFEST_RECEIVER_PACKAGE_NOT_INDEXED");
        String normalizedProcess = requireText(processName, "processName");
        boolean receiverProcess = false;
        for (Receiver receiver : record.receiversByClass().values()) {
            if (receiver.processName().equals(normalizedProcess)) {
                receiverProcess = true;
                break;
            }
        }
        String key = processKey(packageName, virtualUserId, normalizedProcess);
        if (!receiverProcess) {
            bindings.remove(key);
            return null;
        }
        SessionBinding binding = new SessionBinding(packageName, virtualUserId, normalizedProcess,
                sessionId, generation);
        bindings.put(key, binding);
        return binding;
    }

    public synchronized int removeSession(String sessionId, long generation) {
        int removed = 0;
        var iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            SessionBinding binding = iterator.next().getValue();
            if (binding.sessionId().equals(sessionId) && binding.generation() == generation) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int removePackage(String packageName, int virtualUserId) {
        String packageKey = packageKey(packageName, virtualUserId);
        int removed = packages.remove(packageKey) == null ? 0 : 1;
        removeActionIndex(packageKey);
        String prefix = packageKey + "#";
        bindings.keySet().removeIf(key -> key.startsWith(prefix));
        return removed;
    }

    public synchronized Resolution resolveExplicit(String senderPackage, int senderUser,
                                                   String targetPackage, int targetUser,
                                                   String receiverClass) {
        return resolveExplicit(senderPackage, senderUser, targetPackage, targetUser, receiverClass, "");
    }

    public synchronized Resolution resolveExplicit(String senderPackage, int senderUser,
                                                   String targetPackage, int targetUser,
                                                   String receiverClass,
                                                   String requiredReceiverPermission) {
        String sender = requirePackage(senderPackage, "senderPackage");
        String target = requirePackage(targetPackage, "targetPackage");
        requireSameUser(senderUser, targetUser);
        PackageRecord targetRecord = packages.get(packageKey(target, targetUser));
        if (targetRecord == null) throw new IllegalStateException("MANIFEST_RECEIVER_PACKAGE_NOT_INDEXED");
        Receiver receiver = targetRecord.receiversByClass().get(requireClass(receiverClass));
        if (receiver == null || !receiver.enabled()) throw new IllegalArgumentException("MANIFEST_RECEIVER_NOT_FOUND");
        requireCallerAllowed(sender, senderUser, targetRecord, receiver,
                normalize(requiredReceiverPermission));
        return resolution(receiver, targetUser, 0);
    }

    public synchronized List<Resolution> resolveImplicit(String senderPackage, int senderUser,
                                                         BroadcastIntent intent,
                                                         String requiredReceiverPermission) {
        return resolveImplicit(senderPackage, senderUser, intent, requiredReceiverPermission, "");
    }

    public synchronized List<Resolution> resolveImplicit(String senderPackage, int senderUser,
                                                         BroadcastIntent intent,
                                                         String requiredReceiverPermission,
                                                         String targetPackage) {
        String sender = requirePackage(senderPackage, "senderPackage");
        if (senderUser < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        Objects.requireNonNull(intent, "intent");
        String requiredPermission = normalize(requiredReceiverPermission);
        String targetRestriction = normalize(targetPackage);
        if (!targetRestriction.isEmpty()) requirePackage(targetRestriction, "targetPackage");
        ArrayList<Resolution> matches = new ArrayList<>();
        List<ReceiverRef> candidates = actionIndex.getOrDefault(actionKey(senderUser, intent.action()),
                Collections.emptyList());
        for (ReceiverRef ref : candidates) {
            PackageRecord targetRecord = packages.get(ref.packageKey());
            if (targetRecord == null || targetRecord.virtualUserId() != senderUser) continue;
            if (!targetRestriction.isEmpty() && !targetRestriction.equals(targetRecord.packageName())) continue;
            Receiver receiver = targetRecord.receiversByClass().get(ref.receiverClass());
            if (receiver == null || !receiver.enabled()) continue;
            int priority = receiver.matchPriority(intent);
            if (priority == Integer.MIN_VALUE) continue;
            try {
                requireCallerAllowed(sender, senderUser, targetRecord, receiver, requiredPermission);
            } catch (SecurityException ignored) {
                continue;
            }
            matches.add(resolution(receiver, senderUser, priority));
            if (matches.size() > MAX_IMPLICIT_MATCHES) {
                throw new IllegalStateException("MANIFEST_RECEIVER_MATCH_LIMIT_EXCEEDED");
            }
        }
        matches.sort(Comparator.comparingInt(Resolution::priority).reversed()
                .thenComparing(item -> item.receiver().packageName())
                .thenComparing(item -> item.receiver().className()));
        return Collections.unmodifiableList(matches);
    }

    public synchronized boolean packageRequestsPermission(String packageName, int virtualUserId,
                                                          String permission) {
        String normalized = normalize(permission);
        if (normalized.isEmpty()) return true;
        PackageRecord record = packages.get(packageKey(requirePackage(packageName, "packageName"),
                virtualUserId));
        return record != null && record.requestedPermissions().contains(normalized);
    }

    public synchronized int packageCount() { return packages.size(); }

    public synchronized int receiverCount() {
        int count = 0;
        for (PackageRecord record : packages.values()) count += record.receiversByClass().size();
        return count;
    }

    public synchronized int bindingCount() { return bindings.size(); }
    public synchronized int actionIndexKeyCount() { return actionIndex.size(); }

    public synchronized int actionIndexEntryCount() {
        int count = 0;
        for (List<ReceiverRef> refs : actionIndex.values()) count += refs.size();
        return count;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(packageCount(), receiverCount(), bindingCount(),
                actionIndexKeyCount(), actionIndexEntryCount());
    }

    public synchronized Snapshot clear() {
        Snapshot before = snapshot();
        packages.clear();
        actionIndex.clear();
        bindings.clear();
        return before;
    }

    public record Snapshot(int packages, int receivers, int bindings,
                           int actionIndexKeys, int actionIndexEntries) {
        public Snapshot {
            if (packages < 0 || receivers < 0 || bindings < 0
                    || actionIndexKeys < 0 || actionIndexEntries < 0) {
                throw new IllegalArgumentException("manifest Receiver snapshot counts must be non-negative");
            }
        }
    }

    public static final class Receiver {
        private final String packageName;
        private final String className;
        private final String processName;
        private final boolean exported;
        private final boolean enabled;
        private final String permission;
        private final Set<String> actions;
        private final List<Filter> filters;

        public Receiver(String packageName, String className, String processName,
                        boolean exported, boolean enabled, String permission,
                        Set<String> actions) {
            this(packageName, className, processName, exported, enabled, permission,
                    legacyFilters(actions));
        }

        public Receiver(String packageName, String className, String processName,
                        boolean exported, boolean enabled, String permission,
                        List<Filter> filters) {
            this.packageName = requirePackage(packageName, "packageName");
            this.className = requireClass(className);
            this.processName = requireText(processName, "processName");
            this.exported = exported;
            this.enabled = enabled;
            this.permission = normalize(permission);
            ArrayList<Filter> copy = new ArrayList<>(filters == null ? Collections.emptyList() : filters);
            if (copy.size() > MAX_FILTERS_PER_RECEIVER) {
                throw new IllegalArgumentException("Too many intent filters for " + className);
            }
            this.filters = Collections.unmodifiableList(copy);
            LinkedHashSet<String> allActions = new LinkedHashSet<>();
            for (Filter filter : copy) allActions.addAll(filter.actions());
            if (allActions.size() > MAX_ACTIONS_PER_RECEIVER) {
                throw new IllegalArgumentException("Too many Receiver actions for " + className);
            }
            this.actions = Collections.unmodifiableSet(allActions);
        }

        public String packageName() { return packageName; }
        public String className() { return className; }
        public String processName() { return processName; }
        public boolean exported() { return exported; }
        public boolean enabled() { return enabled; }
        public String permission() { return permission; }
        public Set<String> actions() { return actions; }
        public List<Filter> filters() { return filters; }

        int matchPriority(BroadcastIntent intent) {
            int best = Integer.MIN_VALUE;
            for (Filter filter : filters) if (filter.matches(intent)) best = Math.max(best, filter.priority());
            return best;
        }
    }

    public static final class Filter {
        private final int priority;
        private final Set<String> actions;
        private final Set<String> categories;
        private final List<DataRule> dataRules;

        public Filter(int priority, Set<String> actions, Set<String> categories, List<DataRule> dataRules) {
            if (priority < -1000 || priority > 1000) throw new IllegalArgumentException("Receiver priority out of range");
            this.priority = priority;
            this.actions = immutableTexts(actions);
            this.categories = immutableTexts(categories);
            if (this.actions.size() > MAX_ACTIONS_PER_RECEIVER) {
                throw new IllegalArgumentException("Too many actions in Receiver filter");
            }
            if (this.categories.size() > MAX_CATEGORIES_PER_FILTER) {
                throw new IllegalArgumentException("Too many categories in Receiver filter");
            }
            ArrayList<DataRule> dataCopy = new ArrayList<>(
                    dataRules == null ? Collections.emptyList() : dataRules);
            if (dataCopy.size() > MAX_DATA_RULES_PER_FILTER) {
                throw new IllegalArgumentException("Too many data rules in Receiver filter");
            }
            this.dataRules = Collections.unmodifiableList(dataCopy);
            // Android permits IntentFilter data dimensions to be added independently. In
            // particular, dynamic filters may contain a path entry without an authority or
            // scheme; the platform keeps that registration instead of rejecting it here.
            // Matching below still applies every dimension that was supplied.
        }

        public int priority() { return priority; }
        public Set<String> actions() { return actions; }
        public Set<String> categories() { return categories; }
        public List<DataRule> dataRules() { return dataRules; }

        boolean matches(BroadcastIntent intent) {
            if (!actions.contains(intent.action())) return false;
            if (!categories.containsAll(intent.categories())) return false;
            if (dataRules.isEmpty()) return !intent.hasData();

            LinkedHashSet<String> schemes = new LinkedHashSet<>();
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            LinkedHashSet<String> mimeTypes = new LinkedHashSet<>();
            ArrayList<DataRule> pathRules = new ArrayList<>();
            for (DataRule rule : dataRules) {
                if (!rule.scheme().isEmpty()) schemes.add(rule.scheme());
                if (!rule.host().isEmpty()) hosts.add(rule.host());
                if (!rule.mimeType().isEmpty()) mimeTypes.add(rule.mimeType());
                if (rule.hasPathConstraint()) pathRules.add(rule);
            }

            if (mimeTypes.isEmpty()) {
                if (!intent.mimeType().isEmpty()) return false;
            } else {
                boolean mimeMatched = false;
                for (String mimeType : mimeTypes) {
                    if (mimeMatches(mimeType, intent.mimeType())) { mimeMatched = true; break; }
                }
                if (!mimeMatched) return false;
            }

            if (schemes.isEmpty()) {
                if (!intent.scheme().isEmpty() && !"content".equalsIgnoreCase(intent.scheme())
                        && !"file".equalsIgnoreCase(intent.scheme())) return false;
            } else if (!schemes.contains(intent.scheme().toLowerCase(Locale.ROOT))) {
                return false;
            }

            if (!hosts.isEmpty() && !hosts.contains(intent.host().toLowerCase(Locale.ROOT))) return false;
            if (!pathRules.isEmpty()) {
                boolean pathMatched = false;
                for (DataRule rule : pathRules) {
                    if (rule.pathMatches(intent.path())) { pathMatched = true; break; }
                }
                if (!pathMatched) return false;
            }
            return true;
        }
    }

    public static final class DataRule {
        private final String scheme;
        private final String host;
        private final String path;
        private final String pathPrefix;
        private final String pathPattern;
        private final String mimeType;

        public DataRule(String scheme, String host, String path, String pathPrefix,
                        String pathPattern, String mimeType) {
            this.scheme = normalize(scheme).toLowerCase(Locale.ROOT);
            this.host = normalize(host).toLowerCase(Locale.ROOT);
            this.path = normalizePath(path);
            this.pathPrefix = normalizePath(pathPrefix);
            this.pathPattern = normalizePath(pathPattern);
            this.mimeType = normalize(mimeType).toLowerCase(Locale.ROOT);
            if (!this.mimeType.isEmpty() && !BroadcastIntent.validMime(this.mimeType)) {
                throw new IllegalArgumentException("Invalid filter MIME type: " + mimeType);
            }
        }

        public String scheme() { return scheme; }
        public String host() { return host; }
        public String path() { return path; }
        public String pathPrefix() { return pathPrefix; }
        public String pathPattern() { return pathPattern; }
        public String mimeType() { return mimeType; }

        boolean hasPathConstraint() {
            return !path.isEmpty() || !pathPrefix.isEmpty() || !pathPattern.isEmpty();
        }

        boolean pathMatches(String actualPath) {
            if (!path.isEmpty() && path.equals(actualPath)) return true;
            if (!pathPrefix.isEmpty() && actualPath.startsWith(pathPrefix)) return true;
            return !pathPattern.isEmpty() && AndroidSimpleGlobMatcher.matches(pathPattern, actualPath);
        }
    }

    public static final class SessionBinding {
        private final String packageName;
        private final int virtualUserId;
        private final String processName;
        private final String sessionId;
        private final long generation;

        SessionBinding(String packageName, int virtualUserId, String processName,
                       String sessionId, long generation) {
            this.packageName = requirePackage(packageName, "packageName");
            if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
            this.virtualUserId = virtualUserId;
            this.processName = requireText(processName, "processName");
            this.sessionId = requireText(sessionId, "sessionId");
            if (generation < 1) throw new IllegalArgumentException("generation must be positive");
            this.generation = generation;
        }

        public String packageName() { return packageName; }
        public int virtualUserId() { return virtualUserId; }
        public String processName() { return processName; }
        public String sessionId() { return sessionId; }
        public long generation() { return generation; }
    }

    public static final class Resolution {
        private final Receiver receiver;
        private final Optional<SessionBinding> binding;
        private final int priority;

        Resolution(Receiver receiver, Optional<SessionBinding> binding, int priority) {
            this.receiver = receiver;
            this.binding = binding;
            this.priority = priority;
        }

        public Receiver receiver() { return receiver; }
        public Optional<SessionBinding> binding() { return binding; }
        public int priority() { return priority; }
        public boolean requiresProcessStart() { return binding.isEmpty(); }
    }

    private record PackageRecord(String packageName, int virtualUserId,
                                 Set<String> requestedPermissions,
                                 Map<String, Receiver> receiversByClass) {
        PackageRecord {
            requestedPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(
                    requestedPermissions == null ? Collections.emptySet() : requestedPermissions));
            receiversByClass = Collections.unmodifiableMap(new LinkedHashMap<>(receiversByClass));
        }
    }

    private Resolution resolution(Receiver receiver, int userId, int priority) {
        SessionBinding binding = bindings.get(processKey(receiver.packageName(), userId, receiver.processName()));
        return new Resolution(receiver, Optional.ofNullable(binding), priority);
    }

    private void requireCallerAllowed(String senderPackage, int senderUser, PackageRecord targetRecord,
                                      Receiver receiver, String requiredReceiverPermission) {
        boolean samePackage = senderPackage.equals(targetRecord.packageName());
        if (!samePackage && !receiver.exported()) throw new SecurityException("MANIFEST_RECEIVER_NOT_EXPORTED");
        if (!samePackage && !receiver.permission().isEmpty()) {
            PackageRecord senderRecord = packages.get(packageKey(senderPackage, senderUser));
            if (senderRecord == null || !senderRecord.requestedPermissions().contains(receiver.permission())) {
                throw new SecurityException("MANIFEST_RECEIVER_PERMISSION_DENIED:" + receiver.permission());
            }
        }
        if (!requiredReceiverPermission.isEmpty()
                && !targetRecord.requestedPermissions().contains(requiredReceiverPermission)) {
            throw new SecurityException("MANIFEST_RECEIVER_SENDER_PERMISSION_DENIED:" + requiredReceiverPermission);
        }
    }

    private void addActionIndex(String packageKey, PackageRecord record) {
        for (Receiver receiver : record.receiversByClass().values()) {
            for (String action : receiver.actions()) {
                String key = actionKey(record.virtualUserId(), action);
                actionIndex.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new ReceiverRef(packageKey, receiver.className()));
            }
        }
    }

    private void removeActionIndex(String packageKey) {
        var iterator = actionIndex.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<ReceiverRef>> entry = iterator.next();
            entry.getValue().removeIf(ref -> ref.packageKey().equals(packageKey));
            if (entry.getValue().isEmpty()) iterator.remove();
        }
    }

    private static String actionKey(int virtualUserId, String action) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        return "u" + virtualUserId + ":" + requireText(action, "action");
    }

    private record ReceiverRef(String packageKey, String receiverClass) { }

    private static List<Filter> legacyFilters(Set<String> actions) {
        if (actions == null || actions.isEmpty()) return Collections.emptyList();
        return List.of(new Filter(0, actions, Collections.emptySet(), Collections.emptyList()));
    }

    private static Set<String> immutableTexts(Set<String> values) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        if (values != null) for (String value : values) copy.add(requireText(value, "filter value"));
        return Collections.unmodifiableSet(copy);
    }

    private static boolean mimeMatches(String filter, String actual) {
        if (actual == null || actual.isEmpty()) return false;
        String[] f = filter.split("/", -1);
        String[] a = actual.toLowerCase(Locale.ROOT).split("/", -1);
        if (f.length != 2 || a.length != 2) return false;
        return ("*".equals(f[0]) || f[0].equals(a[0]))
                && ("*".equals(f[1]) || f[1].equals(a[1]));
    }


    private static void requireSameUser(int senderUser, int targetUser) {
        if (senderUser < 0 || targetUser < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (senderUser != targetUser) throw new SecurityException("RECEIVER_CROSS_USER_DENIED");
    }

    private static String packageKey(String packageName, int virtualUserId) {
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        return "u" + virtualUserId + ":" + requirePackage(packageName, "packageName");
    }

    private static String processKey(String packageName, int virtualUserId, String processName) {
        return packageKey(packageName, virtualUserId) + "#" + requireText(processName, "processName");
    }

    private static String requirePackage(String value, String name) {
        String text = requireText(value, name);
        if (!text.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException(name + " is invalid");
        return text;
    }

    private static String requireClass(String value) {
        String text = requireText(value, "receiverClass");
        if (!text.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_$]+)+")) throw new IllegalArgumentException("receiverClass is invalid");
        return text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static String normalizePath(String value) {
        String path = normalize(value);
        if (!path.isEmpty() && !path.startsWith("/")) path = "/" + path;
        return path;
    }
}
