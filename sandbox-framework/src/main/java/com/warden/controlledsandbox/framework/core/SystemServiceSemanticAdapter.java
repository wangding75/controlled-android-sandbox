package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.binder.BinderInterceptionFoundation;
import com.warden.controlledsandbox.framework.identity.AttributionSourceChain;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;

/** Shared semantic adapter used by every typed system-service proxy. */
public final class SystemServiceSemanticAdapter {
    private final GuestIdentity identity;
    private final String serviceName;
    private final SystemServiceSemanticContract contract;

    public SystemServiceSemanticAdapter(GuestIdentity identity, String serviceName) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.serviceName = serviceName == null ? "" : serviceName;
        this.contract = SystemServiceSemanticCatalog.forService(this.serviceName);
    }

    public GuestIdentity identity() { return identity; }
    public String serviceName() { return serviceName; }
    public SystemServiceSemanticContract contract() { return contract; }

    /** Applies the common Guest -> Host identity transform before Binder marshalling. */
    public IdentityObjectRewriter.RewriteScope rewriteArguments(Object[] arguments) {
        IdentityObjectRewriter.RewriteScope scope =
                IdentityObjectRewriter.rewriteArguments(arguments, identity);
        AttributionSourceChain.rewriteOutbound(arguments, identity, scope);
        return scope;
    }

    /** Applies common result/callback projection before the Binder boundary wraps returned objects. */
    public Object projectResult(Object result) {
        return AttributionSourceChain.rewriteInbound(
                IdentityObjectRewriter.rewriteResult(result, identity), identity);
    }

    /** The Binder boundary is the owner of callback and returned-Binder lifetime. */
    public Object wrapReturned(BinderInterceptionFoundation boundary, Object value,
                               Class<?> declaredType, String role) {
        return boundary == null ? value : boundary.wrapReturned(value, declaredType, role);
    }

    public boolean containsGuestAttribution(Object[] arguments) {
        return AttributionSourceChain.contains(arguments, identity.packageName(), identity.virtualUid());
    }

    public boolean containsHostAttribution(Object[] arguments) {
        return AttributionSourceChain.contains(arguments, identity.hostPackageName(), identity.hostUid());
    }
}
