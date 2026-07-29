package com.warden.controlledsandbox.fixture;

import java.io.File;

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
    static String probe(File filesDir) {
        if (!AVAILABLE) return loadStatus();
        if (filesDir == null) return "JNI_INVALID_FILES_DIR";
        return nativeProbe(new File(filesDir, "native-probe.txt").getAbsolutePath());
    }
    private static native String nativeProbe(String path);
}
