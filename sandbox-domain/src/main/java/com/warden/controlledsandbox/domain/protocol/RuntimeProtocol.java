package com.warden.controlledsandbox.domain.protocol;

/** Versioned Binder protocol shared by broker and guest processes. */
public final class RuntimeProtocol {
    public static final int CURRENT = 2;
    public static final int MIN_SUPPORTED = 2;

    private RuntimeProtocol() { }

    public static boolean isCompatible(int peerVersion) {
        return peerVersion >= MIN_SUPPORTED && peerVersion <= CURRENT;
    }
}
