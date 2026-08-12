package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualMediaProjectionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.lang.reflect.Method;

/** MediaProjection capability projection and bounded session ownership. */
final class PeripheralProjectionInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralProjectionInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualMediaProjectionProfileSnapshot profile =
                state.identity().virtualServices().peripheralServicesProfile().mediaProjection();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "stop", "release", "destroy")) {
            removeIdentity(state.projectionSessions, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "isavailable", "hasprojectionpermission")) {
            return handled(booleanValue(method.getReturnType(), profile.projectionAvailable()));
        }
        if (containsAny(name, "canscreencapture")) {
            return handled(booleanValue(method.getReturnType(), profile.allowScreenCapture()));
        }
        if (containsAny(name, "canaudiocapture")) {
            return handled(booleanValue(method.getReturnType(), profile.allowAudioCapture()));
        }
        if (containsAny(name, "getvirtualwidth")) {
            return handled(numeric(method.getReturnType(), profile.virtualWidth()));
        }
        if (containsAny(name, "getvirtualheight")) {
            return handled(numeric(method.getReturnType(), profile.virtualHeight()));
        }
        if (containsAny(name, "getdensitydpi")) {
            return handled(numeric(method.getReturnType(), profile.densityDpi()));
        }
        if (containsAny(name, "createprojection", "startprojection", "getmediaprojection")) {
            if (!profile.projectionAvailable() || !profile.allowScreenCapture()) {
                throw new SecurityException("VIRTUAL_MEDIA_PROJECTION_DENIED");
            }
            if (profile.requireConsent()) {
                throw new IllegalStateException("VIRTUAL_MEDIA_PROJECTION_CONSENT_ADAPTER_REQUIRED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = state.syntheticToken();
            addBounded(state.projectionSessions, token, profile.maximumActiveSessions(),
                    "VIRTUAL_MEDIA_PROJECTION_SESSION_LIMIT_EXCEEDED");
            return adaptableSessionResult("MEDIA_PROJECTION", method, token, state.projectionSessions);
        }
        return unsupported("media_projection", method);
    }
}
