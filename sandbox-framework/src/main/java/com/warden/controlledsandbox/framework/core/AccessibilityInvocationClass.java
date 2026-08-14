package com.warden.controlledsandbox.framework.core;

/**
 * Method-level Accessibility semantics. Guest-local View/window bookkeeping must not be
 * forwarded to Host services; Host mutation, cross-app trees, and secure settings stay denied.
 */
public enum AccessibilityInvocationClass {
    APP_LOCAL_EVENT,
    APP_LOCAL_WINDOW_METADATA,
    HOST_ACCESSIBILITY_STATE_READ,
    HOST_ACCESSIBILITY_STATE_MUTATION,
    CROSS_APP_ACCESSIBILITY_DATA,
    SECURE_SETTING_MUTATION,
    UNKNOWN
}
