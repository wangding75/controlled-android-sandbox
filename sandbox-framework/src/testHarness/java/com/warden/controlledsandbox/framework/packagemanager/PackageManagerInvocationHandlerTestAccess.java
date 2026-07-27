package com.warden.controlledsandbox.framework.packagemanager;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;

import java.lang.reflect.InvocationHandler;

/** Test-harness-only access to the package-private production constructor. */
public final class PackageManagerInvocationHandlerTestAccess {
    private PackageManagerInvocationHandlerTestAccess() {
    }

    public static InvocationHandler create(Object delegate, GuestIdentity identity) {
        return new PackageManagerInvocationHandler(delegate, identity);
    }
}
