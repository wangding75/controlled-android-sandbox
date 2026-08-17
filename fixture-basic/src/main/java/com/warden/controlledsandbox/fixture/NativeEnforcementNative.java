package com.warden.controlledsandbox.fixture;

/** Fixture-only JNI surface. Used for 32-bit seccomp feasibility on RD. */
public final class NativeEnforcementNative {
    private static final boolean AVAILABLE;
    private static final String ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary("cas_native_enf");
            available = true;
        } catch (Throwable failure) {
            error = failure.getClass().getName() + ":" + String.valueOf(failure.getMessage());
        }
        AVAILABLE = available;
        ERROR = error;
    }

    private NativeEnforcementNative() { }

    public static boolean available() {
        return AVAILABLE;
    }

    public static String loadError() {
        return ERROR;
    }

    public static String compiledAbi() {
        if (!AVAILABLE) return "unavailable";
        return nativeCompiledAbi();
    }

    public static String probeSeccomp() {
        if (!AVAILABLE) return "{\"error\":\"JNI_UNAVAILABLE\",\"detail\":\"" + ERROR + "\"}";
        return nativeProbeSeccomp();
    }

    private static native String nativeProbeOpen(String path);
    private static native String nativeProbeConnect(String host, int port);
    private static native String nativeProbeSeccomp();
    private static native String nativeCompiledAbi();
}
