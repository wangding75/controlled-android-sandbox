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

        Bundle normalRequest = request(state, "com.example.NormalService", ComponentOperations.START_SERVICE);
        check(IsolatedProcessRoutePolicy.match(normalRequest) == null, "normal service must not match isolated route");
        IsolatedProcessRoutePolicy.rejectOrdinaryRoute(normalRequest);

        Bundle isolatedRequest = request(state, ".IsolatedService", ComponentOperations.START_SERVICE);
        IsolatedProcessRoutePolicy.Match match = IsolatedProcessRoutePolicy.requireIsolatedService(isolatedRequest);
        check("com.example.IsolatedService".equals(match.componentClass()), "isolated class normalization failed");
        check("com.example:isolated_com_example_IsolatedService".equals(match.processName()),
                "isolated process name must be deterministic");
        expectOrdinaryBlocked(isolatedRequest);

        Bundle providerRequest = request(state, "", ComponentOperations.PROVIDER_QUERY);
        providerRequest.putString(ComponentOperations.AUTHORITY, "com.example.private.alt");
        check(IsolatedProcessRoutePolicy.match(providerRequest) != null, "isolated provider lookup failed");
        expectUnsupported(providerRequest, "ISOLATED_PROCESS_ONLY_SERVICE_SUPPORTED:");

        Bundle wrongOperation = request(state, ".IsolatedService", ComponentOperations.SEND_BROADCAST);
        expectUnsupported(wrongOperation, "ISOLATED_PROCESS_NON_SERVICE_OPERATION_REJECTED:");

        Bundle noState = new Bundle();
        noState.putString(RuntimeKeys.COMPONENT_CLASS, "com.example.IsolatedService");
        check(IsolatedProcessRoutePolicy.match(noState) == null, "missing package state must not guess");
        IsolatedProcessRoutePolicy.rejectOrdinaryRoute(noState);
        System.out.println("PASS broker dedicated isolated-process route policy self-test");
    }

    private static Bundle request(VirtualPackageStateSnapshot state, String component, String operation) {
        Bundle out = new Bundle();
        out.putParcelable(RuntimeKeys.PACKAGE_STATE, state);
        out.putString(RuntimeKeys.COMPONENT_CLASS, component);
        out.putString(ComponentOperations.OPERATION, operation);
        return out;
    }

    private static void expectOrdinaryBlocked(Bundle request) {
        try { IsolatedProcessRoutePolicy.rejectOrdinaryRoute(request); }
        catch (UnsupportedOperationException expected) {
            check(String.valueOf(expected.getMessage()).startsWith(
                    "ISOLATED_PROCESS_DEDICATED_UID_TRANSPORT_REQUIRED:"), "wrong ordinary-route error");
            return;
        }
        throw new AssertionError("isolated component must fail before ordinary process allocation");
    }

    private static void expectUnsupported(Bundle request, String prefix) {
        try { IsolatedProcessRoutePolicy.requireIsolatedService(request); }
        catch (UnsupportedOperationException expected) {
            check(String.valueOf(expected.getMessage()).startsWith(prefix), "wrong isolated-route error");
            return;
        }
        throw new AssertionError("unsupported isolated route must fail closed");
    }

    private static String digest(char value) { return String.valueOf(value).repeat(64); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
