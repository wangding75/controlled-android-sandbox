package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import com.warden.controlledsandbox.runtime.protocol.ComponentOperations;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.util.List;

public final class IsolatedProcessRoutePolicySelfTest {
    private IsolatedProcessRoutePolicySelfTest() { }

    public static void main(String[] args) {
        VirtualComponentSnapshot normal = new VirtualComponentSnapshot("SERVICE",
                "com.example.NormalService", "com.example:remote", false, true, false,
                "", "", List.of());
        VirtualComponentSnapshot isolated = new VirtualComponentSnapshot("SERVICE",
                ".IsolatedService", "com.example:isolated", false, true, true,
                "", "", List.of());
        VirtualComponentSnapshot provider = new VirtualComponentSnapshot("PROVIDER",
                ".PrivateProvider", "com.example:isolated_provider", false, true, true,
                "com.example.private;com.example.private.alt", "", List.of());
        VirtualPackageStateSnapshot state = new VirtualPackageStateSnapshot("com.example", 0,
                "Example", "1", 1L, digest('a'), digest('b'), ".MainActivity", "", true,
                List.of(normal, isolated, provider), List.of(), List.of());

        Bundle normalRequest = request(state, "com.example.NormalService");
        IsolatedProcessRoutePolicy.rejectOrdinaryRoute(normalRequest);

        expectBlocked(request(state, "com.example.IsolatedService"));
        expectBlocked(request(state, ".IsolatedService"));

        Bundle providerRequest = request(state, "");
        providerRequest.putString(ComponentOperations.AUTHORITY, "com.example.private.alt");
        expectBlocked(providerRequest);

        Bundle noState = new Bundle();
        noState.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.IsolatedService");
        IsolatedProcessRoutePolicy.rejectOrdinaryRoute(noState);
        System.out.println("PASS broker isolated-process route policy self-test");
    }

    private static Bundle request(VirtualPackageStateSnapshot state, String component) {
        Bundle out = new Bundle();
        out.putParcelable(RuntimeKeys.PACKAGE_STATE, state);
        out.putString(RuntimeKeys.COMPONENT_CLASS, component);
        return out;
    }

    private static void expectBlocked(Bundle request) {
        try {
            IsolatedProcessRoutePolicy.rejectOrdinaryRoute(request);
        } catch (UnsupportedOperationException expected) {
            if (!String.valueOf(expected.getMessage()).startsWith(
                    "ISOLATED_PROCESS_DEDICATED_UID_TRANSPORT_REQUIRED:")) {
                throw new AssertionError("wrong isolated-process error", expected);
            }
            return;
        }
        throw new AssertionError("isolated component must fail before ordinary process allocation");
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
