package com.warden.controlledsandbox.framework.core;

/** Pure contract checks for the bounded virtual input service. */
public final class InputManagerServiceContractSelfTest {
    private InputManagerServiceContractSelfTest() { }

    public static void main(String[] args) {
        require("input".equals(InputManagerServiceContract.ANDROID_SERVICE), "service name");
        require("android.hardware.input.IInputManager".equals(
                InputManagerServiceContract.DESCRIPTOR), "descriptor");
        require(InputManagerServiceContract.isNoOpListenerRegistration(
                "registerInputDevicesChangedListener"), "listener registration is no-op");
        require(!InputManagerServiceContract.isNoOpListenerRegistration("enableInputDevice"),
                "mutator is not listener registration");
        require(((int[]) InputManagerServiceContract.controlledResult(
                "getInputDeviceIds", int[].class)).length == 0, "device ids are virtual empty");
        require(InputManagerServiceContract.controlledResult(
                "getInputDevice", Object.class) == null, "device object is unavailable");
        require(Boolean.FALSE.equals(InputManagerServiceContract.controlledResult(
                "hasKeys", boolean.class)), "key query fails closed");
        boolean denied = false;
        try { InputManagerServiceContract.controlledResult("injectInputEvent", void.class); }
        catch (SecurityException expected) { denied = true; }
        require(denied, "mutating void operation denied");
        System.out.println("PASS InputManager service contract self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
