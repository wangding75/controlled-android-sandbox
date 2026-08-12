package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.view.Surface;
import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.nativebridge.NativePolicy;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Set;

/** Camera discovery, typed Binder session and source/frame adaptation boundary. */
final class PeripheralCameraInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralCameraInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualCameraProfileSnapshot profile = state.identity().virtualServices()
                .peripheralServicesProfile().camera();
        String name = normalize(method.getName());
        if (containsAny(name, "connect", "connectdevice", "getcameracharacteristics",
                "createstream", "createinputstream", "submitrequest", "submitrequestlist",
                "getcaptureresultmetadataqueue", "disconnect")) {
            android.util.Log.i("CS_CAMERA_CALL", "method=" + method.getName()
                    + " return=" + method.getReturnType().getName()
                    + " args=" + argumentTypes(arguments)
                    + " mode=" + profile.mode() + " available=" + profile.cameraAvailable()
                    + " ids=" + profile.cameraIds().size() + " source=" + profile.source().kind());
        }
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "disconnect", "close", "release", "remove")) {
            removeIdentity(state.cameraSessions, arguments);
            removeIdentity(state.cameraListeners, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getcameralist", "getcameraidlist")) {
            return handled(stringArrayOrList(method.getReturnType(), profile.cameraIds()));
        }
        if (containsAny(name, "getnumberofcameras")) {
            return handled(numeric(method.getReturnType(), profile.cameraIds().size()));
        }
        if (containsAny(name, "isavailable", "hascamera")) {
            return handled(booleanValue(method.getReturnType(), profile.cameraAvailable()));
        }
        if (containsAny(name, "isfrontcamera")) {
            return handled(booleanValue(method.getReturnType(),
                    profile.frontCameraIds().contains(firstString(arguments))));
        }
        if (containsAny(name, "connect", "opencamera", "connectdevice")) {
            String cameraId = firstString(arguments);
            if (!profile.cameraAvailable() || !profile.allowOpen()
                    || !profile.cameraIds().contains(cameraId)) {
                throw new SecurityException("VIRTUAL_CAMERA_OPEN_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = state.syntheticToken();
            addBounded(state.cameraSessions, token, profile.maximumOpenCameras(),
                    "VIRTUAL_CAMERA_SESSION_LIMIT_EXCEEDED");
            return cameraUserSession(method.getReturnType(), token, profile);
        }
        if (containsAny(name, "settorchmode", "turnontorch", "turnofftorch")) {
            String cameraId = firstString(arguments);
            if (!profile.allowTorch() || !profile.torchAvailableCameraIds().contains(cameraId)) {
                throw new SecurityException("VIRTUAL_CAMERA_TORCH_DENIED");
            }
            return handled(successValue(method.getReturnType()));
        }
        if (name.equals("addlistener")) {
            Object listener = firstIdentity(arguments);
            if (listener != null) {
                addBounded(state.cameraListeners, listener, 32,
                        "VIRTUAL_CAMERA_LISTENER_LIMIT_EXCEEDED");
                Object captured = listener;
                state.identity().capabilityLeases().register("camera", listener,
                        () -> state.removeCameraListener(captured));
            }
            return handled(cameraStatusArray(method.getReturnType(), profile));
        }
        if (containsAny(name, "getcameracharacteristics", "getcamerainfo")) {
            return handled(cameraCharacteristics(method.getReturnType()));
        }
        if (name.equals("getconcurrentcameraids")) {
            if (method.getReturnType().isArray()) {
                return handled(Array.newInstance(method.getReturnType().getComponentType(), 0));
            }
            return handled(emptyValue(method.getReturnType()));
        }
        if (captureOperation(name)) return capture(method, arguments, name, profile);
        return unsupported("camera", method);
    }

    private PeripheralServicesInvocationInterceptor.Decision capture(
            Method method, Object[] arguments, String name, VirtualCameraProfileSnapshot profile) {
        VirtualCameraSourceSnapshot source = profile.source();
        if (!profile.substituteCaptureResult()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_SUBSTITUTION_DISABLED");
        }
        if (source == null || !source.isConfigured()) {
            throw new IllegalStateException("VIRTUAL_CAMERA_SOURCE_NOT_CONFIGURED");
        }
        byte[] value;
        try {
            value = VirtualCameraCaptureEngine.read(
                    new File(state.identity().applicationInfo().dataDir, "files"), source, 0L,
                    containsAny(name, "nv21", "yuv", "previewcallback"));
        } catch (Exception error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_SOURCE_FAILED", error);
        }
        Class<?> type = method.getReturnType();
        if (type == byte[].class || type == Object.class) return handled(value);
        if (type == ByteBuffer.class) return handled(ByteBuffer.wrap(value));
        if (type == void.class || type == Void.class) {
            if (!dispatchCaptureCallback(arguments, value)) {
                throw new IllegalStateException("VIRTUAL_CAMERA_CAPTURE_CALLBACK_ADAPTER_REQUIRED");
            }
            return handled(null);
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

    private static Object cameraStatusArray(Class<?> returnType, VirtualCameraProfileSnapshot profile) {
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

    private static Object cameraCharacteristics(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) return null;
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object metadata = constructor.newInstance();
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

    private PeripheralServicesInvocationInterceptor.Decision cameraUserSession(
            Class<?> returnType, Object token, VirtualCameraProfileSnapshot profile) {
        // Some controlled service contracts represent a successful connect with a textual
        // session token rather than the hidden Binder interface used by CameraManager. Keep that
        // explicit typed adaptation in the common value policy; never return a textual value for
        // an interface-shaped platform result.
        if (returnType == String.class || returnType == Object.class
                || returnType == void.class || returnType == Void.class
                || returnType == boolean.class || returnType == Boolean.class
                || returnType == int.class || returnType == Integer.class
                || returnType == long.class || returnType == Long.class) {
            return adaptableSessionResult("CAMERA", returnType, token, state.cameraSessions);
        }
        if (!returnType.isInterface()) {
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
                            state.cameraSessions.remove(token);
                            return null;
                        }
                        // Android 16 exposes the optional FMQ result-metadata descriptor during
                        // CameraDevice setup.  The virtual camera delivers controlled frames
                        // through the existing request/output adapter, so there is no metadata
                        // FMQ to publish.  A null descriptor is the platform's optional-queue
                        // absence contract and lets CameraDeviceImpl continue with Binder results.
                        if (name.equals("getcaptureresultmetadataqueue")) {
                            return emptyCaptureResultMetadataQueue(method.getReturnType());
                        }
                        if (name.equals("getcamerainfo") || name.equals("createdefaultrequest")) {
                            return cameraCharacteristics(method.getReturnType());
                        }
                        if (name.equals("createstream") || name.equals("createinputstream")) return nextStreamId[0]++;
                        if (name.equals("endconfigure")) return new int[0];
                        if (name.equals("issessionconfigurationsupported")) return true;
                        if (name.equals("submitrequest") || name.equals("submitrequestlist")) {
                            deliverCameraFrame(arguments, profile);
                            return submitInfo(method.getReturnType());
                        }
                        if (name.equals("switchtooffline")) return null;
                        if (name.equals("cancelrequest") || name.equals("flush")) return 0L;
                        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) return null;
                        if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return true;
                        if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return 0;
                        if (method.getReturnType() == long.class || method.getReturnType() == Long.class) return 0L;
                        if (method.getReturnType().isArray()) {
                            return Array.newInstance(method.getReturnType().getComponentType(), 0);
                        }
                        if (method.getReturnType() == Object.class) return null;
                        throw new IllegalStateException("VIRTUAL_CAMERA_DEVICE_OPERATION_UNSUPPORTED:"
                                + method.getName());
                    });
            return handled(user);
        } catch (RuntimeException error) {
            state.cameraSessions.remove(token);
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

    /**
     * API 36 parcels the optional result-metadata FMQ descriptor during camera open.  The
     * controlled camera has no metadata queue, but CameraDeviceImpl still requires a parcelable
     * envelope.  Keep the envelope typed and empty; frame delivery remains on the request/output
     * path above.
     */
    private static Object emptyCaptureResultMetadataQueue(Class<?> returnType) {
        try {
            Constructor<?> constructor = returnType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object descriptor = constructor.newInstance();
            setField(descriptor, returnType, "flags", 0);
            setField(descriptor, returnType, "quantum", 1);
            Field grantors = field(returnType, "grantors");
            grantors.set(descriptor, Array.newInstance(grantors.getType().getComponentType(), 0));
            Field handle = field(returnType, "handle");
            Object nativeHandle = handle.getType().getDeclaredConstructor().newInstance();
            setField(nativeHandle, handle.getType(), "fds", Array.newInstance(
                    field(handle.getType(), "fds").getType().getComponentType(), 0));
            setField(nativeHandle, handle.getType(), "ints", Array.newInstance(
                    field(handle.getType(), "ints").getType().getComponentType(), 0));
            handle.set(descriptor, nativeHandle);
            return descriptor;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("VIRTUAL_CAMERA_METADATA_QUEUE_ADAPTER_FAILED", error);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field result = current.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void setField(Object target, Class<?> type, String name, Object value)
            throws ReflectiveOperationException {
        field(type, name).set(target, value);
    }

    private void deliverCameraFrame(Object[] arguments, VirtualCameraProfileSnapshot profile) {
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
                    new File(state.identity().applicationInfo().dataDir, "files"),
                    profile.source(), nextFrameTimeMs(profile), false);
            int delivered = 0;
            StringBuilder failures = new StringBuilder();
            for (Object target : iterable) {
                if (!(target instanceof Surface surface)) continue;
                try {
                    if (!surface.isValid()) failures.append(surface).append(":invalid-producer;");
                    else if (deliverToSurface(surface, jpeg)) delivered++;
                    else failures.append(surface).append(":no-adapter;");
                } catch (Throwable error) {
                    failures.append(surface).append(":").append(error.getClass().getSimpleName()).append(';');
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
        if (profile.source() == null || !VirtualCameraSourceSnapshot.VIDEO.equals(profile.source().kind())) return 0L;
        return Math.floorMod(System.currentTimeMillis(), Math.max(1L, profile.source().durationMs()));
    }

    private static boolean deliverToSurface(Surface surface, byte[] jpeg) {
        String descriptor = String.valueOf(surface);
        boolean previewSurface = descriptor.contains("SurfaceTexture") || descriptor.contains("SurfaceView");
        if (!previewSurface) {
            int nativeResult = NativePolicy.queueJpeg(surface, jpeg);
            if (nativeResult == 0) return true;
            android.util.Log.w("CS_CAMERA_FRAME", "jpeg_surface_rejected result=" + nativeResult
                    + " surface=" + descriptor);
            return false;
        }
        return deliverToSurfaceCanvas(surface, jpeg);
    }

    private static boolean deliverToSurfaceCanvas(Surface surface, byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) throw new IllegalStateException("VIRTUAL_CAMERA_BITMAP_DECODE_FAILED");
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) return false;
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
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
            Field keyField = CameraCharacteristics.class.getDeclaredField(keyName);
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
                Field field = current.getDeclaredField(name);
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
                // A failed callback is not a successful capture substitution.
            }
        }
        return false;
    }

    private static Method callbackMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (captureCallback(method)) return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (captureCallback(method)) return method;
        }
        return null;
    }

    private static boolean captureCallback(Method method) {
        String name = normalize(method.getName());
        if (!containsAny(name, "onpicturetaken", "onpreviewframe", "onimageavailable",
                "oncapture", "onframe")) return false;
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length > 0 && parameters[0] == byte[].class;
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
}
