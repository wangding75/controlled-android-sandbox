package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualCompanionDeviceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.lang.reflect.Method;
import java.util.List;

/** Companion-device association/presence projection with scoped observer ownership. */
final class PeripheralCompanionInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralCompanionInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualCompanionDeviceProfileSnapshot profile =
                state.identity().virtualServices().peripheralServicesProfile().companionDevice();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "stopobserving", "unregister", "close")) {
            removeIdentity(state.companionObservers, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        ensureAssociations(profile);
        if (containsAny(name, "getassociations", "getallassociations")) {
            return handled(stringArrayOrList(method.getReturnType(), List.copyOf(state.companionAssociations)));
        }
        // Disassociation is classified before association because its normalized name contains "associate".
        if (containsAny(name, "disassociate", "removeassociation")) {
            if (!profile.allowDisassociation()) {
                throw new SecurityException("VIRTUAL_COMPANION_DISASSOCIATION_DENIED");
            }
            state.companionAssociations.remove(firstString(arguments));
            return handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "associate", "createassociation")) {
            if (!profile.allowAssociation()) {
                throw new SecurityException("VIRTUAL_COMPANION_ASSOCIATION_DENIED");
            }
            String associationId = firstString(arguments);
            if (associationId.isEmpty()) associationId = "association-" + (++state.syntheticSequence);
            if (!state.companionAssociations.contains(associationId)
                    && state.companionAssociations.size() >= profile.maximumAssociations()) {
                throw new IllegalStateException("VIRTUAL_COMPANION_ASSOCIATION_LIMIT_EXCEEDED");
            }
            state.companionAssociations.add(associationId);
            return handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "startobserving", "registerpresence")) {
            if (!profile.presenceObservationEnabled()) {
                throw new SecurityException("VIRTUAL_COMPANION_PRESENCE_DENIED");
            }
            Object callback = firstIdentity(arguments);
            if (callback == null) callback = state.syntheticToken();
            addBounded(state.companionObservers, callback, profile.maximumAssociations(),
                    "VIRTUAL_COMPANION_OBSERVER_LIMIT_EXCEEDED");
            return handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "isselfmanagedassociationallowed")) {
            return handled(booleanValue(method.getReturnType(), profile.selfManagedAssociationsAllowed()));
        }
        return unsupported("companion_device", method);
    }

    private void ensureAssociations(VirtualCompanionDeviceProfileSnapshot profile) {
        if (state.companionAssociationsInitialized) return;
        state.companionAssociations.addAll(profile.associationIds());
        state.companionAssociationsInitialized = true;
    }
}
