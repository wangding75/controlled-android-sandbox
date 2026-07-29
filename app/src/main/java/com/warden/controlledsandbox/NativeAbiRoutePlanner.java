package com.warden.controlledsandbox;

/** Decides which process architecture may execute a Guest's native code. */
final class NativeAbiRoutePlanner {
    enum Route { HOST_64, COMPANION_32 }

    private NativeAbiRoutePlanner() { }

    static Route route(String nativeAbi) {
        String abi = nativeAbi == null ? "" : nativeAbi.trim();
        if (abi.isEmpty()) return Route.HOST_64;
        return switch (abi) {
            case "arm64-v8a", "x86_64" -> Route.HOST_64;
            case "armeabi-v7a", "x86" -> Route.COMPANION_32;
            case "legacy-unknown" -> throw new IllegalStateException("NATIVE_ABI_METADATA_REQUIRED");
            default -> throw new IllegalStateException("UNSUPPORTED_NATIVE_ABI:" + abi);
        };
    }

    static boolean requiresCompanion(String nativeAbi) {
        return route(nativeAbi) == Route.COMPANION_32;
    }
}
