package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualUsbProfileSnapshot;
import java.lang.reflect.Method;
import java.util.Map;

/** USB device/accessory projection and guest-owned open-device policy. */
final class PeripheralUsbInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralUsbInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualUsbProfileSnapshot profile = state.identity().virtualServices().peripheralServicesProfile().usb();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "closedevice", "release", "unregister")) {
            removeIdentity(state.usbDevices, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getdevicelist")) {
            if (Map.class.isAssignableFrom(method.getReturnType()) || method.getReturnType() == Object.class) {
                return handled(Map.of());
            }
            return handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "getcurrentaccessory", "getaccessorylist")) {
            return handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "hashostsupport")) {
            return handled(booleanValue(method.getReturnType(), profile.hostSupported()));
        }
        if (containsAny(name, "hasaccessorysupport")) {
            return handled(booleanValue(method.getReturnType(), profile.accessorySupported()));
        }
        if (containsAny(name, "hasdevicepermission", "haspermission")) {
            return handled(booleanValue(method.getReturnType(), approved(profile, arguments)));
        }
        if (containsAny(name, "requestdevicepermission", "requestpermission")) {
            if (!profile.allowPermissionRequests() || !approved(profile, arguments)) {
                throw new SecurityException("VIRTUAL_USB_PERMISSION_REQUEST_DENIED");
            }
            return handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "opendevice")) {
            if (!profile.allowOpenDevice() || !approved(profile, arguments)) {
                throw new SecurityException("VIRTUAL_USB_OPEN_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = state.syntheticToken();
            addBounded(state.usbDevices, token, profile.maximumOpenDevices(),
                    "VIRTUAL_USB_OPEN_DEVICE_LIMIT_EXCEEDED");
            return adaptableSessionResult("USB", method, token, state.usbDevices);
        }
        if (containsAny(name, "getcurrentfunctions", "getdefaultfunctions")) {
            return handled(stringValue(method.getReturnType(), profile.defaultFunctions()));
        }
        if (containsAny(name, "setcurrentfunctions", "setfunctions", "resetusb")) {
            throw new SecurityException("VIRTUAL_USB_FUNCTION_MUTATION_DENIED");
        }
        return unsupported("usb", method);
    }

    private static boolean approved(VirtualUsbProfileSnapshot profile, Object[] arguments) {
        String device = firstString(arguments);
        return !device.isEmpty() && profile.approvedDeviceNames().contains(device);
    }
}
