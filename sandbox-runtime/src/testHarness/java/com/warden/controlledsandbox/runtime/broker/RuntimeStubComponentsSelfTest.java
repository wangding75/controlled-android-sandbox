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

        VirtualPackageStateSnapshot state = new VirtualPackageStateSnapshot("com.example", 0,
                "Example", "1", 1L, digest('a'), digest('b'),
                "com.example.MainActivity", "", true, components, List.of(), List.of());

        String main = RuntimeStubComponents.activityComponentFor(3,
                "com.example.MainActivity", state);
        check(main.endsWith("StubActivity3"), "first declared Activity uses the slot base stub");

        String holder = RuntimeStubComponents.activityComponentFor(3,
                "com.warden.controlledsandbox.fixture.SystemHolderPendingIntentActivity", state);
        check(holder.contains("StubActivitySlotVariants0$S3V1"),
                "system-holder Activity must enter production alias mapping, got " + holder);

        try {
            RuntimeStubComponents.activityComponentFor(3, "com.example.MissingActivity", state);
            throw new AssertionError("missing Activity must fail closed");
        } catch (IllegalArgumentException expected) {
            check(String.valueOf(expected.getMessage()).startsWith("GUEST_ACTIVITY_NOT_IN_PACKAGE_STATE:"),
                    "missing Activity must not be reported as alias-pool exhaustion");
        }
        System.out.println("PASS production Activity stub/alias mapping includes later fixture Activities");
    }

    private static VirtualComponentSnapshot activity(String className) {
        return new VirtualComponentSnapshot("ACTIVITY", className, "com.example",
                true, true, false, "", "", List.of());
    }

    private static String digest(char value) { return String.valueOf(value).repeat(64); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
