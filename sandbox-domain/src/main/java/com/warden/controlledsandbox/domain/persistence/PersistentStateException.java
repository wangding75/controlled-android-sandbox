package com.warden.controlledsandbox.domain.persistence;

/** Indicates that security-sensitive persisted state cannot be trusted or recovered. */
public final class PersistentStateException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PersistentStateException(String message) {
        super(message);
    }

    public PersistentStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
