package com.warden.controlledsandbox.nativebridge;

/** JNI boundary for the clean-room native path and network policy engine. */
public final class NativePolicy {
    private static final boolean AVAILABLE;
    private static volatile String loadError = "";

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("controlled_sandbox_native");
            loaded = true;
        } catch (Throwable error) {
            loadError = error.getClass().getName() + ":" + String.valueOf(error.getMessage());
        }
        AVAILABLE = loaded;
    }

    private NativePolicy() { }

    public static boolean available() { return AVAILABLE; }
    public static String loadError() { return loadError; }

    public static boolean configure(String sessionId, long generation, String packageName,
                                    String processName, int virtualUserId, int virtualUid,
                                    int virtualPid, String abiName, String instanceRoot, String apkPath,
                                    String nativeLibraryRoot, boolean defaultNetworkAllow,
                                    String[] allowHosts, String[] denyHosts,
                                    String[] allowCidrs, String[] denyCidrs) {
        return configure(sessionId, generation, packageName, processName, virtualUserId, virtualUid,
                virtualPid, abiName, instanceRoot, apkPath, nativeLibraryRoot, defaultNetworkAllow,
                allowHosts, denyHosts, allowCidrs, denyCidrs, new String[0], new String[0],
                NativeNetworkIdentity.isolated(packageName, virtualUserId));
    }

    public static boolean configure(String sessionId, long generation, String packageName,
                                    String processName, int virtualUserId, int virtualUid,
                                    int virtualPid, String abiName, String instanceRoot, String apkPath,
                                    String nativeLibraryRoot, boolean defaultNetworkAllow,
                                    String[] allowHosts, String[] denyHosts,
                                    String[] allowCidrs, String[] denyCidrs,
                                    String[] allowCidrsV6, String[] denyCidrsV6,
                                    NativeNetworkIdentity networkIdentity) {
        if (networkIdentity == null) throw new IllegalArgumentException("networkIdentity is required");
        return configure(sessionId, generation, packageName, processName, virtualUserId, virtualUid,
                virtualPid, abiName, instanceRoot, apkPath, nativeLibraryRoot, defaultNetworkAllow,
                allowHosts, denyHosts, allowCidrs, denyCidrs, allowCidrsV6, denyCidrsV6,
                networkIdentity.hostname(), networkIdentity.interfaceName(),
                networkIdentity.ipv4Address(), networkIdentity.ipv6Address(),
                networkIdentity.proxyHost(), networkIdentity.proxyPort(),
                networkIdentity.cleartextPermitted());
    }

    public static boolean configure(String sessionId, long generation, String packageName,
                                    String processName, int virtualUserId, int virtualUid,
                                    int virtualPid, String abiName, String instanceRoot, String apkPath,
                                    String nativeLibraryRoot, boolean defaultNetworkAllow,
                                    String[] allowHosts, String[] denyHosts,
                                    String[] allowCidrs, String[] denyCidrs,
                                    String[] allowCidrsV6, String[] denyCidrsV6,
                                    String virtualHostname, String virtualInterfaceName,
                                    String virtualIpv4, String virtualIpv6,
                                    String proxyHost, int proxyPort, boolean cleartextPermitted) {
        if (!AVAILABLE) return false;
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("sessionId is required");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (packageName == null || packageName.trim().isEmpty()) throw new IllegalArgumentException("packageName is required");
        if (processName == null || processName.trim().isEmpty()) throw new IllegalArgumentException("processName is required");
        if (virtualUserId < 0) throw new IllegalArgumentException("virtualUserId must be non-negative");
        if (virtualUid < 0) throw new IllegalArgumentException("virtualUid must be non-negative");
        if (virtualPid < 1) throw new IllegalArgumentException("virtualPid must be positive");
        if (abiName == null || abiName.trim().isEmpty()) throw new IllegalArgumentException("abiName is required");
        if (instanceRoot == null || instanceRoot.trim().isEmpty()) throw new IllegalArgumentException("instanceRoot is required");
        if (apkPath == null || apkPath.trim().isEmpty()) throw new IllegalArgumentException("apkPath is required");
        if (virtualHostname == null || virtualHostname.trim().isEmpty()) throw new IllegalArgumentException("virtualHostname is required");
        if (virtualInterfaceName == null || virtualInterfaceName.trim().isEmpty()) throw new IllegalArgumentException("virtualInterfaceName is required");
        if (proxyPort < 0 || proxyPort > 65535) throw new IllegalArgumentException("proxyPort out of range");
        return nativeConfigure(sessionId, generation, packageName, processName, virtualUserId,
                virtualUid, virtualPid, abiName, instanceRoot, apkPath,
                nativeLibraryRoot == null ? "" : nativeLibraryRoot,
                defaultNetworkAllow, safe(allowHosts), safe(denyHosts), safe(allowCidrs), safe(denyCidrs),
                safe(allowCidrsV6), safe(denyCidrsV6), virtualHostname, virtualInterfaceName,
                virtualIpv4 == null ? "" : virtualIpv4, virtualIpv6 == null ? "" : virtualIpv6,
                proxyHost == null ? "" : proxyHost, proxyPort, cleartextPermitted);
    }

    public static String mapPath(String guestPath) {
        if (!AVAILABLE) return guestPath;
        return nativeMapPath(guestPath);
    }

    public static boolean allowHost(String host) {
        return !AVAILABLE || nativeAllowHost(host == null ? "" : host);
    }

    public static boolean allowIpv4(String address) {
        return !AVAILABLE || nativeAllowIpv4(address == null ? "" : address);
    }

    public static boolean allowIpv6(String address) {
        return !AVAILABLE || nativeAllowIpv6(address == null ? "" : address);
    }

    public static boolean configureAudioCapture(String sessionId, long generation, boolean allowed) {
        return AVAILABLE && nativeConfigureAudioCapture(sessionId, generation, allowed);
    }

    public static boolean setAudioCaptureAllowed(long generation, boolean allowed) {
        return AVAILABLE && nativeSetAudioCaptureAllowed(generation, allowed);
    }

    public static long beginAudioCapture(String api) {
        return AVAILABLE ? nativeBeginAudioCapture(api == null ? "" : api) : 0L;
    }

    public static boolean endAudioCapture(long token) { return AVAILABLE && nativeEndAudioCapture(token); }
    public static String audioCaptureStatus() { return AVAILABLE ? nativeAudioCaptureStatus() : "unavailable:" + loadError; }
    public static void resetAudioCapture() { if (AVAILABLE) nativeResetAudioCapture(); }

    public static boolean installHooks(String guestLibraryRoot) {
        if (!AVAILABLE) return false;
        if (guestLibraryRoot == null || guestLibraryRoot.trim().isEmpty()) return false;
        return nativeInstallHooks(guestLibraryRoot);
    }

    public static boolean refreshHooks() { return AVAILABLE && nativeRefreshHooks(); }
    public static String hookStatus() { return AVAILABLE ? nativeHookStatus() : "unavailable:" + loadError; }
    public static void resetHooks() { if (AVAILABLE) nativeResetHooks(); }
    public static void resetPolicy() { if (AVAILABLE) nativeResetPolicy(); }
    public static boolean installCrashRecorder(String outputPath) {
        return AVAILABLE && outputPath != null && !outputPath.trim().isEmpty()
                && nativeInstallCrashRecorder(outputPath);
    }
    public static String crashStatus() { return AVAILABLE ? nativeCrashStatus() : "unavailable:" + loadError; }
    public static void resetCrashRecorder() { if (AVAILABLE) nativeResetCrashRecorder(); }

    private static String[] safe(String[] values) { return values == null ? new String[0] : values.clone(); }
    private static native boolean nativeConfigure(String sessionId, long generation, String packageName,
                                                  String processName, int virtualUserId, int virtualUid,
                                                  int virtualPid, String abiName, String instanceRoot, String apkPath,
                                                  String nativeLibraryRoot, boolean defaultNetworkAllow,
                                                  String[] allowHosts, String[] denyHosts,
                                                  String[] allowCidrs, String[] denyCidrs,
                                                  String[] allowCidrsV6, String[] denyCidrsV6,
                                                  String virtualHostname, String virtualInterfaceName,
                                                  String virtualIpv4, String virtualIpv6,
                                                  String proxyHost, int proxyPort, boolean cleartextPermitted);
    private static native String nativeMapPath(String guestPath);
    private static native boolean nativeAllowHost(String host);
    private static native boolean nativeAllowIpv4(String address);
    private static native boolean nativeAllowIpv6(String address);
    private static native boolean nativeConfigureAudioCapture(String sessionId, long generation, boolean allowed);
    private static native boolean nativeSetAudioCaptureAllowed(long generation, boolean allowed);
    private static native long nativeBeginAudioCapture(String api);
    private static native boolean nativeEndAudioCapture(long token);
    private static native String nativeAudioCaptureStatus();
    private static native void nativeResetAudioCapture();
    private static native boolean nativeInstallHooks(String guestLibraryRoot);
    private static native boolean nativeRefreshHooks();
    private static native String nativeHookStatus();
    private static native void nativeResetHooks();
    private static native void nativeResetPolicy();
    private static native boolean nativeInstallCrashRecorder(String outputPath);
    private static native String nativeCrashStatus();
    private static native void nativeResetCrashRecorder();
}
