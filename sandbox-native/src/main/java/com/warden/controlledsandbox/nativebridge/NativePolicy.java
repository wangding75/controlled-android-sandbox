package com.warden.controlledsandbox.nativebridge;

import android.app.Activity;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

/** JNI boundary for the clean-room native path and network policy engine. */
public final class NativePolicy {
    private static final boolean AVAILABLE;
    private static volatile String loadError = "";

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("controlled_sandbox_native");
            loaded = true;
        } catch (Throwable primary) {
            try {
                System.loadLibrary("controlled_sandbox_native32");
                loaded = true;
            } catch (Throwable fallback) {
                loadError = primary.getClass().getName() + ":" + String.valueOf(primary.getMessage())
                        + ";fallback=" + fallback.getClass().getName() + ":"
                        + String.valueOf(fallback.getMessage());
            }
        }
        AVAILABLE = loaded;
    }

    private NativePolicy() { }

    public static boolean available() { return AVAILABLE; }
    public static String loadError() { return loadError; }

    /**
     * Installs the process-local, bounded framework reflection bridge used by Guest framework
     * compatibility. This does not touch Settings.Global or any device-wide policy.
     */
    public static boolean installHiddenApiBridge() {
        return AVAILABLE && nativeInstallHiddenApiBridge();
    }

    /**
     * Clears the framework ActivityClientRecord.window for one detached Activity instance so
     * ActivityThread can perform its normal addView path on the next resume.
     */
    public static boolean clearDetachedActivityRecord(Activity activity) {
        if (!AVAILABLE) throw new IllegalStateException("NATIVE_POLICY_UNAVAILABLE");
        if (activity == null) throw new IllegalArgumentException("activity is required");
        return nativeClearDetachedActivityRecord(activity);
    }

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
                networkIdentity.cleartextPermitted(), networkIdentity.networkId(),
                networkIdentity.transport(), networkIdentity.vpnActive(),
                networkIdentity.metered(), networkIdentity.validated(), networkIdentity.mtu(),
                networkIdentity.privateDnsServerName(), networkIdentity.dnsServers());
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
                                    String proxyHost, int proxyPort, boolean cleartextPermitted,
                                    int networkId, String transport, boolean vpnActive,
                                    boolean metered, boolean validated, int mtu,
                                    String privateDnsServerName, String[] dnsServers) {
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
        if (networkId < 1) throw new IllegalArgumentException("networkId must be positive");
        if (transport == null || transport.trim().isEmpty()) throw new IllegalArgumentException("transport is required");
        if (mtu < 576 || mtu > 65535) throw new IllegalArgumentException("mtu out of range");
        return nativeConfigure(sessionId, generation, packageName, processName, virtualUserId,
                virtualUid, virtualPid, abiName, instanceRoot, apkPath,
                nativeLibraryRoot == null ? "" : nativeLibraryRoot,
                defaultNetworkAllow, safe(allowHosts), safe(denyHosts), safe(allowCidrs), safe(denyCidrs),
                safe(allowCidrsV6), safe(denyCidrsV6), virtualHostname, virtualInterfaceName,
                virtualIpv4 == null ? "" : virtualIpv4, virtualIpv6 == null ? "" : virtualIpv6,
                proxyHost == null ? "" : proxyHost, proxyPort, cleartextPermitted,
                networkId, transport, vpnActive, metered, validated, mtu,
                privateDnsServerName == null ? "" : privateDnsServerName, safe(dnsServers));
    }

    public static String mapPath(String guestPath) {
        if (!AVAILABLE) throw new IllegalStateException("NATIVE_POLICY_UNAVAILABLE");
        if (guestPath == null || guestPath.trim().isEmpty()) {
            throw new IllegalArgumentException("guestPath is required");
        }
        return nativeMapPath(guestPath);
    }

    /** Installs duplicated directory capabilities received over Binder for an isolated Guest. */
    public static boolean configureFileCapabilities(ParcelFileDescriptor dataRoot,
                                                    ParcelFileDescriptor apkParent,
                                                    String apkEntryName,
                                                    ParcelFileDescriptor nativeLibraryRoot) {
        if (!AVAILABLE) return false;
        if (dataRoot == null || apkParent == null) {
            throw new IllegalArgumentException("dataRoot and apkParent capabilities are required");
        }
        if (apkEntryName == null || apkEntryName.trim().isEmpty()) {
            throw new IllegalArgumentException("apkEntryName is required");
        }
        return nativeConfigureFileCapabilities(dataRoot.getFd(), apkParent.getFd(), apkEntryName,
                nativeLibraryRoot == null ? -1 : nativeLibraryRoot.getFd());
    }

    /** Opens a child of a transferred directory capability without resolving a /proc pathname. */
    public static ParcelFileDescriptor openCapability(ParcelFileDescriptor directory,
                                                      String entryName, boolean write) {
        if (!AVAILABLE) throw new IllegalStateException("NATIVE_POLICY_UNAVAILABLE");
        if (directory == null) throw new IllegalArgumentException("directory is required");
        int descriptor = nativeOpenCapability(directory.getFd(), entryName, write);
        return ParcelFileDescriptor.adoptFd(descriptor);
    }

    /** Copies a transferred immutable file into a process-local memfd for path-based Android APIs. */
    public static ParcelFileDescriptor materializeCapabilityFile(ParcelFileDescriptor source) {
        if (!AVAILABLE) throw new IllegalStateException("NATIVE_POLICY_UNAVAILABLE");
        if (source == null) throw new IllegalArgumentException("source is required");
        int descriptor = nativeMaterializeCapabilityFile(source.getFd());
        return ParcelFileDescriptor.adoptFd(descriptor);
    }

    /** Creates an unnamed process-local file used for FD-backed platform loader archives. */
    public static ParcelFileDescriptor createProcessLocalFile(String name) {
        if (!AVAILABLE) throw new IllegalStateException("NATIVE_POLICY_UNAVAILABLE");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("name is required");
        return ParcelFileDescriptor.adoptFd(nativeCreateProcessLocalFile(name));
    }

    public static boolean allowHost(String host) {
        // An unavailable native boundary is not an allow decision.  Returning true here made
        // callers silently fall back to host networking when the policy engine failed to load,
        // which is the opposite of the native engine's fail-closed contract.
        if (!AVAILABLE) return false;
        return nativeAllowHost(host == null ? "" : host);
    }

    public static boolean allowIpv4(String address) {
        if (!AVAILABLE) return false;
        return nativeAllowIpv4(address == null ? "" : address);
    }

    public static boolean allowIpv6(String address) {
        if (!AVAILABLE) return false;
        return nativeAllowIpv6(address == null ? "" : address);
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

    /**
     * Guest CrashSDK/splash recycle must not SIGKILL the sandbox-owned slot.
     * Only GuestProcessService teardown sets this true.
     */
    public static void setGuestProcessExitAllowed(boolean allowed) {
        if (AVAILABLE) nativeSetGuestProcessExitAllowed(allowed);
    }

    /** Observe-only wrap of Runtime.nativeLoad. Does not change which loader binds the library. */
    public static boolean installNativeLoadDiagnostic() {
        return AVAILABLE && nativeInstallNativeLoadDiagnostic();
    }

    /**
     * Installs the translated-ABI native loader boundary.  This uses ART's supported
     * RegisterNatives path and redirects absolute Guest paths before Runtime.nativeLoad reaches
     * the platform linker.  It deliberately does not mutate ArtMethod access flags, which would
     * break the ARM64-to-x86_64 native bridge calling convention.
     */
    public static boolean installNativeLoadRedirect() {
        return AVAILABLE && nativeInstallNativeLoadRedirect();
    }

    /** Observe-only: dump pending Java exceptions seen by JNI ExceptionCheck. */
    public static boolean installJniPendingExceptionProbe() {
        return AVAILABLE && nativeInstallJniPendingExceptionProbe();
    }

    /**
     * Installs the ISOLATED_HOSTILE classic BPF filter in the calling process only.
     * Must never be invoked from Host, Broker, or TRUSTED_COMPAT guests.
     */
    public static String installHostileSeccomp() {
        if (!AVAILABLE) return "NATIVE_POLICY_UNAVAILABLE:" + loadError;
        return nativeInstallHostileSeccomp();
    }

    public static String hostileSeccompDenyNames() {
        if (!AVAILABLE) return "";
        return nativeHostileSeccompDenyNames();
    }

    public static boolean installHooks(String guestLibraryRoot) {
        if (!AVAILABLE) return false;
        if (guestLibraryRoot == null || guestLibraryRoot.trim().isEmpty()) return false;
        return nativeInstallHooks(guestLibraryRoot);
    }

    /** Installs the system-library IO boundary used by isolated capability-backed workers. */
    public static boolean installSystemIoHooks() {
        return AVAILABLE && nativeInstallSystemIoHooks();
    }

    /**
     * Installs only the host runtime-library process-lifetime boundary used by translated guests.
     * Direct native termination remains deny-only; Runtime.nativeExit is forwarded as the normal
     * process boundary. This never rewrites the translated guest's foreign-ABI ELF modules.
     */
    public static boolean installProcessLifetimeHooks() {
        return AVAILABLE && nativeInstallProcessLifetimeHooks();
    }

    public static boolean refreshHooks() { return AVAILABLE && nativeRefreshHooks(); }
    public static boolean refreshProcessLifetimeHooks() {
        return AVAILABLE && nativeRefreshProcessLifetimeHooks();
    }
    public static String hookStatus() { return AVAILABLE ? nativeHookStatus() : "unavailable:" + loadError; }
    public static void resetHooks() { if (AVAILABLE) nativeResetHooks(); }
    public static boolean installCamera1Adapter() {
        return AVAILABLE && nativeInstallCamera1Adapter();
    }
    public static boolean configureCamera1Identity(String guestPackage, int virtualUid,
                                                   String hostPackage, int hostUid,
                                                   boolean virtualCamera, boolean allowOpen,
                                                   boolean replacePreview, boolean replaceCapture) {
        if (!AVAILABLE) return false;
        if (guestPackage == null || guestPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("guestPackage is required");
        }
        if (hostPackage == null || hostPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("hostPackage is required");
        }
        if (virtualUid < 0 || hostUid < 0) throw new IllegalArgumentException("uid is invalid");
        return nativeConfigureCamera1Identity(guestPackage, virtualUid, hostPackage, hostUid,
                virtualCamera, allowOpen, replacePreview, replaceCapture);
    }
    public static boolean configureCamera1Frames(String sourceKind, String sourceSha256,
                                                 int width, int height, byte[][] previewFrames,
                                                 byte[][] captureFrames) {
        if (!AVAILABLE) return false;
        if (sourceKind == null || sourceKind.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceKind is required");
        }
        if (sourceSha256 == null || sourceSha256.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceSha256 is required");
        }
        if (width < 0 || height < 0) throw new IllegalArgumentException("dimensions are invalid");
        if (previewFrames == null || captureFrames == null) {
            throw new IllegalArgumentException("camera frames are required");
        }
        return nativeConfigureCamera1Frames(sourceKind, sourceSha256, width, height,
                previewFrames, captureFrames);
    }
    public static String camera1Status() {
        return AVAILABLE ? nativeCamera1Status() : "unavailable:" + loadError;
    }
    public static void resetCamera1() { if (AVAILABLE) nativeResetCamera1(); }
    public static void resetPolicy() { if (AVAILABLE) nativeResetPolicy(); }
    public static boolean installCrashRecorder(String outputPath) {
        return AVAILABLE && outputPath != null && !outputPath.trim().isEmpty()
                && nativeInstallCrashRecorder(outputPath);
    }
    public static String crashStatus() { return AVAILABLE ? nativeCrashStatus() : "unavailable:" + loadError; }
    public static String networkStatus() { return AVAILABLE ? nativeNetworkStatus() : "unavailable:" + loadError; }
    public static String loaderStatus() { return AVAILABLE ? nativeLoaderStatus() : "unavailable:" + loadError; }
    public static void resetCrashRecorder() { if (AVAILABLE) nativeResetCrashRecorder(); }

    /**
     * Queues one JPEG transport buffer to a guest-owned Surface.  This is a generic camera
     * buffer boundary; callers must only pass a Surface obtained from the Guest API and a
     * sandbox-owned byte array.  A negative result is a truthful adapter failure.
     */
    public static int queueJpeg(Surface surface, byte[] jpeg) {
        if (!AVAILABLE || surface == null || jpeg == null || jpeg.length == 0) return -1;
        return nativeQueueJpeg(surface, jpeg);
    }

    private static String[] safe(String[] values) { return values == null ? new String[0] : values.clone(); }
    private static native boolean nativeInstallHiddenApiBridge();
    private static native boolean nativeClearDetachedActivityRecord(Activity activity);
    private static native boolean nativeConfigure(String sessionId, long generation, String packageName,
                                                  String processName, int virtualUserId, int virtualUid,
                                                  int virtualPid, String abiName, String instanceRoot, String apkPath,
                                                  String nativeLibraryRoot, boolean defaultNetworkAllow,
                                                  String[] allowHosts, String[] denyHosts,
                                                  String[] allowCidrs, String[] denyCidrs,
                                                  String[] allowCidrsV6, String[] denyCidrsV6,
                                                  String virtualHostname, String virtualInterfaceName,
                                                  String virtualIpv4, String virtualIpv6,
                                                  String proxyHost, int proxyPort, boolean cleartextPermitted,
                                                  int networkId, String transport, boolean vpnActive,
                                                  boolean metered, boolean validated, int mtu,
                                                  String privateDnsServerName, String[] dnsServers);
    private static native boolean nativeConfigureFileCapabilities(int dataRootFd, int apkParentFd,
                                                                   String apkEntryName,
                                                                   int nativeLibraryRootFd);
    private static native int nativeOpenCapability(int directoryFd, String entryName, boolean write);
    private static native int nativeMaterializeCapabilityFile(int sourceFd);
    private static native int nativeCreateProcessLocalFile(String name);
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
    private static native void nativeSetGuestProcessExitAllowed(boolean allowed);
    private static native boolean nativeInstallNativeLoadDiagnostic();
    private static native boolean nativeInstallNativeLoadRedirect();
    private static native boolean nativeInstallJniPendingExceptionProbe();
    private static native String nativeInstallHostileSeccomp();
    private static native String nativeHostileSeccompDenyNames();
    private static native boolean nativeInstallHooks(String guestLibraryRoot);
    private static native boolean nativeInstallSystemIoHooks();
    private static native boolean nativeInstallProcessLifetimeHooks();
    private static native boolean nativeRefreshHooks();
    private static native boolean nativeRefreshProcessLifetimeHooks();
    private static native String nativeHookStatus();
    private static native void nativeResetHooks();
    private static native boolean nativeInstallCamera1Adapter();
    private static native boolean nativeConfigureCamera1Identity(String guestPackage, int virtualUid,
                                                                 String hostPackage, int hostUid,
                                                                 boolean virtualCamera, boolean allowOpen,
                                                                 boolean replacePreview, boolean replaceCapture);
    private static native boolean nativeConfigureCamera1Frames(String sourceKind, String sourceSha256,
                                                               int width, int height,
                                                               byte[][] previewFrames,
                                                               byte[][] captureFrames);
    private static native String nativeCamera1Status();
    private static native void nativeResetCamera1();
    private static native void nativeResetPolicy();
    private static native boolean nativeInstallCrashRecorder(String outputPath);
    private static native String nativeCrashStatus();
    private static native String nativeNetworkStatus();
    private static native String nativeLoaderStatus();
    private static native void nativeResetCrashRecorder();
    private static native int nativeQueueJpeg(Surface surface, byte[] jpeg);
}
