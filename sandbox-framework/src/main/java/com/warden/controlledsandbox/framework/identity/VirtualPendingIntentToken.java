package com.warden.controlledsandbox.framework.identity;

/** Marker implemented by Guest-local sender binders so other virtual services can persist references safely. */
public interface VirtualPendingIntentToken {
    String persistentTokenId();
}
