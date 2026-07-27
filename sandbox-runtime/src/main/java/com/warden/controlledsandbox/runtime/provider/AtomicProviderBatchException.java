package com.warden.controlledsandbox.runtime.provider;

/** Public failure type for opt-in atomic Provider batch implementations. */
public final class AtomicProviderBatchException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int operationIndex;

    public AtomicProviderBatchException(int operationIndex, String message) {
        super(message);
        this.operationIndex = operationIndex;
    }

    public AtomicProviderBatchException(int operationIndex, String message, Throwable cause) {
        super(message, cause);
        this.operationIndex = operationIndex;
    }

    public int operationIndex() { return operationIndex; }
}
