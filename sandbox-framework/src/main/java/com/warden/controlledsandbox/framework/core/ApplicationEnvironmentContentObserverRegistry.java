package com.warden.controlledsandbox.framework.core;

import android.net.Uri;

import com.warden.controlledsandbox.contract.IProviderObserver;
import com.warden.controlledsandbox.framework.identity.ContentObserverBridge;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Owns local and Broker-backed ContentObserver registration/delivery state. */
final class ApplicationEnvironmentContentObserverRegistry {
    private final GuestIdentity identity;
    private final Map<Object, List<Registration>> registrations = new IdentityHashMap<>();

    ApplicationEnvironmentContentObserverRegistry(GuestIdentity identity) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
    }

    int count() {
        synchronized (registrations) {
            int count = 0;
            for (List<Registration> values : registrations.values()) {
                count += values == null ? 0 : values.size();
            }
            return count;
        }
    }

    boolean contains(Object observer) {
        synchronized (registrations) {
            return registrations.containsKey(observer);
        }
    }

    void register(Object observer, String uri, boolean notifyForDescendants) {
        String normalizedUri = requiredUri(uri);
        Registration registration = new Registration(observer, normalizedUri,
                notifyForDescendants, deliverSelfNotifications(observer));
        ContentObserverBridge bridge = identity.contentObserverBridge();
        GuestIdentity.ProviderRoute route = identity.providerRoute(authority(normalizedUri));
        if (bridge != null && route != null) {
            ObserverRelay relay = new ObserverRelay(this, observer);
            ContentObserverBridge.Registration remote = bridge.register(
                    route.packageName(), route.virtualUserId(), route.processName(),
                    route.authority(), route.componentClass(), normalizedUri,
                    notifyForDescendants, registration.deliverSelfNotifications, relay.asBinder());
            registration.remoteId = remote.id();
            registration.relay = relay;
        }
        synchronized (registrations) {
            registrations.computeIfAbsent(observer, ignored -> new ArrayList<>()).add(registration);
        }
    }

    void unregister(Object observer) {
        List<Registration> removed;
        synchronized (registrations) {
            removed = registrations.remove(observer);
        }
        if (removed == null) return;
        ContentObserverBridge bridge = identity.contentObserverBridge();
        if (bridge != null) {
            for (Registration registration : removed) {
                if (registration.remoteId == null) continue;
                try { bridge.unregister(registration.remoteId); }
                catch (Throwable error) {
                    com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                            .rethrowIfFatal(error);
                }
            }
        }
    }

    void notifyObservers(Object[] arguments) {
        List<String> uris = observerUris(arguments);
        if (uris.isEmpty()) throw new IllegalArgumentException(
                "VIRTUAL_CONTENT_OBSERVER_URI_REQUIRED");
        Object notifyingObserver = observerArgument(arguments);
        int flags = observerChangeFlags(arguments);
        for (String uri : uris) {
            String normalizedUri = requiredUri(uri);
            String authority = authority(normalizedUri);
            ContentObserverBridge bridge = identity.contentObserverBridge();
            GuestIdentity.ProviderRoute route = identity.providerRoute(authority);
            boolean remote = false;
            if (bridge != null && route != null) {
                bridge.notifyChange(route.packageName(), route.virtualUserId(), route.processName(),
                        route.authority(), route.componentClass(), normalizedUri, flags);
                remote = true;
            }
            dispatchLocal(normalizedUri, notifyingObserver, flags, remote);
        }
    }

    Object observerArgument(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value != null && hasOnChange(value)) return value;
        }
        return null;
    }

    String observerUri(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            String uri = firstUri(value);
            if (!uri.isEmpty()) return uri;
        }
        return "";
    }

    boolean firstBoolean(Object[] arguments, boolean fallback) {
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Boolean booleanValue) return booleanValue;
        }
        return fallback;
    }

    boolean dispatch(Object observer, String uri, boolean selfChange, int flags) {
        boolean delivered = false;
        for (Method callback : observer.getClass().getMethods()) {
            if (!callback.getName().equals("onChange")) continue;
            Object[] values = observerArguments(callback, uri, selfChange, flags);
            try {
                callback.setAccessible(true);
                callback.invoke(observer, values);
                delivered = true;
                break;
            } catch (Throwable error) {
                com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                        .rethrowIfFatal(error);
                return false;
            }
        }
        return delivered;
    }

    private void dispatchLocal(String uri, Object notifyingObserver, int flags,
                               boolean remoteAlreadyDelivered) {
        String normalizedUri = requiredUri(uri);
        List<Registration> snapshot = new ArrayList<>();
        synchronized (registrations) {
            for (List<Registration> values : registrations.values()) {
                if (values != null) snapshot.addAll(values);
            }
        }
        List<Object> dead = new ArrayList<>();
        for (Registration registration : snapshot) {
            if (registration.remoteId != null && remoteAlreadyDelivered) continue;
            if (!uriMatches(registration.uri, normalizedUri, registration.notifyForDescendants)) {
                continue;
            }
            boolean selfChange = notifyingObserver != null
                    && notifyingObserver == registration.observer;
            if (selfChange && !registration.deliverSelfNotifications) continue;
            if (!dispatch(registration.observer, normalizedUri, selfChange, flags)) {
                dead.add(registration.observer);
            }
        }
        for (Object observer : dead) unregister(observer);
    }

    private Object[] observerArguments(Method callback, String uri, boolean selfChange, int flags) {
        Class<?>[] types = callback.getParameterTypes();
        Object[] values = new Object[types.length];
        int integerCount = 0;
        for (Class<?> type : types) {
            if (type == int.class || type == Integer.class) integerCount++;
        }
        int integerIndex = 0;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (type == boolean.class || type == Boolean.class) values[index] = selfChange;
            else if (type == int.class || type == Integer.class) {
                // ContentObserver's public API orders these as flags[, userId].
                values[index] = integerIndex++ == 0 || integerCount == 1
                        ? flags : identity.virtualUserId();
            } else if (type == Uri.class) values[index] = Uri.parse(uri);
            else if (type == String.class) values[index] = uri;
            else if (java.util.Collection.class.isAssignableFrom(type)
                    || java.lang.Iterable.class.isAssignableFrom(type)) {
                values[index] = observerUriCollection(type, uri);
            } else if (type.isArray() && type.getComponentType() != null
                    && type.getComponentType().isAssignableFrom(Uri.class)) {
                Object array = Array.newInstance(type.getComponentType(), 1);
                Array.set(array, 0, Uri.parse(uri));
                values[index] = array;
            } else if (type == Object.class) {
                values[index] = Uri.parse(uri);
            } else values[index] = null;
        }
        return values;
    }

    private static Object observerUriCollection(Class<?> type, String uri) {
        Uri value = Uri.parse(uri);
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>(List.of(value));
        }
        if (type.isAssignableFrom(Collections.singletonList(value).getClass())) {
            return Collections.singletonList(value);
        }
        throw new IllegalArgumentException("VIRTUAL_CONTENT_OBSERVER_COLLECTION_TYPE_UNSUPPORTED:"
                + type.getName());
    }

    private static List<String> observerUris(Object[] arguments) {
        if (arguments == null) return Collections.emptyList();
        List<String> uris = new ArrayList<>();
        for (Object value : arguments) collectUris(value, uris);
        return uris;
    }

    private static void collectUris(Object value, List<String> output) {
        if (value == null) return;
        if (value instanceof Uri uri) {
            output.add(uri.toString());
            return;
        }
        if (value instanceof String text && text.startsWith("content://")) {
            output.add(text);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) collectUris(child, output);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                collectUris(Array.get(value, index), output);
            }
        }
    }

    private static String firstUri(Object value) {
        if (value instanceof Uri uri) return uri.toString();
        if (value instanceof String text && text.startsWith("content://")) return text;
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                String uri = firstUri(child);
                if (!uri.isEmpty()) return uri;
            }
        }
        if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                String uri = firstUri(Array.get(value, index));
                if (!uri.isEmpty()) return uri;
            }
        }
        return "";
    }

    private static String authority(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        try {
            String value = Uri.parse(uri).getAuthority();
            return value == null ? "" : value;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            return "";
        }
    }

    private static String requiredUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) throw new IllegalArgumentException(
                "VIRTUAL_CONTENT_OBSERVER_URI_REQUIRED");
        String normalized = uri.trim();
        if (!normalized.startsWith("content://") || authority(normalized).isEmpty()) {
            throw new IllegalArgumentException("VIRTUAL_CONTENT_OBSERVER_URI_INVALID");
        }
        return normalized;
    }

    private static boolean uriMatches(String registered, String changed, boolean descendants) {
        if (registered.equals(changed)) return true;
        return descendants && changed.startsWith(registered.endsWith("/")
                ? registered : registered + "/");
    }

    private static int observerChangeFlags(Object[] arguments) {
        List<Integer> values = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Integer integerValue) values.add(integerValue);
        }
        return values.size() < 2 ? 0 : values.get(values.size() - 2);
    }

    private static boolean deliverSelfNotifications(Object observer) {
        try {
            Method method = observer.getClass().getMethod("deliverSelfNotifications");
            Object value = method.invoke(observer);
            return value instanceof Boolean && (Boolean) value;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(error);
            return false;
        }
    }

    private static boolean hasOnChange(Object value) {
        for (Method method : value.getClass().getMethods()) {
            if (method.getName().equals("onChange")) return true;
        }
        return false;
    }

    private static final class Registration {
        private final Object observer;
        private final String uri;
        private final boolean notifyForDescendants;
        private final boolean deliverSelfNotifications;
        private String remoteId;
        private ObserverRelay relay;

        private Registration(Object observer, String uri, boolean notifyForDescendants,
                             boolean deliverSelfNotifications) {
            this.observer = observer;
            this.uri = uri;
            this.notifyForDescendants = notifyForDescendants;
            this.deliverSelfNotifications = deliverSelfNotifications;
        }
    }

    private static final class ObserverRelay extends IProviderObserver.Stub {
        private final ApplicationEnvironmentContentObserverRegistry owner;
        private final Object observer;

        private ObserverRelay(ApplicationEnvironmentContentObserverRegistry owner,
                              Object observer) {
            this.owner = owner;
            this.observer = observer;
        }

        @Override public void onChange(String uri, boolean selfChange, int flags) {
            if (!owner.dispatch(observer, requiredUri(uri), selfChange, flags)) {
                throw new IllegalStateException("VIRTUAL_CONTENT_OBSERVER_CALLBACK_UNAVAILABLE");
            }
        }
    }
}
