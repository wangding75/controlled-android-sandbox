package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualContextHubProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualContextHubSnapshot;
import com.warden.controlledsandbox.contract.VirtualGraphicsStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPersistentDataBlockProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrivilegedServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSearchProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualStorageStatsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualSystemUpdateProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Search/storage/graphics/hardware-state service virtualization. */
final class PrivilegedServicesInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Set<Object> graphicsBuffers = identitySet();
    private final Set<Object> contextHubClients = identitySet();
    private byte[] persistentData;
    private Boolean oemUnlockEnabled;
    private Bundle submittedSystemUpdate;
    private int syntheticSequence;

    PrivilegedServicesInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    synchronized Decision before(Method method, Object[] arguments) {
        VirtualPrivilegedServicesProfileSnapshot profile;
        try {
            profile = identity.virtualServices().privilegedServicesProfile();
        } catch (IllegalStateException unavailable) {
            String message = unavailable.getMessage();
            if ("VIRTUAL_PRIVILEGED_SERVICES_PROFILE_AUTHORITY_REQUIRED".equals(message)
                    || "VIRTUAL_PRIVILEGED_SERVICES_PROFILE_NOT_AVAILABLE".equals(message)) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        return switch (service) {
            case "search" -> search(method, profile.search());
            case "storagestats" -> storage(method, profile.storageStats());
            case "graphicsstats" -> graphics(method, arguments, profile.graphicsStats());
            case "contexthub" -> contextHub(method, arguments, profile.contextHub());
            case "persistentdatablock" -> persistentDataBlock(
                    method, arguments, profile.persistentDataBlock());
            case "systemupdate" -> systemUpdate(method, arguments, profile.systemUpdate());
            default -> Decision.passThrough();
        };
    }

    synchronized int graphicsBufferCount() { return graphicsBuffers.size(); }
    synchronized int contextHubClientCount() { return contextHubClients.size(); }

    private Decision search(Method method, VirtualSearchProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (InvocationMethodMatcher.named(name, "getglobalsearchactivity")) {
            return Decision.handled(PrivilegedInvocationValues.component(
                    method.getReturnType(), profile.globalSearchComponent()));
        }
        if (InvocationMethodMatcher.named(name, "getwebsearchactivity")) {
            return Decision.handled(PrivilegedInvocationValues.component(
                    method.getReturnType(), profile.webSearchComponent()));
        }
        if (containsAny(name, "isglobalsearchenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.globalSearchEnabled()));
        }
        if (containsAny(name, "iswebsearchenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.webSearchEnabled()));
        }
        if (containsAny(name, "getsearchablecomponents", "getsearchablesinglobalsearch")) {
            if (method.getReturnType() == Object.class || method.getReturnType() == String[].class) {
                return Decision.handled(stringArrayOrList(
                        method.getReturnType(), profile.searchableComponents()));
            }
            return Decision.handled(emptyCollection(method.getReturnType()));
        }
        if (containsAny(name, "getsuggestionauthorities")) {
            return Decision.handled(stringArrayOrList(
                    method.getReturnType(), profile.suggestionAuthorities()));
        }
        if (containsAny(name, "getsearchableinfo", "launchassist", "launchlegacyassist")) {
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        return unsupported("search", method);
    }

    private Decision storage(Method method, VirtualStorageStatsProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "gettotalbytes")) {
            return Decision.handled(numeric(method.getReturnType(), profile.totalBytes()));
        }
        if (containsAny(name, "getfreebytes")) {
            return Decision.handled(numeric(method.getReturnType(), profile.freeBytes()));
        }
        if (containsAny(name, "getcachequotabytes")) {
            return Decision.handled(numeric(method.getReturnType(), profile.cacheQuotaBytes()));
        }
        if (containsAny(name, "isquotasupported")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.quotaSupported()));
        }
        if (containsAny(name, "isreservedsupported")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.reservedSupported()));
        }
        if (containsAny(name, "queryexternalstats")) {
            return Decision.handled(PrivilegedInvocationValues.storageStats(
                    method.getReturnType(), profile, true));
        }
        if (containsAny(name, "querystats", "getstoragestats")) {
            return Decision.handled(PrivilegedInvocationValues.storageStats(
                    method.getReturnType(), profile, false));
        }
        if (containsAny(name, "getcachebytes")) {
            return Decision.handled(numeric(method.getReturnType(), profile.cacheBytes()));
        }
        return unsupported("storage_stats", method);
    }

    private Decision graphics(Method method, Object[] arguments,
            VirtualGraphicsStatsProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "addtosavebuffer", "rotatebuffer")) {
            throw new SecurityException("VIRTUAL_GRAPHICS_BUFFER_MUTATION_DENIED");
        }
        if (containsAny(name, "savebuffer", "releasebuffer", "close", "destroy")) {
            removeIdentity(graphicsBuffers, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "requestbuffer")) {
            if (!profile.allowBufferRequests()) {
                throw new SecurityException("VIRTUAL_GRAPHICS_BUFFER_REQUEST_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(graphicsBuffers, token, profile.maximumBuffers(),
                    "VIRTUAL_GRAPHICS_BUFFER_LIMIT_EXCEEDED");
            if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                return Decision.handled(null);
            }
            graphicsBuffers.remove(token);
            return Decision.handled(null);
        }
        if (containsAny(name, "getstats", "querystats", "fetchstats")) {
            if (!profile.exposeStats()) return Decision.handled(emptyValue(method.getReturnType()));
            return Decision.handled(PrivilegedInvocationValues.graphicsStats(method.getReturnType(),
                    profile.totalFrames(), profile.jankyFrames(), profile.lastResetTimeMs()));
        }
        return unsupported("graphics_stats", method);
    }

    private Decision contextHub(Method method, Object[] arguments,
            VirtualContextHubProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "closeclient", "destroyclient", "unregisterclient", "release")) {
            removeIdentity(contextHubClients, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        // ContextHubManager registers its process callback from the constructor on API32/API35.
        // A controlled-unavailable hub must keep that framework cache constructible while all
        // actual client, message, and nanoapp operations remain policy-controlled below.
        if (containsAny(name, "registercallback", "unregistercallback")) {
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getcontexthubhandles", "getcontexthubs")) {
            return Decision.handled(PrivilegedInvocationValues.contextHubs(
                    method.getReturnType(), profile.hubs()));
        }
        if (containsAny(name, "getcontexthubinfo")) {
            return Decision.handled(PrivilegedInvocationValues.contextHub(
                    method.getReturnType(), hub(profile.hubs(), firstInt(arguments, -1))));
        }
        if (containsAny(name, "querynanoapps", "getnanoappinstanceinfo", "findnanoapp")) {
            List<String> ids = profile.hubs().stream().flatMap(value -> value.nanoAppIds().stream())
                    .distinct().collect(java.util.stream.Collectors.toList());
            return Decision.handled(stringArrayOrList(method.getReturnType(), ids));
        }
        if (containsAny(name, "createclient", "creatependingintentclient")) {
            if (!profile.contextHubAvailable() || !profile.allowClientSessions()) {
                throw new SecurityException("VIRTUAL_CONTEXT_HUB_CLIENT_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(contextHubClients, token, profile.maximumClients(),
                    "VIRTUAL_CONTEXT_HUB_CLIENT_LIMIT_EXCEEDED");
            return adaptableSessionResult("CONTEXT_HUB_CLIENT", method, token, contextHubClients);
        }
        if (containsAny(name, "sendmessage")) {
            if (!profile.allowMessages()) throw new SecurityException("VIRTUAL_CONTEXT_HUB_MESSAGE_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "loadnanoapp", "unloadnanoapp", "enablenanoapp", "disablenanoapp")) {
            if (!profile.allowNanoAppMutations()) {
                throw new SecurityException("VIRTUAL_CONTEXT_HUB_NANOAPP_MUTATION_DENIED");
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("context_hub", method);
    }

    private Decision persistentDataBlock(Method method, Object[] arguments,
            VirtualPersistentDataBlockProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        ensurePersistentData(profile);
        if (InvocationMethodMatcher.named(name, "read")) {
            if (!profile.readable()) throw new SecurityException("VIRTUAL_PERSISTENT_DATA_READ_DENIED");
            return Decision.handled(persistentData.clone());
        }
        if (InvocationMethodMatcher.named(name, "write")) {
            if (!profile.writable()) throw new SecurityException("VIRTUAL_PERSISTENT_DATA_WRITE_DENIED");
            byte[] value = firstBytes(arguments);
            if (value.length > profile.maximumDataBytes()) {
                throw new IllegalArgumentException("VIRTUAL_PERSISTENT_DATA_LIMIT_EXCEEDED");
            }
            persistentData = value.clone();
            return Decision.handled(numeric(method.getReturnType(), value.length));
        }
        if (containsAny(name, "getdatablocksize")) {
            return Decision.handled(numeric(method.getReturnType(), persistentData.length));
        }
        if (containsAny(name, "getmaximumdatablocksize")) {
            return Decision.handled(numeric(method.getReturnType(), profile.maximumDataBytes()));
        }
        if (containsAny(name, "getoemunlockenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(),
                    oemUnlockEnabled == null ? profile.oemUnlockEnabled() : oemUnlockEnabled));
        }
        if (containsAny(name, "setoemunlockenabled")) {
            if (!profile.writable()) throw new SecurityException("VIRTUAL_OEM_UNLOCK_MUTATION_DENIED");
            oemUnlockEnabled = firstBoolean(arguments, false);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getflashlockstate")) {
            return Decision.handled(numeric(method.getReturnType(), profile.flashLockState()));
        }
        if (containsAny(name, "hasfrpcredentialhandle", "ischecksumvalid")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.checksumValid()));
        }
        if (containsAny(name, "wipe")) {
            if (!profile.allowWipe()) throw new SecurityException("VIRTUAL_PERSISTENT_DATA_WIPE_DENIED");
            persistentData = new byte[0];
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("persistent_data_block", method);
    }

    private Decision systemUpdate(Method method, Object[] arguments,
            VirtualSystemUpdateProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "retrievesystemupdateinfo", "getsystemupdateinfo")) {
            if (!profile.queryEnabled()) return Decision.handled(new Bundle());
            return Decision.handled(submittedSystemUpdate == null
                    ? PrivilegedInvocationValues.systemUpdateBundle(profile.status(), profile.title(),
                            profile.version(), profile.securityPatch(), profile.progressPercent(),
                            profile.receivedTimeMs())
                    : new Bundle(submittedSystemUpdate));
        }
        if (containsAny(name, "updatesystemupdateinfo", "submitsystemupdateinfo")) {
            if (!profile.allowStatusSubmission()) {
                throw new SecurityException("VIRTUAL_SYSTEM_UPDATE_SUBMISSION_DENIED");
            }
            Bundle value = firstBundle(arguments);
            submittedSystemUpdate = value == null ? new Bundle() : new Bundle(value);
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("system_update", method);
    }

    private static VirtualContextHubSnapshot hub(List<VirtualContextHubSnapshot> hubs, int id) {
        for (VirtualContextHubSnapshot hub : hubs) if (hub.hubId() == id) return hub;
        return null;
    }

    private void ensurePersistentData(VirtualPersistentDataBlockProfileSnapshot profile) {
        if (persistentData == null) persistentData = profile.data();
    }

    private static byte[] firstBytes(Object[] arguments) {
        if (arguments == null) return new byte[0];
        for (Object value : arguments) if (value instanceof byte[] bytes) return bytes.clone();
        return new byte[0];
    }

    private static Bundle firstBundle(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value instanceof Bundle bundle) return new Bundle(bundle);
            if (value != null && "android.os.PersistableBundle".equals(
                    value.getClass().getName())) {
                return persistableBundle(value);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Bundle persistableBundle(Object value) {
        try {
            java.lang.reflect.Method keySet = value.getClass().getMethod("keySet");
            java.lang.reflect.Method get = value.getClass().getMethod("get", String.class);
            Bundle out = new Bundle();
            for (String key : (java.util.Set<String>) keySet.invoke(value)) {
                Object item = get.invoke(value, key);
                if (item instanceof String text) out.putString(key, text);
                else if (item instanceof Integer number) out.putInt(key, number);
                else if (item instanceof Long number) out.putLong(key, number);
                else if (item instanceof Boolean flag) out.putBoolean(key, flag);
                else if (item instanceof Double number) out.putDouble(key, number);
                else if (item != null && item.getClass().isArray()) {
                    putBundleArray(out, key, item);
                } else if (item != null) {
                    throw new IllegalArgumentException(
                            "VIRTUAL_SYSTEM_UPDATE_PAYLOAD_UNSUPPORTED:" + item.getClass());
                }
            }
            return out;
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(cause);
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("VIRTUAL_SYSTEM_UPDATE_PAYLOAD_INVALID", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("VIRTUAL_SYSTEM_UPDATE_PAYLOAD_INVALID", error);
        }
    }

    private static void putBundleArray(Bundle target, String key, Object value) {
        try {
            java.lang.reflect.Method method = Bundle.class.getMethod(
                    bundleArrayMethod(value.getClass()), String.class, value.getClass());
            method.invoke(target, key, value);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy
                    .rethrowIfFatal(cause);
            throw new IllegalArgumentException("VIRTUAL_SYSTEM_UPDATE_PAYLOAD_INVALID", cause);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            throw new IllegalArgumentException("VIRTUAL_SYSTEM_UPDATE_PAYLOAD_UNSUPPORTED_ARRAY",
                    error);
        }
    }

    private static String bundleArrayMethod(Class<?> type) {
        if (type == String[].class) return "putStringArray";
        if (type == int[].class) return "putIntArray";
        if (type == long[].class) return "putLongArray";
        if (type == boolean[].class) return "putBooleanArray";
        if (type == double[].class) return "putDoubleArray";
        throw new IllegalArgumentException("VIRTUAL_SYSTEM_UPDATE_PAYLOAD_UNSUPPORTED_ARRAY:" + type);
    }

    private static int firstInt(Object[] arguments, int fallback) {
        if (arguments == null) return fallback;
        for (Object value : arguments) if (value instanceof Number number) return number.intValue();
        return fallback;
    }

    private static boolean firstBoolean(Object[] arguments, boolean fallback) {
        if (arguments == null) return fallback;
        for (Object value : arguments) if (value instanceof Boolean flag) return flag;
        return fallback;
    }

    private static Decision adaptableSessionResult(
            String domain, Method method, Object token, Set<Object> registry) {
        Class<?> type = method.getReturnType();
        if (type == void.class || type == Void.class) return Decision.handled(null);
        if (type == boolean.class || type == Boolean.class) return Decision.handled(true);
        if (type == int.class || type == Integer.class) return Decision.handled(0);
        if (type == long.class || type == Long.class) return Decision.handled(0L);
        if (type == String.class || type == Object.class) return Decision.handled(token.toString());
        registry.remove(token);
        throw new IllegalStateException("VIRTUAL_" + domain + "_RESULT_ADAPTER_REQUIRED:"
                + type.getName());
    }

    private static Decision unsupported(String domain, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT)
                + "_OPERATION_UNSUPPORTED:" + method.getName());
    }

    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object result) { return new Decision(true, result); }
    }

    private record SyntheticToken(int id) { }
}
