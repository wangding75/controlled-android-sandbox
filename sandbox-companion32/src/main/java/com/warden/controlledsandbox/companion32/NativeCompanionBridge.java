package com.warden.controlledsandbox.companion32;

/** JNI status boundary for the independently packaged 32-bit Native hook library. */
final class NativeCompanionBridge {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;
    static {
        boolean available = false;
        String error = "";
        try { System.loadLibrary("controlled_sandbox_native32"); available = true; }
        catch (Throwable failure) { error = failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()); }
        AVAILABLE = available;
        LOAD_ERROR = error;
    }
    private NativeCompanionBridge() { }
    static boolean available() { return AVAILABLE; }
    static int processBitness() { return AVAILABLE ? nativeProcessBitness() : 0; }
    static String status() { return AVAILABLE ? nativeStatus() : "unavailable:" + LOAD_ERROR; }
    private static native int nativeProcessBitness();
    private static native String nativeStatus();
}
