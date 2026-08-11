package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCompanionDeviceProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaProjectionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualOemSystemServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrintProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsbProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
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
    private final Set<Object> cameraListeners = identitySet();
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
    synchronized int cameraListenerCount() { return cameraListeners.size(); }
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
        if (containsAny(name, "connect", "connectdevice", "getcameracharacteristics",
                "createstream", "createinputstream", "submitrequest", "submitrequestlist",
                "disconnect")) {
            android.util.Log.i("CS_CAMERA_CALL", "method=" + method.getName()
                    + " return=" + method.getReturnType().getName()
                    + " args=" + argumentTypes(arguments)
                    + " mode=" + profile.mode() + " available=" + profile.cameraAvailable()
                    + " ids=" + profile.cameraIds().size() + " source=" + profile.source().kind());
        }
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "disconnect", "close", "release", "remove")) {
            removeIdentity(cameraSessions, arguments);
            removeIdentity(cameraListeners, arguments);
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
            return cameraUserSession(method.getReturnType(), token, cameraSessions, profile, identity);
        }
        if (containsAny(name, "settorchmode", "turnontorch", "turnofftorch")) {
            String cameraId = firstString(arguments);
            if (!profile.allowTorch() || !profile.torchAvailableCameraIds().contains(cameraId)) {
                throw new SecurityException("VIRTUAL_CAMERA_TORCH_DENIED");
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        // CameraManagerGlobal registers this Binder callback before it asks for the
        // camera-id list.  Keep the listener lifecycle bounded and guest-owned; the
        // virtual profile is authoritative for availability, so no host callback is
        // forwarded into the Guest.
        if (name.equals("addlistener")) {
            Object listener = firstIdentity(arguments);
            if (listener != null) {
                addBounded(cameraListeners, listener, 32,
                        "VIRTUAL_CAMERA_LISTENER_LIMIT_EXCEEDED");
                identity.capabilityLeases().register("camera", listener, () -> {
                    synchronized (PeripheralServicesInvocationInterceptor.this) {
                        cameraListeners.remove(listener);
                    }
                    });
            }
            return Decision.handled(cameraStatusArray(method.getReturnType(), profile));
        }
        if (containsAny(name, "getcameracharacteristics", "getcamerainfo")) {
            return Decision.handled(cameraCharacteristics(method.getReturnType(), profile));
        }
        if (name.equals("getconcurrentcameraids")) {
            // No concurrent-camera set is advertised by the single-source profile.  Returning a
            // correctly typed empty array is required by CameraManagerGlobal's discovery loop.
            if (method.getReturnType().isArray()) {
                return Decision.handled(Array.newInstance(method.getReturnType().getComponentType(), 0));
            }
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (captureOperation(name)) return capture(method, arguments, name, profile);
        return unsupported("camera", method);
    }

    /**
     * The Binder service does not have one stable Java return type for camera results.  Keep the
     * source substitution generic and only adapt byte[]/ByteBuffer or a callback whose first
     * parameter is byte[].  Object-shaped Camera2 Image results remain an explicit adapter
     * boundary instead of being reported as a fake successful capture.
     */
    private Decision capture(Method method, Object[] arguments, String name,
            VirtualCameraProfileSnapshot profile) {
        VirtualCameraSourceSnapshot source = profile.source();
        if (!profile.substituteCaptureResult()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_SUBSTITUTION_DISABLED");
        }
        if (source == null || !source.isConfigured()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_NOT_CONFIGURED");
        }
        boolean nv21 = containsAny(name, "nv21", "yuv", "previewcallback");
        byte[] value;
        try {
            value = VirtualCameraCaptureEngine.read(
                    new File(identity.applicationInfo().dataDir, "files"), source, 0L, nv21);
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_SOURCE_FAILED", error);
        }
        Class<?> type = method.getReturnType();
        if (type == byte[].class || type == Object.class) return Decision.handled(value);
        if (type == ByteBuffer.class) return Decision.handled(ByteBuffer.wrap(value));
        if (type == void.class || type == Void.class) {
            if (!dispatchCaptureCallback(arguments, value)) {
                throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_CALLBACK_ADAPTER_REQUIRED");
            }
            return Decision.handled(null);
        }
        throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_RESULT_ADAPTER_REQUIRED:" + type.getName());
    }

    private static boolean captureOperation(String name) {
        return containsAny(name, "takepicture", "capture", "acquirelatestimage",
                "acquirenextimage", "getframe", "nv21", "yuv", "jpeg", "previewcallback");
    }

    private static String argumentTypes(Object[] arguments) {
        if (arguments == null || arguments.length == 0) return "[]";
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < arguments.length; index++) {
            if (index > 0) result.append(',');
            Object value = arguments[index];
            result.append(value == null ? "null" : value.getClass().getName());
        }
        return result.append(']').toString();
    }

    /**
     * API 32's hidden ICameraService.addListener contract returns CameraStatus[].  Returning null
     * makes CameraManagerGlobal dereference a missing status array before it can expose ids.
     * Build only the platform status envelope here; frame data remains owned by the generic
     * source/capture adapter below.
     */
    private static Object cameraStatusArray(Class<?> returnType,
                                            VirtualCameraProfileSnapshot profile) {
        if (!returnType.isArray()) return successValue(returnType);
        Class<?> statusType = returnType.getComponentType();
        Object statuses = Array.newInstance(statusType, profile.cameraIds().size());
        try {
            for (int index = 0; index < profile.cameraIds().size(); index++) {
                Object status = statusType.getDeclaredConstructor().newInstance();
                setStatusField(statusType, status, "cameraId", profile.cameraIds().get(index), true);
                setStatusField(statusType, status, "status", 1, true);
                setStatusField(statusType, status, "unavailablePhysicalCameras", null, false);
                setStatusField(statusType, status, "clientPackage", null, false);
                setStatusField(statusType, status, "deviceId", 0, false);
                Array.set(statuses, index, status);
            }
            return statuses;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_STATUS_ADAPTER_FAILED", error);
        }
    }

    /** Construct the platform's empty metadata envelope without importing hidden camera classes. */
    private static Object cameraCharacteristics(Class<?> returnType,
                                                VirtualCameraProfileSnapshot profile) {
        if (returnType == void.class || returnType == Void.class) return null;
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object metadata = constructor.newInstance();
            // CameraManager reads REQUEST_AVAILABLE_CAPABILITIES while building its
            // physical-camera map.  An empty native envelope is not a valid Camera2
            // characteristics object: getPhysicalCameraIds() asserts when that key is
            // absent.  Populate the minimum backward-compatible profile through the
            // platform's own CameraMetadataNative key marshalling rather than writing
            // native buffers or pretending that open succeeded.
            setCameraMetadata(metadata, "REQUEST_AVAILABLE_CAPABILITIES", new int[]{0});
            setCameraMetadata(metadata, "LENS_FACING", 0);
            setCameraMetadata(metadata, "SENSOR_ORIENTATION", 0);
            setCameraMetadata(metadata, "INFO_SUPPORTED_HARDWARE_LEVEL", 0);
            setCameraMetadata(metadata, "SCALER_AVAILABLE_MAX_DIGITAL_ZOOM", 1.0f);
            setCameraMetadata(metadata, "SCALER_CROPPING_TYPE", 0);
            setCameraMetadata(metadata, "FLASH_INFO_AVAILABLE", false);
            setCameraMetadata(metadata, "LENS_INFO_MINIMUM_FOCUS_DISTANCE", 0.0f);
            setCameraMetadata(metadata, "LENS_INFO_HYPERFOCAL_DISTANCE", 0.0f);
            setCameraMetadata(metadata, "LENS_INFO_AVAILABLE_FOCAL_LENGTHS", new float[]{4.0f});
            setCameraMetadata(metadata, "CONTROL_AE_AVAILABLE_MODES", new int[]{0});
            setCameraMetadata(metadata, "CONTROL_AF_AVAILABLE_MODES", new int[]{0});
            setCameraMetadata(metadata, "CONTROL_AWB_AVAILABLE_MODES", new int[]{0});
            return metadata;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CHARACTERISTICS_ADAPTER_FAILED", error);
        }
    }

    /**
     * CameraManager expects the hidden ICameraDeviceUser Binder contract from connectDevice.
     * A plain Object or a boolean cannot satisfy CameraDeviceImpl's subsequent configure and
     * close calls.  Keep this adapter generic: it is selected by the Android interface type,
     * not by a package name, and every unsupported frame operation remains an explicit error.
     */
    private static Decision cameraUserSession(Class<?> returnType, Object token,
                                              Set<Object> registry,
                                              VirtualCameraProfileSnapshot profile,
                                              GuestIdentity identity) {
        if (!returnType.isInterface()) {
            registry.remove(token);
            throw new IllegalStateException("VIRTUAL_CAMERA_RESULT_ADAPTER_REQUIRED:" + returnType.getName());
        }
        try {
            final int[] nextStreamId = {1};
            Object user = Proxy.newProxyInstance(returnType.getClassLoader(),
                    new Class<?>[]{returnType}, (proxy, method, arguments) -> {
                        String name = normalize(method.getName());
                        if (name.equals("asbinder")) return new android.os.Binder();
                        if (name.equals("tostring")) return "VirtualCameraDeviceUser{" + token + "}";
                        if (name.equals("hashcode")) return System.identityHashCode(proxy);
                        if (name.equals("equals")) return proxy == (arguments == null ? null : arguments[0]);
                        if (name.equals("disconnect")) {
                            registry.remove(token);
                            return null;
                        }
                        if (name.equals("getcamerainfo") || name.equals("createdefaultrequest")) {
                            return cameraCharacteristics(method.getReturnType(), profile);
                        }
                        if (name.equals("createstream")) return nextStreamId[0]++;
                        if (name.equals("createinputstream")) return nextStreamId[0]++;
                        if (name.equals("endconfigure")) return new int[0];
                        if (name.equals("issessionconfigurationsupported")) return true;
                        if (name.equals("submitrequest") || name.equals("submitrequestlist")) {
                            deliverCameraFrame(arguments, profile, identity);
                            return submitInfo(method.getReturnType());
                        }
                        if (name.equals("switchtooffline")) return null;
                        if (name.equals("cancelrequest")) return 0L;
                        if (name.equals("flush")) return 0L;
                        if (method.getReturnType() == void.class
                                || method.getReturnType() == Void.class) return null;
                        if (method.getReturnType() == boolean.class
                                || method.getReturnType() == Boolean.class) return true;
                        if (method.getReturnType() == int.class
                                || method.getReturnType() == Integer.class) return 0;
                        if (method.getReturnType() == long.class
                                || method.getReturnType() == Long.class) return 0L;
                        if (method.getReturnType().isArray()) {
                            return Array.newInstance(method.getReturnType().getComponentType(), 0);
                        }
                        if (method.getReturnType() == Object.class) return null;
                        throw new IllegalStateException("VIRTUAL_CAMERA_DEVICE_OPERATION_UNSUPPORTED:"
                                + method.getName());
                    });
            return Decision.handled(user);
        } catch (RuntimeException error) {
            registry.remove(token);
            throw new IllegalStateException("VIRTUAL_CAMERA_DEVICE_USER_ADAPTER_FAILED", error);
        }
    }

    private static Object submitInfo(Class<?> returnType) {
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor(int.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(1, 0L);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SUBMIT_INFO_ADAPTER_FAILED", error);
        }
    }

    /** Deliver a configured source to the actual Camera2 output Surface(s). */
    private static void deliverCameraFrame(Object[] arguments,
                                           VirtualCameraProfileSnapshot profile,
                                           GuestIdentity identity) {
        if (!profile.substituteCaptureResult() || profile.source() == null
                || !profile.source().isConfigured()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_NOT_CONFIGURED");
        }
        Object request = arguments == null || arguments.length == 0 ? null : arguments[0];
        if (request != null && request.getClass().isArray()) {
            request = Array.getLength(request) == 0 ? null : Array.get(request, 0);
        }
        if (request == null) throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_REQUEST_REQUIRED");
        try {
            Method getTargets = request.getClass().getDeclaredMethod("getTargets");
            getTargets.setAccessible(true);
            Object targets = getTargets.invoke(request);
            if (!(targets instanceof Iterable<?> iterable)) {
                throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_TARGETS_UNAVAILABLE");
            }
            byte[] jpeg = VirtualCameraCaptureEngine.read(
                    new File(identity.applicationInfo().dataDir, "files"),
                    profile.source(), nextFrameTimeMs(profile), false);
            int delivered = 0;
            StringBuilder failures = new StringBuilder();
            for (Object target : iterable) {
                if (!(target instanceof android.view.Surface surface)) continue;
                try {
                    // A Surface object can exist without a connected producer in a virtual
                    // process.  ImageWriter/nativeCreatePlanes aborts the process in that
                    // state, so never cross the native producer boundary without this check.
                    if (!surface.isValid()) {
                        failures.append(surface).append(":invalid-producer;");
                    } else if (deliverToSurface(surface, jpeg)) {
                        delivered++;
                    } else {
                        failures.append(surface).append(":no-adapter;");
                    }
                } catch (Throwable error) {
                    failures.append(surface).append(":")
                            .append(error.getClass().getSimpleName()).append(";");
                }
            }
            if (delivered == 0) {
                throw new IllegalStateException("VIRTUAL_CAMERA_FRAME_DELIVERY_FAILED:" + failures);
            }
            android.util.Log.i("CS_CAMERA_FRAME", "delivered=" + delivered
                    + " source=" + profile.source().kind() + " sha256=" + profile.source().sha256());
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_FRAME_ADAPTER_FAILED", error);
        }
    }

    private static long nextFrameTimeMs(VirtualCameraProfileSnapshot profile) {
        if (profile.source() == null
                || !VirtualCameraSourceSnapshot.VIDEO.equals(profile.source().kind())) {
            return 0L;
        }
        long duration = Math.max(1L, profile.source().durationMs());
        return Math.floorMod(System.currentTimeMillis(), duration);
    }

    private static boolean deliverToSurface(android.view.Surface surface, byte[] jpeg) {
        // ImageReader/JPEG requires a camera3 BLOB-compatible buffer.  Canvas produces RGBA
        // pixels and is therefore only a preview fallback; never use it for an image consumer.
        String descriptor = String.valueOf(surface);
        boolean previewSurface = descriptor.contains("SurfaceTexture")
                || descriptor.contains("SurfaceView");
        if (!previewSurface) {
            int nativeResult = NativePolicy.queueJpeg(surface, jpeg);
            if (nativeResult == 0) return true;
            android.util.Log.w("CS_CAMERA_FRAME", "jpeg_surface_rejected result=" + nativeResult
                    + " surface=" + descriptor);
            return false;
        }
        return deliverToSurfaceCanvas(surface, jpeg);
    }

    private static boolean deliverToSurfaceCanvas(android.view.Surface surface, byte[] jpeg) {
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.length);
        if (bitmap == null) throw new IllegalStateException("VIRTUAL_CAMERA_BITMAP_DECODE_FAILED");
        android.graphics.Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) return false;
            android.graphics.Rect destination = new android.graphics.Rect(0, 0,
                    canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bitmap, null, destination, null);
            return true;
        } finally {
            bitmap.recycle();
            if (canvas != null) {
                surface.unlockCanvasAndPost(canvas);
                android.util.Log.i("CS_CAMERA_PREVIEW", "delivered surface=" + surface);
            }
        }
    }

    private static void setCameraMetadata(Object metadata, String keyName, Object value) {
        try {
            Class<?> characteristics = Class.forName("android.hardware.camera2.CameraCharacteristics");
            Field keyField = characteristics.getDeclaredField(keyName);
            keyField.setAccessible(true);
            Object key = keyField.get(null);
            Method setter = null;
            for (String name : new String[]{"setBase", "set"}) {
                for (Method candidate : metadata.getClass().getDeclaredMethods()) {
                    if (candidate.getName().equals(name) && candidate.getParameterCount() == 2) {
                        setter = candidate;
                        break;
                    }
                }
                if (setter != null) break;
            }
            if (setter == null) throw new NoSuchMethodException("CameraMetadataNative.set");
            setter.setAccessible(true);
            setter.invoke(metadata, key, value);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_METADATA_KEY_FAILED:" + keyName, error);
        }
    }

    private static void setStatusField(Class<?> type, Object target, String name, Object value,
                                       boolean required) throws ReflectiveOperationException {
        Class<?> current = type;
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            }
        }
        if (required) throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static boolean dispatchCaptureCallback(Object[] arguments, byte[] value) {
        if (arguments == null) return false;
        for (Object candidate : arguments) {
            if (candidate == null || candidate instanceof String || candidate instanceof Number
                    || candidate instanceof Boolean || candidate instanceof byte[]
                    || candidate instanceof ByteBuffer || candidate.getClass().isEnum()) continue;
            Method callback = callbackMethod(candidate.getClass());
            if (callback == null) continue;
            Object[] parameters = callbackArguments(callback, arguments, value);
            if (parameters == null) continue;
            try {
                if (!callback.isAccessible()) callback.setAccessible(true);
                callback.invoke(candidate, parameters);
                return true;
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                // Try another callback shape; a failed callback is not a successful substitution.
            }
        }
        return false;
    }

    private static Method callbackMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            String name = normalize(method.getName());
            if (!containsAny(name, "onpicturetaken", "onpreviewframe", "onimageavailable",
                    "oncapture", "onframe")) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length > 0 && parameters[0] == byte[].class) return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            String name = normalize(method.getName());
            if (!containsAny(name, "onpicturetaken", "onpreviewframe", "onimageavailable",
                    "oncapture", "onframe")) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length > 0 && parameters[0] == byte[].class) return method;
        }
        return null;
    }

    private static Object[] callbackArguments(Method callback, Object[] original, byte[] value) {
        Class<?>[] types = callback.getParameterTypes();
        Object[] output = new Object[types.length];
        output[0] = value;
        for (int index = 1; index < types.length; index++) {
            Object match = null;
            for (Object candidate : original) {
                if (candidate != null && types[index].isInstance(candidate)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null && types[index].isPrimitive()) return null;
            output[index] = match;
        }
        return output;
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
