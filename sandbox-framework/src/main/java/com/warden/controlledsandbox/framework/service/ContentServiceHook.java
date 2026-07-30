package com.warden.controlledsandbox.framework.service;

import com.warden.controlledsandbox.framework.core.ReflectiveServiceHook;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;

/** Reversible ContentResolver IContentService replacement. */
public final class ContentServiceHook {
    private ContentServiceHook() { }
    public static AutoCloseable install(GuestIdentity identity) throws Exception {
        return ReflectiveServiceHook.staticField("android.content.ContentResolver", "sContentService",
                "getContentService", identity, "content");
    }
}
