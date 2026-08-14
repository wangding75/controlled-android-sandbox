package com.warden.controlledsandbox.framework.packagemanager;

/**
 * Guest-visible package classes. Ordinary Host user apps stay hidden. System roles are
 * classified independently of any production app package name.
 */
public enum PackageVisibilityClass {
    GUEST_OWNED,
    SYSTEM_PROJECTED,
    SYSTEM_DEPENDENCY_PROJECTED,
    QUERY_DECLARED,
    HOST_USER_APP_HIDDEN,
    EXPLICITLY_DENIED
}
