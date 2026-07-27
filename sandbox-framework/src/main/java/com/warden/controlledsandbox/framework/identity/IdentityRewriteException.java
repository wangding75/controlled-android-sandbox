package com.warden.controlledsandbox.framework.identity;

public final class IdentityRewriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public IdentityRewriteException(String message) {
        super(message);
    }

    public IdentityRewriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
