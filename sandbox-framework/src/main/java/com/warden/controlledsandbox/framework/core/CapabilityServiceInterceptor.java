package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.capability.CapabilityAccessPolicy;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditEvent;
import com.warden.controlledsandbox.framework.capability.CapabilityAuditSink;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Method-level gate and resource tracking for camera, location and microphone Binder surfaces. */
public final class CapabilityServiceInterceptor {
    private static final AtomicLong EVENTS = new AtomicLong();
    private final GuestIdentity identity;
    private final String service;

    public CapabilityServiceInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    public Call before(Method method, Object[] arguments) {
        String operation = method.getName();
        String capability = capability(operation);
        if (capability.isEmpty()) return Call.NONE;
        Object token = resourceToken(arguments);
        if (isCleanup(operation)) return new Call(capability, operation, token, true, false);
        if (!identity.capabilityPolicy().allowed(capability)) {
            audit(capability, operation, "DENIED", identity.capabilityPolicy().explanation(capability));
            throw new SecurityException("GUEST_CAPABILITY_CALL_DENIED:" + capability + ":" + operation);
        }
        return new Call(capability, operation, token, false, isRegistration(operation));
    }

    public void afterSuccess(Call call, Object delegate, Object[] rewrittenArguments, Object result) {
        if (call == null || call == Call.NONE) return;
        if (call.cleanup) {
            boolean released = releaseTracked(call.token, rewrittenArguments);
            audit(call.capability, call.operation, "RELEASE_CALLED", released ? "TRACKED" : "UNTRACKED");
            return;
        }
        audit(call.capability, call.operation, "ALLOWED", "");
        if (!call.registration) return;
        Registration registration = registration(delegate, call, rewrittenArguments, result);
        if (registration != null) {
            identity.capabilityLeases().register(call.capability, registration.token, registration.cleanup);
            audit(call.capability, call.operation, "LEASE_REGISTERED",
                    registration.token.getClass().getName());
        }
    }

    private boolean releaseTracked(Object preferred, Object[] arguments) {
        if (preferred != null
                && identity.capabilityLeases().release(preferred, identity.capabilityAudit(), "EXPLICIT_RELEASE")) {
            return true;
        }
        if (arguments == null) return false;
        for (Object argument : arguments) {
            if (argument == null || argument == preferred) continue;
            if (identity.capabilityLeases().release(argument, identity.capabilityAudit(), "EXPLICIT_RELEASE")) {
                return true;
            }
        }
        return false;
    }

    private Registration registration(Object delegate, Call call, Object[] arguments, Object result) {
        if (CapabilityAccessPolicy.CAMERA.equals(call.capability) && result != null) {
            CapabilityLeaseRegistry.CleanupAction cleanup = cleanupAction(delegate, call, result);
            if (cleanup != null) return new Registration(result, cleanup);
        }
        if (arguments != null) {
            for (int index = arguments.length - 1; index >= 0; index--) {
                Object candidate = arguments[index];
                if (!isResourceCandidate(candidate)) continue;
                CapabilityLeaseRegistry.CleanupAction cleanup = cleanupAction(delegate, call, candidate);
                if (cleanup != null) return new Registration(candidate, cleanup);
            }
        }
        Object candidate = call.token;
        if (candidate == null) return null;
        CapabilityLeaseRegistry.CleanupAction cleanup = cleanupAction(delegate, call, candidate);
        return cleanup == null ? null : new Registration(candidate, cleanup);
    }

    public void afterFailure(Call call, Throwable error) {
        if (call == null || call == Call.NONE) return;
        audit(call.capability, call.operation, "DELEGATE_FAILED",
                error == null ? "" : error.getClass().getSimpleName());
    }

