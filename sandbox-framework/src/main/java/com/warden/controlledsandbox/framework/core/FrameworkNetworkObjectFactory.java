package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualNetworkSnapshot;
import com.warden.controlledsandbox.contract.VirtualProxyProfileSnapshot;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Version-tolerant reflective construction for network framework result objects. */
final class FrameworkNetworkObjectFactory {
    private FrameworkNetworkObjectFactory() { }

    static Object network(Class<?> type, VirtualNetworkSnapshot value) {
        Object out = construct(type, value.networkId());
        write(out, new String[]{"setNetId"}, new String[]{"netId", "mNetId", "networkId"}, value.networkId());
        return out;
    }

    static Object capabilities(Class<?> type, VirtualNetworkSnapshot value) {
        Object out = construct(type, null);
        invokeInt(out, "addTransportType", transport(value.transport()));
        if (value.connected()) invokeInt(out, "addCapability", 12); // INTERNET
        if (value.validated()) invokeInt(out, "addCapability", 16); // VALIDATED
        if (!value.metered()) invokeInt(out, "addCapability", 11); // NOT_METERED
        if (!value.roaming()) invokeInt(out, "addCapability", 18); // NOT_ROAMING
        if (value.captivePortal()) invokeInt(out, "addCapability", 17);
        write(out, new String[]{"setLinkDownstreamBandwidthKbps"},
                new String[]{"mLinkDownBandwidthKbps", "downstreamKbps"}, value.downstreamKbps());
        write(out, new String[]{"setLinkUpstreamBandwidthKbps"},
                new String[]{"mLinkUpBandwidthKbps", "upstreamKbps"}, value.upstreamKbps());
        write(out, new String[0], new String[]{"transport", "transportType"}, value.transport());
        write(out, new String[0], new String[]{"connected"}, value.connected());
        write(out, new String[0], new String[]{"validated"}, value.validated());
        write(out, new String[0], new String[]{"metered"}, value.metered());
        return out;
    }

    static Object linkProperties(Class<?> type, VirtualNetworkSnapshot value) {
        Object out = construct(type, null);
        write(out, new String[]{"setInterfaceName"}, new String[]{"mIfaceName", "interfaceName"}, value.interfaceName());
        write(out, new String[]{"setMtu"}, new String[]{"mMtu", "mtu"}, value.mtu());
        write(out, new String[]{"setDomains"}, new String[]{"mDomains", "domains"}, value.domains());
        write(out, new String[0], new String[]{"addresses", "mAddresses"}, value.addresses());
        write(out, new String[0], new String[]{"routes", "mRoutes"}, value.routes());
        write(out, new String[0], new String[]{"dnsServers", "mDnses"}, inetAddresses(value.dnsServers()));
        return out;
    }

    static Object networkInfo(Class<?> type, VirtualNetworkSnapshot value) {
        Object out = construct(type, null);
        write(out, new String[0], new String[]{"mNetworkType", "type"}, legacyType(value.transport()));
        write(out, new String[0], new String[]{"mTypeName", "typeName"}, value.transport());
        write(out, new String[0], new String[]{"mIsAvailable", "available"}, value.connected());
        write(out, new String[0], new String[]{"mIsRoaming", "roaming"}, value.roaming());
        write(out, new String[0], new String[]{"mState", "state"}, value.connected() ? "CONNECTED" : "DISCONNECTED");
        write(out, new String[0], new String[]{"mDetailedState", "detailedState"},
                value.connected() ? "CONNECTED" : "DISCONNECTED");
        return out;
    }

    static Object proxyInfo(Class<?> type, VirtualProxyProfileSnapshot value) {
        if (VirtualProxyProfileSnapshot.NONE.equals(value.type())) return null;
        Object out = construct(type, null);
        write(out, new String[]{"setHost"}, new String[]{"mHost", "host"}, value.host());
        write(out, new String[]{"setPort"}, new String[]{"mPort", "port"}, value.port());
        write(out, new String[0], new String[]{"mExclusionList", "exclusionList"}, value.exclusionList());
        write(out, new String[0], new String[]{"mPacFileUrl", "pacUrl"}, value.pacUrl());
        return out;
    }

