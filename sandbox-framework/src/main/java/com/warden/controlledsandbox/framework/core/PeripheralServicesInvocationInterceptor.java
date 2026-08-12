package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Method;
import java.util.Objects;

/** Unified peripheral entry point: classify a Binder service, then delegate to its typed handler. */
final class PeripheralServicesInvocationInterceptor {
    private final GuestIdentity identity;
    private final PeripheralInvocationState state;
    private final String service;
    private final PeripheralServiceInvocationHandler handler;

    PeripheralServicesInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.state = new PeripheralInvocationState(identity);
        this.service = PeripheralInvocationValues.normalize(service);
        this.handler = classify(this.service, state);
    }

    synchronized Decision before(Method method, Object[] arguments) {
        if (handler == null) return Decision.passThrough();
        VirtualPeripheralServicesProfileSnapshot profile;
        try {
            profile = identity.virtualServices().peripheralServicesProfile();
        } catch (IllegalStateException unavailable) {
            String message = unavailable.getMessage();
            if ("VIRTUAL_PERIPHERAL_SERVICES_PROFILE_AUTHORITY_REQUIRED".equals(message)
                    || "VIRTUAL_PERIPHERAL_SERVICES_PROFILE_NOT_AVAILABLE".equals(message)) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        return handler.before(method, arguments, profile);
    }

    synchronized int nfcReaderCount() { return state.nfcReaders.size(); }
    synchronized int openUsbDeviceCount() { return state.usbDevices.size(); }
    synchronized int printJobCount() { return state.printJobs.size(); }
    synchronized int companionObserverCount() { return state.companionObservers.size(); }
    synchronized int projectionSessionCount() { return state.projectionSessions.size(); }
    synchronized int cameraSessionCount() { return state.cameraSessions.size(); }
    synchronized int cameraListenerCount() { return state.cameraListeners.size(); }
    synchronized int oemSessionCount() { return state.oemSessions.size(); }

    private static PeripheralServiceInvocationHandler classify(
            String service, PeripheralInvocationState state) {
        return switch (service) {
            case "nfc" -> new PeripheralNfcInvocationHandler(state);
            case "usb" -> new PeripheralUsbInvocationHandler(state);
            case "print" -> new PeripheralPrintInvocationHandler(state);
            case "companiondevice" -> new PeripheralCompanionInvocationHandler(state);
            case "mediaprojection" -> new PeripheralProjectionInvocationHandler(state);
            case "camera" -> new PeripheralCameraInvocationHandler(state);
            case "oemsystem" -> new PeripheralOemInvocationHandler(state);
            default -> null;
        };
    }

    // The typed handlers retain the existing contracts for ensureCompanionAssociations,
    // session quotas and explicit error policy: VIRTUAL_NFC_READER_SESSION_LIMIT_EXCEEDED,
    // VIRTUAL_USB_OPEN_DEVICE_LIMIT_EXCEEDED, VIRTUAL_PRINT_JOB_LIMIT_EXCEEDED,
    // VIRTUAL_COMPANION_DISASSOCIATION_DENIED, VIRTUAL_MEDIA_PROJECTION_SESSION_LIMIT_EXCEEDED,
    // VIRTUAL_CAMERA_SESSION_LIMIT_EXCEEDED, VIRTUAL_OEM_SYSTEM_SESSION_LIMIT_EXCEEDED.
    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object result) { return new Decision(true, result); }
    }
}
