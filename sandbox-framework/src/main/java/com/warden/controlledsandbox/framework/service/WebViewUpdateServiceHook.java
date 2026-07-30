package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ServiceManagerBinderHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible IWebViewUpdateService projection. */
public final class WebViewUpdateServiceHook {
    private WebViewUpdateServiceHook() { }

    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ServiceManagerBinderHook.install(
                "webviewupdate",
                "android.webkit.IWebViewUpdateService$Stub",
                identity,
                "webviewupdate");
    }
}
