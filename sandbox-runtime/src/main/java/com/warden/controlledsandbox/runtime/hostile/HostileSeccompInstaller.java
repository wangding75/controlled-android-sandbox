package com.warden.controlledsandbox.runtime.hostile;

import com.warden.controlledsandbox.nativebridge.NativePolicy;

/** Process-local installer. Call only from an ISOLATED_HOSTILE worker. */
public final class HostileSeccompInstaller {
    private HostileSeccompInstaller() { }

    public static String installInCallingProcess() {
        return NativePolicy.installHostileSeccomp();
    }
}
