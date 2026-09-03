package com.warden.controlledsandbox.framework.core;

import android.os.Build;

import com.warden.controlledsandbox.contract.VirtualDisplayProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDisplaySnapshot;
import com.warden.controlledsandbox.contract.VirtualInputMethodProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualInteractionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualWindowPolicySnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.GuestInteractionState;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Source-side Window/ActivityClient/InputMethod/Display routing and ownership enforcement. */
final class InteractionServiceInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Object delegate;

    InteractionServiceInvocationInterceptor(GuestIdentity identity, String service, Object delegate) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
        this.delegate = delegate;
    }

    Call before(Method method, Object[] arguments) {
        VirtualInteractionProfileSnapshot profile;
        try {
            profile = identity.virtualServices().interactionProfile();
        } catch (IllegalStateException unavailable) {
            if ("VIRTUAL_INTERACTION_PROFILE_AUTHORITY_REQUIRED".equals(unavailable.getMessage())
                    || "VIRTUAL_INTERACTION_PROFILE_NOT_AVAILABLE".equals(unavailable.getMessage())) {
                return Call.passThrough();
            }
            throw unavailable;
        }
        return switch (service) {
            case "window" -> window(method, arguments, profile);
            case "windowsession" -> windowSession(method, arguments, profile);
            case "activityclient" -> activityClient(method, arguments, profile);
            case "inputmethod" -> inputMethod(method, arguments, profile);
            case "display" -> display(method, arguments, profile);
            default -> Call.passThrough();
        };
    }

    private Call window(Method method, Object[] arguments, VirtualInteractionProfileSnapshot profile) {
        String name = normalize(method.getName());
        VirtualWindowPolicySnapshot policy = profile.window();
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(policy.mode())) return Call.passThrough();
        if (VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(policy.mode())) {
            if (name.contains("rotation") || name.contains("displaysize")) {
                return displayMetric(method, arguments, profile.display());
            }
            throw new SecurityException("VIRTUAL_WINDOW_BLOCKED:" + method.getName());
        }
        if (name.equals("opensession") || name.contains("opensession")) {
            return Call.after(result -> {
                if (result == null) throw new IllegalStateException("VIRTUAL_WINDOW_SESSION_MISSING");
                identity.interactions().windows().openSession(result);
                return ReflectiveServiceHook.createProxy(result, identity, "windowSession");
            });
        }
        if (name.contains("screenCaptureDisabled".toLowerCase(Locale.ROOT))
                || name.contains("setscreencapturedisabled")) {
            if (!policy.allowScreenCaptureControl()) {
                throw new SecurityException("VIRTUAL_SCREEN_CAPTURE_CONTROL_DENIED");
            }
        }
        if (name.contains("rotation") || name.contains("displaysize")) {
            return displayMetric(method, arguments, profile.display());
        }
        if (name.startsWith("addapptoken") || name.startsWith("removeapptoken")) {
            return Call.handled(defaultValue(method.getReturnType()));
        }
        return Call.passThrough();
    }

    private Call windowSession(Method method, Object[] arguments,
            VirtualInteractionProfileSnapshot profile) {
        String name = normalize(method.getName());
        VirtualWindowPolicySnapshot policy = profile.window();
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(policy.mode())) return Call.passThrough();
        if (VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(policy.mode())) {
            throw new SecurityException("VIRTUAL_WINDOW_SESSION_BLOCKED:" + method.getName());
        }
        InteractionObjectRewriter.RewriteScope scope = InteractionObjectRewriter.rewrite(
                arguments, identity, policy, profile.inputMethod());
        GuestInteractionState.WindowState state = identity.interactions().windows();
        Object token = windowToken(method, arguments);
        int displayId = firstInt(arguments, profile.display().defaultDisplayId());
        int type = layoutType(arguments);
        if (startsAny(name, "addtodisplay", "addwithoutinputchannel", "add")) {
            state.register(delegate, token, displayId, type, policy.maximumWindows());
            return Call.passThrough(scope, null, () -> state.remove(delegate, token));
        }
        if (name.startsWith("relayout")) {
            if (token == null || !state.owns(delegate, token)) {
                scope.close();
                throw new SecurityException("VIRTUAL_WINDOW_TOKEN_NOT_OWNED");
            }
            return Call.passThrough(scope, result -> {
                state.relayout(delegate, token, displayId, type);
                return result;
            }, null);
        }
        if (startsAny(name, "remove", "removewindow")) {
            if (token == null || !state.owns(delegate, token)) {
                scope.close();
                throw new SecurityException("VIRTUAL_WINDOW_TOKEN_NOT_OWNED");
            }
            return Call.passThrough(scope, result -> {
                state.remove(delegate, token);
                return result;
            }, null);
        }
        if (startsAny(name, "close", "killsession")) {
            return Call.passThrough(scope, result -> {
                state.closeSession(delegate);
                return result;
            }, null);
        }
        if (name.contains("finishdrawing") || name.contains("outofmemory")
                || name.contains("performhapticfeedback") || name.contains("wallpaperoffsets")) {
            return Call.passThrough(scope);
        }
        return Call.passThrough(scope);
    }

    private Call activityClient(Method method, Object[] arguments,
            VirtualInteractionProfileSnapshot profile) {
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(profile.window().mode())) {
            return Call.passThrough();
        }
        String name = normalize(method.getName());
        Object token = firstToken(arguments);
        GuestInteractionState.ActivityClientState state = identity.interactions().activities();
        String event = name.contains("resumed") ? "RESUMED"
                : name.contains("paused") ? "PAUSED"
                : name.contains("stopped") ? "STOPPED"
                : name.contains("destroyed") ? "DESTROYED"
                : name.startsWith("finishactivity") ? "FINISHED"
                : name.contains("topresumed") ? "TOP_RESUMED" : "";
        Object description = name.contains("settaskdescription")
                ? objectAfter(arguments, token) : null;
        InteractionObjectRewriter.RewriteScope scope = InteractionObjectRewriter.rewrite(
                arguments, identity, profile.window(), profile.inputMethod());
        return Call.passThrough(scope, result -> {
            if (!event.isEmpty()) state.event(token, event);
            if (description != null) {
                state.taskDescription(token, FrameworkInteractionObjectFactory.taskDescription(description));
            }
            return result;
        }, null);
    }

    private Call inputMethod(Method method, Object[] arguments,
            VirtualInteractionProfileSnapshot profile) {
        String name = normalize(method.getName());
        VirtualInputMethodProfileSnapshot policy = profile.inputMethod();
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(policy.mode())) return Call.passThrough();
        if (VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(policy.mode())) {
            if (isCleanup(name)) return Call.handled(successValue(method.getReturnType()));
            if (name.contains("list") || name.startsWith("getinputmethod") || name.startsWith("getenabled")) {
                return Call.handled(emptyInputMethodResult(method.getReturnType()));
            }
            return Call.handled(falseValue(method.getReturnType()));
        }
        if (name.contains("showinputmethodpicker") || name.contains("showinputmethodandsubtypeenabler")) {
            if (!policy.allowPicker()) return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("getinputmethodlist") || name.startsWith("getenabledinputmethodlist")
                || name.startsWith("getinputmethodsubtypelist") || name.contains("shortcutinputmethods")) {
            return Call.handled(emptyInputMethodResult(method.getReturnType()));
        }
        if (name.contains("getcurrentinputmethodinfo") || name.contains("getlastinputmethodsubtype")) {
            return Call.handled(null);
        }
        InteractionObjectRewriter.RewriteScope scope = InteractionObjectRewriter.rewrite(
                arguments, identity, profile.window(), policy);
        Object client = inputClient(arguments);
        Object focus = focusToken(arguments);
        GuestInteractionState.InputMethodState state = identity.interactions().inputMethods();
        if (name.contains("startinput") || name.contains("windowgainedfocus")) {
            boolean existed = state.active(client);
            state.start(client, focus, policy.maximumSessions());
            return Call.passThrough(scope, null, existed ? null : () -> state.finish(client));
        }
        if (name.contains("finishinput") || name.contains("removeclient")) {
            return Call.passThrough(scope, result -> {
                state.finish(client);
                return result;
            }, null);
        }
        return Call.passThrough(scope);
    }

    private Call display(Method method, Object[] arguments,
            VirtualInteractionProfileSnapshot profile) {
        String name = normalize(method.getName());
        VirtualDisplayProfileSnapshot policy = profile.display();
        if (VirtualWindowPolicySnapshot.MODE_HOST.equals(policy.mode())) return Call.passThrough();
        if (VirtualWindowPolicySnapshot.MODE_BLOCKED.equals(policy.mode())) {
            if (name.contains("getdisplayids")) return Call.handled(new int[0]);
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.contains("getdisplayids")) {
            int[] ids = new int[policy.displays().size()];
            for (int index = 0; index < ids.length; index++) ids[index] = policy.displays().get(index).displayId();
            return Call.handled(ids);
        }
        if (name.contains("getdisplayinfo")) {
            VirtualDisplaySnapshot display = policy.display(firstInt(arguments, policy.defaultDisplayId()));
            return Call.handled(display == null ? null
                    : FrameworkInteractionObjectFactory.displayInfo(method.getReturnType(), display));
        }
        if (name.contains("getstabledisplaysize") || name.contains("getdisplaysize")) {
            return displayMetric(method, arguments, policy);
        }
        if (name.contains("createvirtualdisplay")) {
            if (!policy.allowCreateVirtualDisplay() || policy.maximumVirtualDisplays() == 0) {
                throw new SecurityException("VIRTUAL_DISPLAY_CREATE_DENIED");
            }
            Object callback = lastComplex(arguments);
            String displayName = firstString(arguments);
            GuestInteractionState.DisplayState state = identity.interactions().displays();
            boolean existed = state.owns(callback);
            state.reserve(callback, displayName, policy.maximumVirtualDisplays());
            InteractionObjectRewriter.RewriteScope scope = InteractionObjectRewriter.rewrite(
                    arguments, identity, profile.window(), profile.inputMethod());
            return Call.passThrough(scope, null, existed ? null : () -> state.release(callback));
        }
        if (name.contains("releasevirtualdisplay")) {
            Object callback = lastComplex(arguments);
            return Call.passThrough(InteractionObjectRewriter.RewriteScope.EMPTY, result -> {
                identity.interactions().displays().release(callback);
                return result;
            }, null);
        }
        if (containsAny(name, "setbrightness", "settemporarybrightness", "requestcolor")) {
            throw new SecurityException("VIRTUAL_DISPLAY_MUTATION_DENIED:" + method.getName());
        }
        return Call.passThrough();
    }

    private Call displayMetric(Method method, Object[] arguments, VirtualDisplayProfileSnapshot policy) {
        VirtualDisplaySnapshot display = policy.display(policy.defaultDisplayId());
        if (display == null) return Call.handled(defaultValue(method.getReturnType()));
        if (normalize(method.getName()).contains("rotation")) return Call.handled(display.rotation());
        Object out = pointLike(arguments);
        if (out != null && FrameworkInteractionObjectFactory.populatePoint(
                out, display.widthPixels(), display.heightPixels())) {
            return Call.handled(defaultValue(method.getReturnType()));
        }
        return Call.handled(FrameworkInteractionObjectFactory.point(
                method.getReturnType(), display.widthPixels(), display.heightPixels()));
    }

    private static Object windowToken(Method method, Object[] arguments) {
        if (method != null && arguments != null) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            int count = Math.min(parameterTypes.length, arguments.length);
            for (int index = 0; index < count; index++) {
                String typeName = parameterTypes[index].getName();
                if ("android.view.IWindow".equals(typeName)
                        || typeName.endsWith(".IWindow")) {
                    return arguments[index];
                }
            }
        }
        return windowToken(arguments);
    }

    private static Object windowToken(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || isSimple(value)) continue;
            String name = value.getClass().getName();
            if (name.contains("LayoutParams") || name.contains("Insets") || name.contains("InputChannel")) continue;
            if (name.contains("Window") || name.contains("Binder") || name.contains("Token")) return value;
        }
        return firstToken(arguments);
    }
    private static Object firstToken(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || isSimple(value)) continue;
            String name = value.getClass().getName();
            if (name.contains("LayoutParams") || name.contains("TaskDescription")
                    || name.contains("EditorInfo") || name.contains("Configuration")) continue;
            return value;
        }
        return null;
    }
    private static Object inputClient(Object[] arguments) { return firstToken(arguments); }
    private static Object focusToken(Object[] arguments) {
        if (arguments == null) return null;
        Object first = null;
        for (Object value : arguments) {
            if (value == null || isSimple(value)) continue;
            String name = value.getClass().getName();
            if (name.contains("EditorInfo") || name.contains("InputConnection")
                    || name.contains("Attribution")) continue;
            if (first == null) first = value; else return value;
        }
        return first;
    }
    private static Object pointLike(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null) continue;
            String name = value.getClass().getName();
            if (name.endsWith("Point") || hasField(value.getClass(), "x") && hasField(value.getClass(), "y")) return value;
        }
        return null;
    }
    private static Object lastComplex(Object[] arguments) {
        if (arguments == null) return null;
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object value = arguments[index];
            if (value != null && !isSimple(value)) return value;
        }
        return null;
    }
    private static Object objectAfter(Object[] arguments, Object token) {
        if (arguments == null) return null;
        boolean seen = token == null;
        for (Object value : arguments) {
            if (!seen) { if (value == token) seen = true; continue; }
            if (value != null && !isSimple(value)) return value;
        }
        return null;
    }
    private static int layoutType(Object[] arguments) {
        if (arguments == null) return 0;
        for (Object value : arguments) {
            if (value == null) continue;
            String name = value.getClass().getName();
            if (name.contains("LayoutParams") || hasField(value.getClass(), "type")) {
                return FrameworkInteractionObjectFactory.intMember(value, 0, "type", "mType");
            }
        }
        return 0;
    }
    private static int firstInt(Object[] arguments, int fallback) {
        if (arguments != null) for (Object value : arguments) if (value instanceof Integer) return (Integer) value;
        return fallback;
    }
    private static String firstString(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) if (value instanceof String) return (String) value;
        return "";
    }
    private static boolean hasField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { cursor.getDeclaredField(name); return true; }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return false;
    }
    private static boolean isSimple(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value.getClass().isEnum() || value.getClass().isArray() && value.getClass().getComponentType().isPrimitive();
    }
    private static boolean isCleanup(String name) {
        return startsAny(name, "finish", "remove", "hide", "close", "unbind");
    }
    private static Object emptyCollection(Class<?> type) {
        if (List.class.isAssignableFrom(type)) return List.of();
        if (java.util.Set.class.isAssignableFrom(type)) return Collections.emptySet();
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        return null;
    }
    private static Object emptyInputMethodResult(Class<?> type) {
        if (Build.VERSION.SDK_INT >= 35 && type != null
                && "com.android.internal.inputmethod.InputMethodInfoSafeList".equals(type.getName())) {
            HiddenApiAccess.ensureExemptions();
            try {
                Method empty = type.getDeclaredMethod("empty");
                empty.setAccessible(true);
                Object result = empty.invoke(null);
                if (type.isInstance(result)) return result;
                throw new IllegalStateException("INPUT_METHOD_SAFE_LIST_EMPTY_TYPE_MISMATCH");
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException("INPUT_METHOD_SAFE_LIST_EMPTY_UNAVAILABLE", error);
            }
        }
        return emptyCollection(type);
    }
    private static Object successValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }
    private static Object falseValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        return defaultValue(type);
    }
    private static Object defaultValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0f;
        if (type == double.class || type == Double.class) return 0d;
        if (List.class.isAssignableFrom(type)) return List.of();
        if (java.util.Set.class.isAssignableFrom(type)) return Collections.emptySet();
        if (type.isArray()) return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        return null;
    }
    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    private static boolean startsAny(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    interface After { Object apply(Object value); }

    static final class Call implements AutoCloseable {
        private static final After IDENTITY = value -> value;
        private final boolean handled;
        private final Object result;
        private final InteractionObjectRewriter.RewriteScope scope;
        private final After after;
        private final Runnable failure;
        private boolean failed;
        private boolean closed;

        private Call(boolean handled, Object result,
                InteractionObjectRewriter.RewriteScope scope, After after, Runnable failure) {
            this.handled = handled; this.result = result;
            this.scope = scope == null ? InteractionObjectRewriter.RewriteScope.EMPTY : scope;
            this.after = after == null ? IDENTITY : after;
            this.failure = failure;
        }
        static Call handled(Object result) { return new Call(true, result, null, null, null); }
        static Call passThrough() { return new Call(false, null, null, null, null); }
        static Call passThrough(InteractionObjectRewriter.RewriteScope scope) {
            return new Call(false, null, scope, null, null);
        }
        static Call passThrough(InteractionObjectRewriter.RewriteScope scope,
                After after, Runnable failure) {
            return new Call(false, null, scope, after, failure);
        }
        static Call after(After after) { return new Call(false, null, null, after, null); }
        boolean handled() { return handled; }
        Object result() { return result; }
        Object after(Object value) { return after.apply(value); }
        synchronized void onFailure() {
            if (failed) return;
            failed = true;
            if (failure != null) failure.run();
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true; scope.close();
        }
    }
}
