package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.lang.reflect.Method;

/** Generic OEM-service query projection and mutation/session policy. */
final class PeripheralOemInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralOemInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualOemSystemServicesProfileSnapshot profile =
                state.identity().virtualServices().peripheralServicesProfile().oemSystemServices();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (cleanup(name)) {
            removeIdentity(state.oemSessions, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (matchesPrefix(name, profile.blockedMutationPrefixes())) {
            throw new SecurityException("VIRTUAL_OEM_SYSTEM_MUTATION_DENIED:" + method.getName());
        }
        if (matchesPrefix(name, profile.allowedQueryPrefixes())) {
            return handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "register", "opensession")) {
            Object token = firstIdentity(arguments);
            if (token == null) token = state.syntheticToken();
            addBounded(state.oemSessions, token, profile.maximumSessions(),
                    "VIRTUAL_OEM_SYSTEM_SESSION_LIMIT_EXCEEDED");
            return handled(successValue(method.getReturnType()));
        }
        return unsupported("oem_system", method);
    }
}
