package com.warden.controlledsandbox.fixture.scale;

public final class ScaleNative {
    static { System.loadLibrary("controlled_sandbox_fixture_scale"); }
    private ScaleNative() { }
    public static native int version();
}
