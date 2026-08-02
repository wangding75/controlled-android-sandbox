package com.warden.controlledsandbox.contract;

/** Single checked-in source of truth for Host/Companion release and protocol identity. */
public final class ControlledReleaseIdentity {
    public static final String PRODUCT = "controlled-android-sandbox";
    public static final String RELEASE_TRAIN = "m5-t19-1";
    public static final int VERSION_CODE = 19;
    public static final String VERSION_NAME = "0.5.19.1-source";
    public static final int COMPANION_PROTOCOL = 1;

    private ControlledReleaseIdentity() { }
}
