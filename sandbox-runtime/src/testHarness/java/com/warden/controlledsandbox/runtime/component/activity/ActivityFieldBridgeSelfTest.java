package com.warden.controlledsandbox.runtime.component.activity;

import java.util.List;
import java.util.Map;

public final class ActivityFieldBridgeSelfTest {
    private ActivityFieldBridgeSelfTest() { }

    public static void main(String[] args) {
        FakeActivity host = new FakeActivity();
        host.required = "host-required";
        host.optional = "host-optional";
        FakeActivity guest = new FakeActivity();
        guest.required = "guest-required";
        guest.direct = "guest-direct";
        ActivityFieldBridge.BridgeReport report = ActivityFieldBridge.installFields(
                host, guest, List.of("required"), List.of("optional", "missing"),
                Map.of("direct", "new-direct"), 36);
        check("host-required".equals(guest.required), "required field not copied");
        check("host-optional".equals(guest.optional), "optional field not copied");
        check("new-direct".equals(guest.direct), "direct field not written");
        check(report.optionalMissingFields().equals(List.of("missing")), "missing optional field not reported");

        expectFailure(() -> ActivityFieldBridge.installFields(host, new MissingRequired(),
                List.of("required"), List.of(), Map.of(), 36), "missing required field must fail closed");
        expectFailure(() -> ActivityFieldBridge.installFields(host, guest,
                List.of("required"), List.of(), Map.of(), 37), "unknown API must fail closed");
        expectFailure(() -> ActivityFieldBridge.installFields(new WrongTypeHost(), new WrongTypeTarget(),
                List.of("required"), List.of(), Map.of(), 36), "type mismatch must fail closed");

        System.out.println("PASS audited Activity field bridge self-test");
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static class FakeActivity {
        Object required;
        Object optional;
        Object direct;
    }
    private static final class MissingRequired { }
    private static final class WrongTypeHost { String required = "wrong"; }
    private static final class WrongTypeTarget { Integer required = 1; }
}
