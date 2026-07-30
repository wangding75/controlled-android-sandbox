package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualConnectivityProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDnsRecordSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkServiceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualVpnProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Connectivity, DNS, proxy and VPN result projection with bounded process-local ownership. */
final class NetworkServiceInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;

    NetworkServiceInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    Decision before(Method method, Object[] arguments) {
        if (method == null) return Decision.passThrough();
        VirtualNetworkServiceProfileSnapshot profile;
        try { profile = identity.virtualServices().networkServiceProfile(); }
        catch (IllegalStateException unavailable) { return Decision.passThrough(); }
        return switch (service) {
            case "connectivity" -> connectivity(method, arguments, profile.connectivity(), profile.proxy());
            case "dnsresolver" -> dns(method, arguments, profile.dns());
            case "vpn" -> vpn(method, arguments, profile.vpn());
            default -> Decision.passThrough();
        };
    }

    private Decision connectivity(Method method, Object[] arguments,
            VirtualConnectivityProfileSnapshot profile, VirtualProxyProfileSnapshot proxy) {
        String name = normalize(method.getName());
        if (!isConnectivityOperation(name)) return Decision.passThrough();
        if (containsAny(name, "getglobalproxy", "getdefaultproxy", "getproxyfornetwork")) {
            requireMode(proxy.mode(), "proxy", name);
            if (VirtualLocationProfileSnapshot.MODE_HOST.equals(proxy.mode())) return Decision.passThrough();
            return Decision.handled(FrameworkNetworkObjectFactory.proxyInfo(method.getReturnType(), proxy));
        }
        requireMode(profile.mode(), "connectivity", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        VirtualNetworkSnapshot network = network(profile, arguments);
        if (containsAny(name, "getactivenetwork", "getdefaultnetwork") && !name.contains("info")) {
            return Decision.handled(network == null ? null
                    : FrameworkNetworkObjectFactory.network(method.getReturnType(), network));
        }
        if (name.contains("getallnetworks")) {
            return Decision.handled(FrameworkNetworkObjectFactory.networkArray(
                    method.getReturnType(), profile.networks()));
        }
        if (name.contains("getnetworkcapabilities")) {
            return Decision.handled(network == null ? null
                    : FrameworkNetworkObjectFactory.capabilities(method.getReturnType(), network));
        }
        if (name.contains("getlinkproperties")) {
            return Decision.handled(network == null ? null
                    : FrameworkNetworkObjectFactory.linkProperties(method.getReturnType(), network));
        }
        if (name.contains("getallnetworkinfo")) {
            return Decision.handled(FrameworkNetworkObjectFactory.networkInfoArray(
                    method.getReturnType(), profile.networks()));
        }
        if (name.contains("getactivenetworkinfo") || name.equals("getnetworkinfo")) {
            return Decision.handled(network == null ? null
                    : FrameworkNetworkObjectFactory.networkInfo(method.getReturnType(), network));
        }
        if (name.contains("isactivenetworkmetered")) {
            return Decision.handled(network == null || network.metered());
        }
        if (name.contains("getrestrictbackgroundstatus")) {
            return Decision.handled(profile.backgroundRestricted() ? 3 : 1);
        }
        if (name.contains("getmultipathpreference")) return Decision.handled(0);
        if (containsAny(name, "unregisternetworkcallback", "releasenetworkrequest", "unlisten")) {
            Object callback = callback(arguments);
            if (callback != null) identity.networks().releaseCallback(callback);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "registerdefaultnetworkcallback", "registernetworkcallback",
                "requestnetwork", "requestbackgroundnetwork", "listenfornetwork")) {
            Object callback = callback(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_NETWORK_CALLBACK_REQUIRED");
            boolean created = identity.networks().reserveCallback(callback, profile.maximumCallbacks());
            try {
                if (network != null) dispatchNetworkAvailable(callback, network);
            } catch (RuntimeException error) {
                if (created) identity.networks().releaseCallback(callback);
                throw error;
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "bindprocesstonetwork", "setprocessdefaultnetwork", "setairplanemode",
                "starttethering", "stoptethering", "setglobalproxy", "setacceptunvalidated")) {
            throw new SecurityException("VIRTUAL_CONNECTIVITY_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("connectivity", method);
    }

    private Decision dns(Method method, Object[] arguments, VirtualDnsProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isDnsOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "dns", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "getdnsservers", "getresolverinfo")) {
            return Decision.handled(stringResult(method.getReturnType(), profile.servers()));
        }
        if (containsAny(name, "query", "rawquery")) {
            if (name.contains("raw") && !profile.allowRawQueries()) {
                throw new SecurityException("VIRTUAL_DNS_RAW_QUERY_DENIED");
            }
            String hostname = hostname(arguments);
            String type = dnsType(arguments);
            Object callback = callback(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_DNS_CALLBACK_REQUIRED");
            VirtualDnsRecordSnapshot record = profile.record(hostname, type);
            if (record == null) dispatchDnsError(callback, 3); // NXDOMAIN
            else dispatchDnsAnswer(callback, record);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (startsAny(name, "set", "clear", "register", "unregister")) {
            throw new SecurityException("VIRTUAL_DNS_MUTATION_DENIED:" + method.getName());
        }
        return failUnsupported("dns", method);
    }

    private Decision vpn(Method method, Object[] arguments, VirtualVpnProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (!isVpnOperation(name)) return Decision.passThrough();
        requireMode(profile.mode(), "vpn", name);
        if (VirtualLocationProfileSnapshot.MODE_HOST.equals(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "getvpnstate", "getstate")) return Decision.handled(profile.state());
        if (containsAny(name, "getalwaysonvpnpackage", "getalwaysonpackage")) {
            return Decision.handled(emptyToNull(profile.alwaysOnPackage()));
        }
        if (containsAny(name, "isvpnlockdownenabled", "islockdownenabled")) {
            return Decision.handled(profile.lockdown());
        }
        if (containsAny(name, "getvpnlockdownallowlist", "getlockdownallowlist")) {
            return Decision.handled(Collections.unmodifiableList(profile.lockdownAllowlist()));
        }
        if (name.contains("isalwaysonvpnpackagesupported")) return Decision.handled(true);
        if (containsAny(name, "preparevpn", "provisionvpnprofile")) {
            return Decision.handled(profile.allowProvisioning());
        }
        if (containsAny(name, "establishvpn", "startvpn", "startlegacyvpn", "startprovisionedvpnprofile")) {
            if (!profile.allowEstablish()) throw new SecurityException("VIRTUAL_VPN_ESTABLISH_DENIED");
            Object token = sessionToken(arguments, method);
            identity.networks().reserveVpnSession(token, profile.maximumSessions());
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "stopvpn", "teardownvpn", "stopprovisionedvpnprofile")) {
            Object token = callback(arguments);
            if (token == null) identity.networks().clearVpnSessions();
            else identity.networks().releaseVpnSession(token);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "setalwaysonvpnpackage", "setvpnpackageauthorization", "setlockdown")) {
            throw new SecurityException("VIRTUAL_VPN_POLICY_MUTATION_DENIED:" + method.getName());
        }
        if (containsAny(name, "getvpnconfig", "getlegacyvpninfo", "getvpnprofile")) {
            return Decision.handled(vpnObject(method.getReturnType(), profile));
        }
        return failUnsupported("vpn", method);
    }

    private void dispatchNetworkAvailable(Object callback, VirtualNetworkSnapshot network) {
        invokeCallback(callback, "onAvailable", network);
        invokeCallback(callback, "onCapabilitiesChanged", network, "capabilities");
        invokeCallback(callback, "onLinkPropertiesChanged", network, "linkProperties");
        if (network.validated()) invokeCallback(callback, "onBlockedStatusChanged", network, false);
    }

    private static void invokeCallback(Object callback, String methodName,
            VirtualNetworkSnapshot network, Object... extras) {
        for (Method method : callback.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Object[] args = new Object[method.getParameterCount()];
            for (int index = 0; index < args.length; index++) {
                Class<?> type = method.getParameterTypes()[index];
                if (index == 0) args[index] = FrameworkNetworkObjectFactory.network(type, network);
                else if (type == boolean.class || type == Boolean.class) args[index] = extras.length == 0 ? false : extras[extras.length - 1];
                else if (type.getSimpleName().toLowerCase(Locale.ROOT).contains("capabil")) {
                    args[index] = FrameworkNetworkObjectFactory.capabilities(type, network);
                } else if (type.getSimpleName().toLowerCase(Locale.ROOT).contains("linkpropert")) {
                    args[index] = FrameworkNetworkObjectFactory.linkProperties(type, network);
                } else args[index] = null;
            }
            try { method.setAccessible(true); method.invoke(callback, args); return; }
            catch (Throwable error) { throw new IllegalStateException("VIRTUAL_NETWORK_CALLBACK_FAILED:" + methodName, error); }
        }
    }

    private static void dispatchDnsAnswer(Object callback, VirtualDnsRecordSnapshot record) {
        for (Method method : callback.getClass().getMethods()) {
            if (!method.getName().equals("onAnswer") && !method.getName().equals("onResponse")) continue;
            Object[] args = new Object[method.getParameterCount()];
            for (int index = 0; index < args.length; index++) {
                Class<?> type = method.getParameterTypes()[index];
                if (List.class.isAssignableFrom(type)) args[index] = record.values();
                else if (type == byte[].class) args[index] = record.values().get(0).getBytes(StandardCharsets.UTF_8);
                else if (type == int.class || type == Integer.class) args[index] = index == args.length - 1 ? 0 : record.ttlSeconds();
                else if (type == String.class) args[index] = record.values().get(0);
                else args[index] = record.values();
            }
            try { method.setAccessible(true); method.invoke(callback, args); return; }
            catch (Throwable error) { throw new IllegalStateException("VIRTUAL_DNS_CALLBACK_FAILED", error); }
        }
        throw new IllegalStateException("VIRTUAL_DNS_ANSWER_CALLBACK_UNSUPPORTED");
    }

    private static void dispatchDnsError(Object callback, int code) {
        for (Method method : callback.getClass().getMethods()) {
            if (!method.getName().equals("onError") && !method.getName().equals("onFailure")) continue;
            Object[] args = new Object[method.getParameterCount()];
            for (int index = 0; index < args.length; index++) {
                Class<?> type = method.getParameterTypes()[index];
                args[index] = type == int.class || type == Integer.class ? code : null;
            }
            try { method.setAccessible(true); method.invoke(callback, args); return; }
            catch (Throwable error) { throw new IllegalStateException("VIRTUAL_DNS_ERROR_CALLBACK_FAILED", error); }
        }
        throw new IllegalStateException("VIRTUAL_DNS_ERROR_CALLBACK_UNSUPPORTED");
    }

    private static Object vpnObject(Class<?> type, VirtualVpnProfileSnapshot profile) {
        if (type == void.class || type == Void.class) return null;
        try {
            Object value = type == Object.class ? new Object() : type.getDeclaredConstructor().newInstance();
            write(value, "state", profile.state()); write(value, "mState", profile.state());
            write(value, "interfaze", profile.interfaceName()); write(value, "interfaceName", profile.interfaceName());
            write(value, "addresses", profile.addresses()); write(value, "routes", profile.routes());
            write(value, "dnsServers", profile.dnsServers()); return value;
        } catch (Throwable ignored) { return null; }
    }
    private static void write(Object target, String fieldName, Object value) {
        if (target == null) return;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try { Field field = cursor.getDeclaredField(fieldName); field.setAccessible(true); field.set(target, value); return; }
            catch (Throwable ignored) { cursor = cursor.getSuperclass(); }
        }
    }

    private static VirtualNetworkSnapshot network(VirtualConnectivityProfileSnapshot profile, Object[] arguments) {
        int id = networkId(arguments);
        return id >= 0 ? profile.network(id) : profile.defaultNetwork();
    }
    private static int networkId(Object[] arguments) {
        if (arguments == null) return -1;
        for (Object value : arguments) {
            if (value instanceof Integer number && number >= 0) return number;
            if (value == null) continue;
            for (String name : new String[]{"getNetId", "getNetworkHandle"}) {
                try {
                    Method method = value.getClass().getMethod(name);
                    Object result = method.invoke(value);
                    if (result instanceof Number number) return number.intValue();
                } catch (Throwable ignored) { }
            }
            for (String name : new String[]{"netId", "mNetId", "networkId"}) {
                try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return ((Number) field.get(value)).intValue(); }
                catch (Throwable ignored) { }
            }
        }
        return -1;
    }
    private static Object callback(Object[] arguments) {
        if (arguments == null) return null;
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object value = arguments[index];
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value.getClass().isEnum() || value instanceof List<?>) continue;
            String type = value.getClass().getName().toLowerCase(Locale.ROOT);
            if (type.contains("networkrequest") || type.contains("network") && !type.contains("callback")
                    || type.contains("executor") || type.contains("attribution")) continue;
            return value;
        }
        return null;
    }
    private static Object sessionToken(Object[] arguments, Method method) {
        Object token = callback(arguments);
        if (token != null) return token;
        if (arguments != null) for (Object value : arguments) if (value != null && !(value instanceof String)
                && !(value instanceof Number) && !(value instanceof Boolean)) return value;
        return method;
    }
    private String hostname(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof String text) {
                String candidate = text.trim().toLowerCase(Locale.ROOT);
                if (!candidate.isEmpty() && !candidate.equals(identity.packageName().toLowerCase(Locale.ROOT))
                        && !candidate.equals(identity.hostPackageName().toLowerCase(Locale.ROOT))) return candidate;
            }
        }
        throw new IllegalArgumentException("VIRTUAL_DNS_HOSTNAME_REQUIRED");
    }
    private static String dnsType(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) if (value instanceof Integer number) {
            if (number == 28) return VirtualDnsRecordSnapshot.AAAA;
            if (number == 5) return VirtualDnsRecordSnapshot.CNAME;
        }
        return VirtualDnsRecordSnapshot.A;
    }
    private static Object stringResult(Class<?> type, List<String> values) {
        if (type.isArray() && type.getComponentType() == String.class) return values.toArray(new String[0]);
        if (List.class.isAssignableFrom(type)) return Collections.unmodifiableList(values);
        return values.isEmpty() ? null : values.get(0);
    }
    private static Object successValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (List.class.isAssignableFrom(type)) return List.of();
        return null;
    }
    private static void requireMode(String mode, String service, String operation) {
        if (VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode)) {
            throw new SecurityException("VIRTUAL_" + service.toUpperCase(Locale.ROOT) + "_BLOCKED:" + operation);
        }
    }
    private static Decision failUnsupported(String service, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + service.toUpperCase(Locale.ROOT)
                + "_SIGNATURE_UNSUPPORTED:" + method.getName());
    }
    private static boolean isConnectivityOperation(String name) {
        return containsAny(name, "network", "connect", "linkpropert", "capabil", "metered",
                "proxy", "tether", "airplane", "background", "multipath");
    }
    private static boolean isDnsOperation(String name) {
        return containsAny(name, "dns", "resolver", "query", "answer");
    }
    private static boolean isVpnOperation(String name) {
        return containsAny(name, "vpn", "lockdown", "alwayson");
    }
    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    private static boolean startsAny(String value, String... needles) {
        for (String needle : needles) if (value.startsWith(needle)) return true;
        return false;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    record Decision(boolean handled, Object result) {
        static Decision handled(Object value) { return new Decision(true, value); }
        static Decision passThrough() { return new Decision(false, null); }
    }
}
