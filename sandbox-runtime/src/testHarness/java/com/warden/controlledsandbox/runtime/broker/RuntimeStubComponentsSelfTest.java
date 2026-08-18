package com.warden.controlledsandbox.runtime.broker;

import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.contract.VirtualPackageStateSnapshot;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeStubComponentsSelfTest {
    private RuntimeStubComponentsSelfTest() { }

    public static void main(String[] args) {
        List<VirtualComponentSnapshot> components = new ArrayList<>();
        components.add(activity("com.example.MainActivity"));
        components.add(new VirtualComponentSnapshot("SERVICE", "com.example.Svc",
                "com.example", false, true, false, "", "", List.of()));
        components.add(activity("com.warden.controlledsandbox.fixture.SystemHolderPendingIntentActivity"));
        for (int index = 0; index < 128; index++) {
            components.add(activity("com.example.ScaleActivity" + index));
        }
        components.add(translucent("com.example.DialogActivity"));

        VirtualPackageStateSnapshot state = new VirtualPackageStateSnapshot("com.example", 0,
                "Example", "1", 1L, digest('a'), digest('b'),
                "com.example.MainActivity", "", true, components, List.of(), List.of());

        String main = RuntimeStubComponents.activityComponentFor(3,
                "com.example.MainActivity", state);
        check(main.endsWith("StubActivity3"), "opaque Guest Activity uses the slot opaque stub");

        String holder = RuntimeStubComponents.activityComponentFor(3,
                "com.warden.controlledsandbox.fixture.SystemHolderPendingIntentActivity", state);
        check(holder.endsWith("StubActivity3"),
                "later Guest Activity must reuse the slot stub, got " + holder);

        String index127 = RuntimeStubComponents.activityComponentFor(3,
                "com.example.ScaleActivity127", state);
        check(index127.endsWith("StubActivity3"),
                "declaration index 127 must not create a Host class, got " + index127);

        String dialog = RuntimeStubComponents.activityComponentFor(3,
                "com.example.DialogActivity", state);
        check(dialog.endsWith("StubActivityTranslucent3"),
                "translucent theme must select the translucent family, got " + dialog);

        try {
            RuntimeStubComponents.activityComponentFor(3, "com.example.MissingActivity", state);
            throw new AssertionError("missing Activity must fail closed");
        } catch (IllegalArgumentException expected) {
            check(String.valueOf(expected.getMessage()).startsWith("GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE:"),
                    "missing Activity must not be reported as alias-pool exhaustion");
        }
        System.out.println("PASS physical stub identity is slot x window family, not Guest index");
    }

    private static VirtualComponentSnapshot activity(String className) {
        return new VirtualComponentSnapshot("ACTIVITY", className, "com.example",
                true, true, false, "", "", List.of());
    }

    private static VirtualComponentSnapshot translucent(String className) {
        return new VirtualComponentSnapshot("ACTIVITY", className, "com.example",
                true, true, false, "", "", "", "", false, "DEFAULT", List.of(), List.of(),
                List.of(), 16973831);
    }

    private static String digest(char value) { return String.valueOf(value).repeat(64); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
