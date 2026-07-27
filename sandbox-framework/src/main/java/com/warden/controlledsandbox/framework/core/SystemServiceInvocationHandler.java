package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.IdentityObjectRewriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Common Binder-interface proxy with exact identity rewriting and rollback. */
public final class SystemServiceInvocationHandler implements InvocationHandler {
    private final Object delegate;
    private final GuestIdentity identity;

    SystemServiceInvocationHandler(Object delegate, GuestIdentity identity) {
        this.delegate = delegate;
        this.identity = identity;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return method.invoke(delegate, arguments);
        Object[] rewritten = arguments == null ? null : arguments.clone();
        IdentityObjectRewriter.RewriteScope scope = IdentityObjectRewriter.rewriteArguments(rewritten, identity);
        try {
            try {
                Object result = method.invoke(delegate, rewritten);
                return IdentityObjectRewriter.rewriteResult(result, identity);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        } finally {
            scope.close();
        }
    }
}
