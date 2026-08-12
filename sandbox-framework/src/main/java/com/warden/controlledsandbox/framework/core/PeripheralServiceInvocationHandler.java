package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.lang.reflect.Method;

/** Typed capability handler selected by the single peripheral service classifier. */
interface PeripheralServiceInvocationHandler {
    PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot profile);
}
