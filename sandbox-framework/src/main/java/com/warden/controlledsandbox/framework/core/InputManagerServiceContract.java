package com.warden.controlledsandbox.framework.core;

import java.lang.reflect.Array;
import java.util.Collections;

/**
 * Contract for the process-local virtual input service.
 *
 * <p>The Guest receives no physical input-device catalog.  Android's framework still requires
 * the generic virtual keyboard entry ({@code -1}) to resolve {@code KeyCharacterMap}; that
 * single synthetic entry is therefore retained while no hardware query or mutating operation
 * is allowed to reach the Host input service.</p>
 */
public final class InputManagerServiceContract {
    public static final String ANDROID_SERVICE = "input";
    public static final String DESCRIPTOR = "android.hardware.input.IInputManager";
    public static final String LOGICAL_SERVICE = "inputManager";
    /** AOSP's synthetic keyboard id used by KeyCharacterMap.load(int). */
    public static final int VIRTUAL_KEYBOARD_ID = -1;

    private InputManagerServiceContract() { }

    public static boolean isNoOpListenerRegistration(String methodName) {
        return "registerInputDevicesChangedListener".equals(methodName);
    }

    /** Returns a fail-closed result for a non-mutating query, without consulting Host state. */
    public static Object controlledResult(String methodName, Class<?> returnType) {
        if (returnType == void.class) {
            if (isNoOpListenerRegistration(methodName)) return null;
            throw new SecurityException("VIRTUAL_INPUT_OPERATION_UNAVAILABLE:" + methodName);
        }
        if ("getInputDeviceIds".equals(methodName) && returnType == int[].class) {
            return new int[] {VIRTUAL_KEYBOARD_ID};
        }
        if ("getInputDevice".equals(methodName)) return null;
        if (returnType == boolean.class) return false;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return -1;
        if (returnType == long.class) return -1L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        if (returnType == char.class) return (char) 0;
        if (returnType.isArray()) return Array.newInstance(returnType.getComponentType(), 0);
        if (java.util.List.class.isAssignableFrom(returnType)) return Collections.emptyList();
        if (java.util.Map.class.isAssignableFrom(returnType)) return Collections.emptyMap();
        return null;
    }
}