    private String capability(String operation) {
        String name = normalize(operation);
        if ("camera".equals(service)) {
            if (containsAny(name, "connect", "open", "torch", "cameradevice")) {
                return CapabilityAccessPolicy.CAMERA;
            }
            return "";
        }
        if ("location".equals(service)) {
            if (containsAny(name, "location", "updates", "geofence", "gnss", "nmea", "providerenabled")) {
                return CapabilityAccessPolicy.LOCATION;
            }
            return "";
        }
        if ("audio".equals(service)) {
            if (containsAny(name, "record", "capture", "microphone", "input")) {
                return CapabilityAccessPolicy.MICROPHONE;
            }
        }
        return "";
    }

    private static boolean isRegistration(String operation) {
        String name = normalize(operation);
        return startsAny(name, "request", "register", "add", "connect", "open", "start");
    }

    private static boolean isCleanup(String operation) {
        String name = normalize(operation);
        return startsAny(name, "remove", "unregister", "disconnect", "close", "finish", "stop", "release");
    }

    private CapabilityLeaseRegistry.CleanupAction cleanupAction(Object delegate, Call call, Object token) {
        if (token == null) return null;
        if (CapabilityAccessPolicy.CAMERA.equals(call.capability)) {
            Method close = compatibleMethod(token.getClass(), new String[]{"close", "disconnect", "release"}, null);
            return close == null ? null : () -> close.invoke(token);
        }
        String[] names = CapabilityAccessPolicy.LOCATION.equals(call.capability)
                ? new String[]{"removeUpdates", "unregisterGnssStatusCallback", "removeNmeaListener",
                "removeGeofence", "removeTestProvider"}
                : new String[]{"unregisterAudioRecordingCallback", "stopInput", "releaseInput"};
        Method cleanup = compatibleMethod(delegate.getClass(), names, token.getClass());
        return cleanup == null ? null : () -> cleanup.invoke(delegate, token);
    }

    private static Method compatibleMethod(Class<?> owner, String[] names, Class<?> tokenType) {
        for (Method method : owner.getMethods()) {
            if (!contains(names, method.getName())) continue;
            if (tokenType == null && method.getParameterCount() == 0) {
                method.setAccessible(true); return method;
            }
            if (tokenType != null && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(tokenType)) {
                method.setAccessible(true); return method;
            }
        }
        return null;
    }

    private static Object resourceToken(Object[] arguments) {
        if (arguments == null) return null;
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object argument = arguments[index];
            if (isResourceCandidate(argument)) return argument;
        }
        return null;
    }

    private static boolean isResourceCandidate(Object argument) {
        if (argument == null || argument instanceof String || argument instanceof Number
                || argument instanceof Boolean || argument.getClass().isEnum()) return false;
        String name = argument.getClass().getName();
        return !name.contains("Attribution") && !name.startsWith("android.os.Bundle")
                && !name.contains("Executor") && !name.contains("LocationRequest");
    }

    private void audit(String capability, String operation, String decision, String detail) {
        identity.capabilityAudit().record(new CapabilityAuditEvent(EVENTS.incrementAndGet(), capability,
                service, operation, decision, detail));
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    private static boolean startsAny(String value, String... needles) {
        for (String needle : needles) if (value.startsWith(needle)) return true;
        return false;
    }
    private static boolean contains(String[] values, String value) {
        for (String item : values) if (item.equals(value)) return true;
        return false;
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Registration {
        final Object token;
        final CapabilityLeaseRegistry.CleanupAction cleanup;
        Registration(Object token, CapabilityLeaseRegistry.CleanupAction cleanup) {
            this.token = token;
            this.cleanup = cleanup;
        }
    }

    public static final class Call {
        public static final Call NONE = new Call("", "", null, false, false);
        public final String capability;
        public final String operation;
        public final Object token;
        public final boolean cleanup;
        public final boolean registration;
        Call(String capability, String operation, Object token, boolean cleanup, boolean registration) {
            this.capability = capability;
            this.operation = operation;
            this.token = token;
            this.cleanup = cleanup;
            this.registration = registration;
        }
    }
}
