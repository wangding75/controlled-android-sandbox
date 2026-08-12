package com.warden.controlledsandbox.framework.core;

import static com.warden.controlledsandbox.framework.core.PeripheralInvocationValues.*;

import com.warden.controlledsandbox.contract.VirtualNfcProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualPeripheralServicesProfileSnapshot;
import java.lang.reflect.Method;

/** NFC adapter projection and guest-owned reader/tag session policy. */
final class PeripheralNfcInvocationHandler implements PeripheralServiceInvocationHandler {
    private final PeripheralInvocationState state;

    PeripheralNfcInvocationHandler(PeripheralInvocationState state) { this.state = state; }

    @Override public PeripheralServicesInvocationInterceptor.Decision before(
            Method method, Object[] arguments, VirtualPeripheralServicesProfileSnapshot ignored) {
        VirtualNfcProfileSnapshot profile = state.identity().virtualServices().peripheralServicesProfile().nfc();
        String name = normalize(method.getName());
        if (host(profile.mode())) return PeripheralServicesInvocationInterceptor.Decision.passThrough();
        if (containsAny(name, "disablereadermode", "unregister", "close", "release")) {
            removeIdentity(state.nfcReaders, arguments);
            return handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) return handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getstate", "getadapterstate")) {
            return handled(numeric(method.getReturnType(), adapterState(profile.adapterState())));
        }
        if (containsAny(name, "isenabled")) {
            return handled(booleanValue(method.getReturnType(), "ON".equals(profile.adapterState())));
        }
        if (containsAny(name, "enable", "disable") && !name.contains("readermode")) {
            throw new SecurityException("VIRTUAL_NFC_ADAPTER_MUTATION_DENIED");
        }
        if (containsAny(name, "enablereadermode", "registerreader")) {
            if (!profile.readerModeAllowed()) throw new SecurityException("VIRTUAL_NFC_READER_MODE_DENIED");
            Object callback = firstIdentity(arguments);
            if (callback == null) callback = state.syntheticToken();
            addBounded(state.nfcReaders, callback, profile.maximumReaderSessions(),
                    "VIRTUAL_NFC_READER_SESSION_LIMIT_EXCEEDED");
            return handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "gettagids", "listtags")) {
            return handled(stringArrayOrList(method.getReturnType(), profile.tagIds()));
        }
        if (containsAny(name, "transceive", "ndef", "tagoperation")) {
            if (state.tagOperations >= profile.maximumTagOperations()) {
                throw new IllegalStateException("VIRTUAL_NFC_TAG_OPERATION_LIMIT_EXCEEDED");
            }
            String tag = firstString(arguments);
            if (!tag.isEmpty() && !profile.tagIds().contains(tag)) {
                throw new SecurityException("VIRTUAL_NFC_TAG_NOT_APPROVED");
            }
            state.tagOperations++;
            return handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "iscardemulation", "hascardemulation")) {
            return handled(booleanValue(method.getReturnType(), profile.cardEmulationAvailable()));
        }
        if (containsAny(name, "isndefpushenabled")) {
            return handled(booleanValue(method.getReturnType(), profile.ndefPushEnabled()));
        }
        if (containsAny(name, "getnfc", "gettaginterface", "getcardemulationinterface")) {
            return handled(null);
        }
        return unsupported("nfc", method);
    }
}
