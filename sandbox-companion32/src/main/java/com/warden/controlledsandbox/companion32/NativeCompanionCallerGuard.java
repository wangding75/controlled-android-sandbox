package com.warden.controlledsandbox.companion32;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

/** Defense-in-depth caller check in addition to the manifest signature permission. */
final class NativeCompanionCallerGuard {
    private static final String SIGNATURE_PERMISSION =
            "com.warden.controlledsandbox.permission.BIND_NATIVE_COMPANION";

    private NativeCompanionCallerGuard() { }

    static void requireSignedPeer(Context context) {
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) return;
        if (context == null || context.checkCallingPermission(SIGNATURE_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("UNTRUSTED_NATIVE_COMPANION_CALLER:" + callerUid);
        }
    }
}
