package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompanionDeviceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaProjectionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrintProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsbProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Source-side NFC/USB/printing/companion/projection/camera/OEM service virtualization. */
final class PeripheralServicesInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Set<Object> nfcReaders = identitySet();
    private final Set<Object> usbDevices = identitySet();
    private final Set<Object> printJobs = identitySet();
    private final Set<Object> companionObservers = identitySet();
    private final Set<String> companionAssociations = new LinkedHashSet<>();
    private final Set<Object> projectionSessions = identitySet();
    private final Set<Object> cameraSessions = identitySet();
    private final Set<Object> oemSessions = identitySet();
    private int syntheticSequence;
    private int tagOperations;
    private boolean companionAssociationsInitialized;

    PeripheralServicesInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    synchronized Decision before(Method method, Object[] arguments) {
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
        return switch (service) {
            case "nfc" -> nfc(method, arguments, profile.nfc());
            case "usb" -> usb(method, arguments, profile.usb());
            case "print" -> printing(method, arguments, profile.printing());
            case "companiondevice" -> companion(method, arguments, profile.companionDevice());
            case "mediaprojection" -> projection(method, arguments, profile.mediaProjection());
            case "camera" -> camera(method, arguments, profile.camera());
            case "oemsystem" -> oem(method, arguments, profile.oemSystemServices());
            default -> Decision.passThrough();
        };
    }

    synchronized int nfcReaderCount() { return nfcReaders.size(); }
    synchronized int openUsbDeviceCount() { return usbDevices.size(); }
    synchronized int printJobCount() { return printJobs.size(); }
    synchronized int companionObserverCount() { return companionObservers.size(); }
    synchronized int projectionSessionCount() { return projectionSessions.size(); }
    synchronized int cameraSessionCount() { return cameraSessions.size(); }
    synchronized int oemSessionCount() { return oemSessions.size(); }

    private Decision nfc(Method method, Object[] arguments, VirtualNfcProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "disablereadermode", "unregister", "close", "release")) {
            removeIdentity(nfcReaders, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getstate", "getadapterstate")) {
            return Decision.handled(numeric(method.getReturnType(), adapterState(profile.adapterState())));
        }
        if (containsAny(name, "isenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(), "ON".equals(profile.adapterState())));
        }
        if (containsAny(name, "enable", "disable") && !name.contains("readermode")) {
            throw new SecurityException("VIRTUAL_NFC_ADAPTER_MUTATION_DENIED");
        }
        if (containsAny(name, "enablereadermode", "registerreader")) {
            if (!profile.readerModeAllowed()) throw new SecurityException("VIRTUAL_NFC_READER_MODE_DENIED");
            Object callback = firstIdentity(arguments);
            if (callback == null) callback = new SyntheticToken(++syntheticSequence);
            addBounded(nfcReaders, callback, profile.maximumReaderSessions(),
                    "VIRTUAL_NFC_READER_SESSION_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "gettagids", "listtags")) {
            return Decision.handled(stringArrayOrList(method.getReturnType(), profile.tagIds()));
        }
        if (containsAny(name, "transceive", "ndef", "tagoperation")) {
            if (tagOperations >= profile.maximumTagOperations()) {
                throw new IllegalStateException("VIRTUAL_NFC_TAG_OPERATION_LIMIT_EXCEEDED");
            }
            String tag = firstString(arguments);
            if (!tag.isEmpty() && !profile.tagIds().contains(tag)) {
                throw new SecurityException("VIRTUAL_NFC_TAG_NOT_APPROVED");
            }
            tagOperations++;
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "iscardemulation", "hascardemulation")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.cardEmulationAvailable()));
        }
        if (containsAny(name, "isndefpushenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.ndefPushEnabled()));
        }
        if (containsAny(name, "getnfc", "gettaginterface", "getcardemulationinterface")) {
            return Decision.handled(null);
        }
        return unsupported("nfc", method);
    }

    private Decision usb(Method method, Object[] arguments, VirtualUsbProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "closedevice", "release", "unregister")) {
            removeIdentity(usbDevices, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getdevicelist")) {
            if (Map.class.isAssignableFrom(method.getReturnType()) || method.getReturnType() == Object.class) {
                return Decision.handled(Map.of());
            }
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "getcurrentaccessory", "getaccessorylist")) {
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "hashostsupport")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.hostSupported()));
        }
        if (containsAny(name, "hasaccessorysupport")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.accessorySupported()));
        }
        if (containsAny(name, "hasdevicepermission", "haspermission")) {
            return Decision.handled(booleanValue(method.getReturnType(), approvedUsb(profile, arguments)));
        }
        if (containsAny(name, "requestdevicepermission", "requestpermission")) {
            if (!profile.allowPermissionRequests() || !approvedUsb(profile, arguments)) {
                throw new SecurityException("VIRTUAL_USB_PERMISSION_REQUEST_DENIED");
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "opendevice")) {
            if (!profile.allowOpenDevice() || !approvedUsb(profile, arguments)) {
                throw new SecurityException("VIRTUAL_USB_OPEN_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(usbDevices, token, profile.maximumOpenDevices(),
                    "VIRTUAL_USB_OPEN_DEVICE_LIMIT_EXCEEDED");
            return adaptableSessionResult("USB", method, token, usbDevices);
        }
        if (containsAny(name, "getcurrentfunctions", "getdefaultfunctions")) {
            return Decision.handled(stringValue(method.getReturnType(), profile.defaultFunctions()));
        }
        if (containsAny(name, "setcurrentfunctions", "setfunctions", "resetusb")) {
            throw new SecurityException("VIRTUAL_USB_FUNCTION_MUTATION_DENIED");
        }
        return unsupported("usb", method);
    }

    private Decision printing(Method method, Object[] arguments, VirtualPrintProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "cancelprintjob", "removeprintjob", "finishprintjob", "destroy")) {
            removeIdentity(printJobs, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "isprintingenabled", "isprintserviceenabled")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.printingEnabled()));
        }
        if (containsAny(name, "getprintservices", "getenabledprintservices")) {
            return Decision.handled(stringArrayOrList(
                    method.getReturnType(), profile.availablePrintServices()));
        }
        if (containsAny(name, "getprintjobinfos", "getprintjobs")) {
            return Decision.handled(emptyCollection(method.getReturnType()));
        }
        if (containsAny(name, "getdefaultprinterid")) {
            return Decision.handled(stringValue(method.getReturnType(), profile.defaultPrinterId()));
        }
        if (containsAny(name, "getdefaultprintername")) {
            return Decision.handled(stringValue(method.getReturnType(), profile.defaultPrinterName()));
        }
        if (containsAny(name, "print", "createprintjob")) {
            if (!profile.printingEnabled() || !profile.allowPrintJobs()) {
                throw new SecurityException("VIRTUAL_PRINT_JOB_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(printJobs, token, profile.maximumActiveJobs(),
                    "VIRTUAL_PRINT_JOB_LIMIT_EXCEEDED");
            return adaptableSessionResult("PRINT_JOB", method, token, printJobs);
        }
        if (containsAny(name, "restartprintjob", "setprintserviceenabled")) {
            throw new SecurityException("VIRTUAL_PRINT_MUTATION_DENIED");
        }
        return unsupported("printing", method);
    }

    private Decision companion(Method method, Object[] arguments,
            VirtualCompanionDeviceProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "stopobserving", "unregister", "close")) {
            removeIdentity(companionObservers, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        ensureCompanionAssociations(profile);
        if (containsAny(name, "getassociations", "getallassociations")) {
            return Decision.handled(stringArrayOrList(
                    method.getReturnType(), List.copyOf(companionAssociations)));
        }
        // Disassociation must be classified before association because the normalized
        // method name "disassociate" contains the substring "associate".
        if (containsAny(name, "disassociate", "removeassociation")) {
            if (!profile.allowDisassociation()) {
                throw new SecurityException("VIRTUAL_COMPANION_DISASSOCIATION_DENIED");
            }
            companionAssociations.remove(firstString(arguments));
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "associate", "createassociation")) {
            if (!profile.allowAssociation()) {
                throw new SecurityException("VIRTUAL_COMPANION_ASSOCIATION_DENIED");
            }
            String associationId = firstString(arguments);
            if (associationId.isEmpty()) associationId = "association-" + (++syntheticSequence);
            if (!companionAssociations.contains(associationId)
                    && companionAssociations.size() >= profile.maximumAssociations()) {
                throw new IllegalStateException("VIRTUAL_COMPANION_ASSOCIATION_LIMIT_EXCEEDED");
            }
            companionAssociations.add(associationId);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "startobserving", "registerpresence")) {
            if (!profile.presenceObservationEnabled()) {
                throw new SecurityException("VIRTUAL_COMPANION_PRESENCE_DENIED");
            }
            Object callback = firstIdentity(arguments);
            if (callback == null) callback = new SyntheticToken(++syntheticSequence);
            addBounded(companionObservers, callback, profile.maximumAssociations(),
                    "VIRTUAL_COMPANION_OBSERVER_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "isselfmanagedassociationallowed")) {
            return Decision.handled(booleanValue(
                    method.getReturnType(), profile.selfManagedAssociationsAllowed()));
        }
        return unsupported("companion_device", method);
    }

    private Decision projection(Method method, Object[] arguments,
            VirtualMediaProjectionProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "stop", "release", "destroy")) {
            removeIdentity(projectionSessions, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "isavailable", "hasprojectionpermission")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.projectionAvailable()));
        }
        if (containsAny(name, "canscreencapture")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.allowScreenCapture()));
        }
        if (containsAny(name, "canaudiocapture")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.allowAudioCapture()));
        }
        if (containsAny(name, "getvirtualwidth")) {
            return Decision.handled(numeric(method.getReturnType(), profile.virtualWidth()));
        }
        if (containsAny(name, "getvirtualheight")) {
            return Decision.handled(numeric(method.getReturnType(), profile.virtualHeight()));
        }
        if (containsAny(name, "getdensitydpi")) {
            return Decision.handled(numeric(method.getReturnType(), profile.densityDpi()));
        }
        if (containsAny(name, "createprojection", "startprojection", "getmediaprojection")) {
            if (!profile.projectionAvailable() || !profile.allowScreenCapture()) {
                throw new SecurityException("VIRTUAL_MEDIA_PROJECTION_DENIED");
            }
            if (profile.requireConsent()) {
                throw new IllegalStateException("VIRTUAL_MEDIA_PROJECTION_CONSENT_ADAPTER_REQUIRED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(projectionSessions, token, profile.maximumActiveSessions(),
                    "VIRTUAL_MEDIA_PROJECTION_SESSION_LIMIT_EXCEEDED");
            return adaptableSessionResult("MEDIA_PROJECTION", method, token, projectionSessions);
        }
        return unsupported("media_projection", method);
    }

    private Decision camera(Method method, Object[] arguments, VirtualCameraProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "disconnect", "close", "release", "remove")) {
            removeIdentity(cameraSessions, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getcameralist", "getcameraidlist")) {
            return Decision.handled(stringArrayOrList(method.getReturnType(), profile.cameraIds()));
        }
        if (containsAny(name, "getnumberofcameras")) {
            return Decision.handled(numeric(method.getReturnType(), profile.cameraIds().size()));
        }
        if (containsAny(name, "isavailable", "hascamera")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.cameraAvailable()));
        }
        if (containsAny(name, "isfrontcamera")) {
            return Decision.handled(booleanValue(
                    method.getReturnType(), profile.frontCameraIds().contains(firstString(arguments))));
        }
        if (containsAny(name, "connect", "opencamera", "connectdevice")) {
            String cameraId = firstString(arguments);
            if (!profile.cameraAvailable() || !profile.allowOpen()
                    || !profile.cameraIds().contains(cameraId)) {
                throw new SecurityException("VIRTUAL_CAMERA_OPEN_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(cameraSessions, token, profile.maximumOpenCameras(),
                    "VIRTUAL_CAMERA_SESSION_LIMIT_EXCEEDED");
            return adaptableSessionResult("CAMERA", method, token, cameraSessions);
        }
        if (containsAny(name, "settorchmode", "turnontorch", "turnofftorch")) {
            String cameraId = firstString(arguments);
            if (!profile.allowTorch() || !profile.torchAvailableCameraIds().contains(cameraId)) {
                throw new SecurityException("VIRTUAL_CAMERA_TORCH_DENIED");
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getcameracharacteristics", "getcamerainfo")) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CHARACTERISTICS_ADAPTER_REQUIRED");
        }
        return unsupported("camera", method);
    }

    private Decision oem(Method method, Object[] arguments,
            VirtualOemSystemServicesProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (cleanup(name)) {
            removeIdentity(oemSessions, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (matchesPrefix(name, profile.blockedMutationPrefixes())) {
            throw new SecurityException("VIRTUAL_OEM_SYSTEM_MUTATION_DENIED:" + method.getName());
        }
        if (matchesPrefix(name, profile.allowedQueryPrefixes())) {
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "register", "opensession")) {
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSequence);
            addBounded(oemSessions, token, profile.maximumSessions(),
                    "VIRTUAL_OEM_SYSTEM_SESSION_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("oem_system", method);
    }

    private void ensureCompanionAssociations(VirtualCompanionDeviceProfileSnapshot profile) {
        if (companionAssociationsInitialized) return;
        companionAssociations.addAll(profile.associationIds());
        companionAssociationsInitialized = true;
    }

    private static boolean approvedUsb(VirtualUsbProfileSnapshot profile, Object[] arguments) {
        String device = firstString(arguments);
        return !device.isEmpty() && profile.approvedDeviceNames().contains(device);
    }

    private static Decision adaptableSessionResult(
            String domain, Method method, Object token, Set<Object> registry) {
        Class<?> type = method.getReturnType();
        if (type == void.class || type == Void.class) return Decision.handled(null);
        if (type == boolean.class || type == Boolean.class) return Decision.handled(true);
        if (type == int.class || type == Integer.class) return Decision.handled(0);
        if (type == long.class || type == Long.class) return Decision.handled(0L);
        if (type == String.class || type == Object.class) return Decision.handled(token.toString());
        registry.remove(token);
        throw new IllegalStateException("VIRTUAL_" + domain + "_RESULT_ADAPTER_REQUIRED:" + type.getName());
    }

    private static Decision unsupported(String domain, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT)
                + "_OPERATION_UNSUPPORTED:" + method.getName());
    }

    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object result) { return new Decision(true, result); }
    }

    private record SyntheticToken(int id) { }
}
