package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** SearchManager Binder hook. */
public final class SearchManagerServiceHook {
    private SearchManagerServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "search", "android.app.ISearchManager$Stub", identity, "search");
    }
}
