package com.warden.controlledsandbox.runtime.guest;

import android.content.Context;

/**
 * Framework-only base used while an Activity-owned ContextWrapper asks for theme/display state.
 * Host-sensitive operations remain denied by the same boundary as GuestContext.
 */
public final class GuestActivityBaseContext extends GuestHostOperationDenyContext {
    public GuestActivityBaseContext(Context frameworkBase) {
        super(java.util.Objects.requireNonNull(frameworkBase, "frameworkBase"));
    }
}
