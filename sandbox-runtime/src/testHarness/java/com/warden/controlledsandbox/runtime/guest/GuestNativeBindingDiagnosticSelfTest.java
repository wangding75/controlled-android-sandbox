package com.warden.controlledsandbox.runtime.guest;

public final class GuestNativeBindingDiagnosticSelfTest {
    public static void main(String[] args) {
        GuestNativeBindingDiagnostic.resetForTest();
        ClassLoader first = GuestNativeBindingDiagnosticSelfTest.class.getClassLoader();
        String described = GuestNativeBindingDiagnostic.describeLoader(first);
        require(described.contains("loader="), "loader description includes type");
        require(described.contains(Integer.toHexString(System.identityHashCode(first))),
                "loader description includes identity");

        GuestNativeBindingDiagnostic.recordLoader("test.base", first);
        GuestNativeBindingDiagnostic.recordClass("test.class", GuestNativeBindingDiagnosticSelfTest.class);
        GuestNativeBindingDiagnostic.recordLibraryLookup(first, "c", null);
        GuestNativeBindingDiagnostic.recordNativeLoad("/data/x/libwebviewuc.so",
                GuestNativeBindingDiagnosticSelfTest.class, first, "/data/x/libwebviewuc.so");
        require(GuestNativeBindingDiagnostic.nativeClassName(
                        "No implementation found for void org.example.Native.nativeFoo(boolean)")
                .equals("org.example.Native"),
                "ULE message yields declaring class");
        UnsatisfiedLinkError link = new UnsatisfiedLinkError(
                "No implementation found for void org.example.Native.nativeFoo(boolean)");
        GuestNativeBindingDiagnostic.recordUnsatisfiedLink(link);

        java.util.List<String> events = GuestNativeBindingDiagnostic.snapshotEvents();
        require(events.stream().anyMatch(line -> line.contains("LOADER site=test.base")),
                "loader event recorded");
        require(events.stream().anyMatch(line -> line.contains("LOOKUP name=c")),
                "library lookup recorded");
        require(events.stream().anyMatch(line -> line.contains("LOAD library=libwebviewuc.so")
                        && line.contains("callerClass=")),
                "nativeLoad records library and caller");
        require(events.stream().anyMatch(line -> line.contains("ULE")
                        && line.contains("org.example.Native")),
                "unsatisfied link records declaring class");
        require(GuestNativeBindingDiagnostic.describeClass(String.class).contains("class=java.lang.String"),
                "class description includes name");
        System.out.println("PASS guest native binding diagnostic self-test");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
