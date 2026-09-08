package com.warden.controlledsandbox.fixture.libraryprovider;

/** Value loaded by the independently installed consumer fixture. */
public final class ProviderLibraryMarker {
    private ProviderLibraryMarker() { }

    public static String value() {
        return "P1_PROVIDER_CLASS_OK";
    }
}
