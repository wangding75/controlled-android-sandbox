package com.warden.controlledsandbox.fixture;

final class FixtureNative {
    private static final boolean AVAILABLE;
    private static final String ERROR;
    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary("controlled_sandbox_fixture");
            available = true;
        } catch (Throwable failure) {
            error = failure.getClass().getName() + ":" + String.valueOf(failure.getMessage());
        }
        AVAILABLE = available;
        ERROR = error;
    }
    private FixtureNative() { }
    static String loadStatus() { return AVAILABLE ? "JNI_LOADED" : "JNI_UNAVAILABLE:" + ERROR; }
    static String probe() { return AVAILABLE ? nativeProbe() : loadStatus(); }
    private static native String nativeProbe();
}
