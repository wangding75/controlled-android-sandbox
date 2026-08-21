package com.warden.controlledsandbox.framework.core;

import android.os.Bundle;
import java.lang.reflect.Method;

/** AccountManager callback/token boundary owned by the Guest account authority. */
public final class AccountAuthenticatorBoundary {
    public static final int ERROR_CODE_TOKEN_MISSING = 5;
    public static final int ERROR_CODE_AUTHENTICATOR_DEFERRED = 6;

    private AccountAuthenticatorBoundary() { }

    /** Completes a getAuthToken-style callback without invoking the Host AccountManager. */
    public static boolean completeToken(Object callback, Object account, String token) {
        if (callback == null) return false;
        if (token == null || token.isEmpty()) {
            return invokeError(callback, ERROR_CODE_TOKEN_MISSING, "VIRTUAL_ACCOUNT_TOKEN_MISSING");
        }
        Bundle result = new Bundle();
        result.putString("authtoken", token);
        String name = member(account, "name", "mName", "getName");
        String type = member(account, "type", "mType", "getType");
        if (name != null) result.putString("authAccount", name);
        if (type != null) result.putString("accountType", type);
        return invokeResult(callback, result);
    }

    /** Explicitly refuses authenticator operations that require a real authenticator service. */
    public static boolean deferAuthenticator(Object callback) {
        return callback != null && invokeError(callback, ERROR_CODE_AUTHENTICATOR_DEFERRED,
                "DEFERRED_ACCOUNT_AUTHENTICATOR");
    }

    public static boolean isCallback(Object value) {
        if (value == null) return false;
        String name = value.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("accountmanagerresponse") || name.contains("authenticatorresponse")) {
            return true;
        }
        for (Method method : value.getClass().getMethods()) {
            if (method.getName().equals("onResult") || method.getName().equals("onError")) return true;
        }
        return false;
    }

    private static boolean invokeResult(Object callback, Bundle result) {
        try {
            Method method = find(callback.getClass(), "onResult", 1);
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(callback, result);
            return true;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        }
    }

    private static boolean invokeError(Object callback, int code, String message) {
        try {
            Method method = find(callback.getClass(), "onError", 2);
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(callback, code, message);
            return true;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        }
    }

    private static Method find(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
        }
        Class<?> cursor = type;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) return method;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static String member(Object value, String... names) {
        if (value == null) return null;
        Class<?> cursor = value.getClass();
        for (String name : names) {
            try {
                if (name.startsWith("get")) {
                    Method method = cursor.getMethod(name);
                    method.setAccessible(true);
                    Object result = method.invoke(value);
                    if (result != null) return String.valueOf(result);
                } else {
                    java.lang.reflect.Field field = findField(cursor, name);
                    if (field != null) {
                        field.setAccessible(true);
                        Object result = field.get(value);
                        if (result != null) return String.valueOf(result);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }
}
