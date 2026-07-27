package com.warden.controlledsandbox.framework.activity;

public final class LaunchFlags {
    public static final int NEW_TASK = 0x10000000;
    public static final int MULTIPLE_TASK = 0x08000000;
    public static final int CLEAR_TOP = 0x04000000;
    public static final int NEW_DOCUMENT = 0x00080000;
    public static final int CLEAR_TASK = 0x00008000;
    public static final int REORDER_TO_FRONT = 0x00020000;
    public static final int SINGLE_TOP = 0x20000000;

    private LaunchFlags() {
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) == flag;
    }
}
