package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPrintProfileSnapshot;
import java.lang.reflect.Method;

/** Printing service projection and bounded guest print-job ownership. */
final class PeripheralPrintInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralPrintInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualPrintProfileSnapshot profile = state.identity().virtualServices().peripheralServicesProfile().printing();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "cancelprintjob", "removeprintjob", "finishprintjob", "destroy")) {
            removeIdentity(state.printJobs, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "isprintingenabled", "isprintserviceenabled")) {
            return handled(booleanValue(method.getReturnType(), profile.printingEnabled()));
        }
        if (containsAny(name, "getprintservices", "getenabledprintservices")) {
            return handled(stringArrayOrList(method.getReturnType(), profile.availablePrintServices()));
        }
        if (containsAny(name, "getprintjobinfos", "getprintjobs")) {
            return handled(emptyCollection(method.getReturnType()));
        }
        if (containsAny(name, "getdefaultprinterid")) {
            return handled(stringValue(method.getReturnType(), profile.defaultPrinterId()));
        }
        if (containsAny(name, "getdefaultprintername")) {
            return handled(stringValue(method.getReturnType(), profile.defaultPrinterName()));
        }
        if (containsAny(name, "print", "createprintjob")) {
            if (!profile.printingEnabled() || !profile.allowPrintJobs()) {
                throw new SecurityException("VIRTUAL_PRINT_JOB_DENIED");
            }
            Object token = firstIdentity(arguments);
            if (token == null) token = state.syntheticToken();
            addBounded(state.printJobs, token, profile.maximumActiveJobs(),
                    "VIRTUAL_PRINT_JOB_LIMIT_EXCEEDED");
            return adaptableSessionResult("PRINT_JOB", method, token, state.printJobs);
        }
        if (containsAny(name, "restartprintjob", "setprintserviceenabled")) {
            throw new SecurityException("VIRTUAL_PRINT_MUTATION_DENIED");
        }
        return unsupported("printing", method);
    }
}