    static Object networkArray(Class<?> returnType, List<VirtualNetworkSnapshot> values) {
        if (!returnType.isArray()) return Collections.unmodifiableList(new ArrayList<>(values));
        Class<?> component = returnType.getComponentType();
        Object array = Array.newInstance(component, values.size());
        for (int index = 0; index < values.size(); index++) Array.set(array, index, network(component, values.get(index)));
        return array;
    }

    static Object networkInfoArray(Class<?> returnType, List<VirtualNetworkSnapshot> values) {
        if (!returnType.isArray()) return Collections.unmodifiableList(new ArrayList<>(values));
        Class<?> component = returnType.getComponentType();
        Object array = Array.newInstance(component, values.size());
        for (int index = 0; index < values.size(); index++) Array.set(array, index, networkInfo(component, values.get(index)));
        return array;
    }

    private static List<InetAddress> inetAddresses(List<String> values) {
        List<InetAddress> out = new ArrayList<>();
        for (String value : values) {
            try { out.add(InetAddress.getByName(value)); } catch (Exception ignored) { }
        }
        return out;
    }
    private static int transport(String value) {
        return switch (value) {
            case VirtualNetworkSnapshot.CELLULAR -> 0;
            case VirtualNetworkSnapshot.WIFI -> 1;
            case VirtualNetworkSnapshot.BLUETOOTH -> 2;
            case VirtualNetworkSnapshot.ETHERNET -> 3;
            case VirtualNetworkSnapshot.VPN -> 4;
            default -> -1;
        };
    }
    private static int legacyType(String value) {
        return switch (value) {
            case VirtualNetworkSnapshot.CELLULAR -> 0;
            case VirtualNetworkSnapshot.WIFI -> 1;
            case VirtualNetworkSnapshot.ETHERNET -> 9;
            case VirtualNetworkSnapshot.VPN -> 17;
            default -> -1;
        };
    }

    private static Object construct(Class<?> type, Integer intArgument) {
        if (type == null || type == Object.class) return new Object();
        try {
            if (intArgument != null) {
                try {
                    Constructor<?> c = type.getDeclaredConstructor(int.class);
                    c.setAccessible(true); return c.newInstance(intArgument);
                } catch (NoSuchMethodException ignored) { }
                try {
                    Constructor<?> c = type.getDeclaredConstructor(long.class);
                    c.setAccessible(true); return c.newInstance(intArgument.longValue());
                } catch (NoSuchMethodException ignored) { }
            }
            Constructor<?> c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance();
        } catch (Throwable error) {
            Object allocated = allocate(type);
            if (allocated != null) return allocated;
            throw new IllegalStateException("VIRTUAL_NETWORK_OBJECT_CONSTRUCTION_UNSUPPORTED:" + type.getName(), error);
        }
    }
    private static Object allocate(Class<?> type) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
        } catch (Throwable ignored) { return null; }
    }
    private static void invokeInt(Object target, String name, int value) {
        if (target == null || value < 0) return;
        try { Method method = target.getClass().getMethod(name, int.class); method.setAccessible(true); method.invoke(target, value); }
        catch (Throwable ignored) { }
    }
    private static void write(Object target, String[] setters, String[] fields, Object value) {
        if (target == null) return;
        for (String name : setters) {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
                try { method.setAccessible(true); method.invoke(target, convert(value, method.getParameterTypes()[0])); return; }
                catch (Throwable ignored) { }
            }
        }
        for (String name : fields) {
            Field field = findField(target.getClass(), name);
            if (field == null) continue;
            try { field.setAccessible(true); field.set(target, convert(value, field.getType())); return; }
            catch (Throwable ignored) { }
        }
    }
    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }
    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) return value;
        if (value instanceof Number n) {
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
        }
        return value;
    }
}
