package com.warden.controlledsandbox.framework.core;

import android.content.pm.ApplicationInfo;
import com.warden.controlledsandbox.contract.*;
import com.warden.controlledsandbox.framework.capability.CapabilityLeaseRegistry;
import com.warden.controlledsandbox.framework.identity.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-side MediaSession/Router/Audio/SMS/Backup/DropBox behavior tests. */
public final class MediaCommunicationVirtualizationSelfTest {
    public static void main(String[] args) {
        GuestIdentity identity = identity("STATIC");
        MediaSessionApi sessions = proxy(MediaSessionApi.class, new MediaSessionDelegate(), identity, "mediasession");
        Object session = new Object();
        Object secondSession = new Object();
        Object replacementSession = new Object();
        sessions.createSession(session, "guest.pkg");
        sessions.createSession(secondSession, "guest.pkg");
        boolean sessionQuota = false;
        try { sessions.createSession(replacementSession, "guest.pkg"); }
        catch (IllegalStateException expected) { sessionQuota = expected.getMessage().contains("LIMIT"); }
        require(sessionQuota, "media session quota enforced");
        sessions.releaseSession(session);
        sessions.createSession(replacementSession, "guest.pkg");
        require(sessions.isGlobalPriorityActive(), "media session state projected");
        sessions.dispatchMediaKeyEvent("guest.pkg");
        sessions.releaseSession(secondSession);
        sessions.releaseSession(replacementSession);

        MediaRouterApi router = proxy(MediaRouterApi.class, new MediaRouterDelegate(), identity, "mediarouter");
        Object routerClient = new Object();
        Object secondRouterClient = new Object();
        Object replacementRouterClient = new Object();
        router.registerClientAsUser(routerClient, "guest.pkg", 0);
        router.registerClientAsUser(secondRouterClient, "guest.pkg", 0);
        boolean routerQuota = false;
        try { router.registerClientAsUser(replacementRouterClient, "guest.pkg", 0); }
        catch (IllegalStateException expected) { routerQuota = expected.getMessage().contains("LIMIT"); }
        require(routerQuota, "media router quota enforced");
        router.unregisterClient(routerClient);
        router.registerClientAsUser(replacementRouterClient, "guest.pkg", 0);
        router.setSelectedRoute(replacementRouterClient, "speaker", true);
        router.unregisterClient(secondRouterClient);
        router.unregisterClient(replacementRouterClient);

        AudioApi audio = proxy(AudioApi.class, new AudioDelegate(), identity, "audio");
        require(audio.getMode() == 3 && audio.getStreamVolume(3) == 7
                && audio.isSpeakerphoneOn(), "audio route state projected");
        Object focus = new Object();
        Object secondFocus = new Object();
        Object replacementFocus = new Object();
        require(audio.requestAudioFocus(focus, "guest.pkg") == 1, "audio focus granted virtually");
        audio.requestAudioFocus(secondFocus, "guest.pkg");
        boolean focusQuota = false;
        try { audio.requestAudioFocus(replacementFocus, "guest.pkg"); }
        catch (IllegalStateException expected) { focusQuota = expected.getMessage().contains("LIMIT"); }
        require(focusQuota, "audio focus quota enforced");
        audio.abandonAudioFocus(focus, "guest.pkg");
        audio.requestAudioFocus(replacementFocus, "guest.pkg");
        audio.abandonAudioFocus(secondFocus, "guest.pkg");
        audio.abandonAudioFocus(replacementFocus, "guest.pkg");
        audio.setStreamVolume(3, 8, 0, "guest.pkg");
        boolean unknownAudioDenied = false;
        try { audio.getDevices(); }
        catch (UnsupportedOperationException expected) { unknownAudioDenied = true; }
        require(unknownAudioDenied, "unknown audio route query fails closed");

        SmsApi sms = proxy(SmsApi.class, new SmsDelegate(), identity, "isms");
        sms.sendTextForSubscriber(1, "guest.pkg", "10086", "hello");
        sms.sendStoredMultipartText(1, "guest.pkg", "10086", List.of("a", "b"));
        boolean quota = false;
        try { sms.sendTextForSubscriber(1, "guest.pkg", "10086", "third"); }
        catch (IllegalStateException expected) { quota = expected.getMessage().contains("QUOTA"); }
        require(quota, "SMS quota enforced");

        BackupApi backup = proxy(BackupApi.class, new BackupDelegate(), identity, "backup");
        require(backup.isBackupEnabled() && "local".equals(backup.getCurrentTransport()),
                "backup state projected");
        require(backup.listAllTransports().length == 1, "backup transports projected");
        require(backup.getAvailableRestoreToken("guest.pkg") == 0L,
                "restore token query fails closed deterministically");
        require(backup.backupNow() == 1, "virtual backup request acknowledged");
        boolean restoreDenied = false;
        try { backup.beginRestoreSession(); }
        catch (SecurityException expected) { restoreDenied = true; }
        require(restoreDenied, "restore denied by profile");

        DropBoxApi dropBox = proxy(DropBoxApi.class, new DropBoxDelegate(), identity, "dropbox");
        require(dropBox.isTagEnabled("crash") && !dropBox.isTagEnabled("system"),
                "DropBox tags projected");
        dropBox.addData("crash", new byte[]{1, 2, 3}, 0);
        require(dropBox.getNextEntry("crash", 0L) == null, "DropBox entries fail closed");

        GuestIdentity host = identity("HOST");
        AudioDelegate hostDelegate = new AudioDelegate();
        proxy(AudioApi.class, hostDelegate, host, "audio").getMode();
        require(hostDelegate.calls == 1, "HOST audio passes through");
        System.out.println("PASS M5-T14 media-communication virtualization self-test");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, GuestIdentity identity, String service) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new SystemServiceInvocationHandler(delegate, identity, service));
    }

    private static GuestIdentity identity(String mode) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "guest.pkg";
        app.uid = 12001;
        VirtualDeviceServiceProfileSnapshot device = new VirtualDeviceServiceProfileSnapshot(1L, 0L,
                new VirtualLocationProfileSnapshot("BLOCKED", "", false, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, ""),
                new VirtualDeviceIdentitySnapshot("STATIC", "0123456789abcdef", "serial",
                        "11111111-2222-3333-4444-555555555555", true, "install", "b", "m", "model", "d", "p", "fp", "board", "hw"),
                new VirtualTelephonyProfileSnapshot("BLOCKED", -1, -1, false, false, false, List.of()),
                new VirtualWifiProfileSnapshot("BLOCKED", false, "", "", "", 0, -1, 0, -127, 0, false, false, List.of()),
                new VirtualBluetoothProfileSnapshot("BLOCKED", false, 10, "", "", false, List.of(), List.of()),
                new VirtualSensorProfileSnapshot("BLOCKED", 1, List.of()));
        VirtualMediaCommunicationProfileSnapshot media = new VirtualMediaCommunicationProfileSnapshot(1L, 0L,
                new VirtualMediaSessionProfileSnapshot(mode, true, true, true, 2, "PLAYING", 42L, "Track", "Artist"),
                new VirtualMediaRouterProfileSnapshot(mode, "speaker", "Speaker", 2, 7, 15, true, 2),
                new VirtualAudioRoutingProfileSnapshot(mode, 3, 2, true, false, false, 7, 15, true, true, 2),
                new VirtualMessagingProfileSnapshot(mode, 1, "guest.pkg", true, true, true, 2, 60000L, true),
                new VirtualBackupProfileSnapshot(mode, true, true, "local", List.of("local"), true, true, false),
                new VirtualDropBoxProfileSnapshot(mode, List.of("crash"), true, false, 8, 4096));
        return new GuestIdentity("guest.pkg", 12001, app, Set.of(), "host.pkg", 10001,
                new VirtualPackageMetadata("guest.pkg", "", app, List.of()), "guest.pkg", 0, 1,
                new VirtualPermissionPolicy(Set.of(), Map.of()), new SandboxAppOpsPolicy(Map.of()), event -> { },
                new CapabilityLeaseRegistry(),
                new VirtualSystemServiceState(device, null, null, null, null, null, media), "rev");
    }

    interface MediaSessionApi {
        void createSession(Object callback, String packageName);
        void releaseSession(Object callback);
        boolean isGlobalPriorityActive();
        void dispatchMediaKeyEvent(String packageName);
    }
    interface MediaRouterApi {
        void registerClientAsUser(Object client, String packageName, int userId);
        void setSelectedRoute(Object client, String routeId, boolean explicit);
        void unregisterClient(Object client);
    }
    interface AudioApi {
        int getMode();
        int getStreamVolume(int stream);
        boolean isSpeakerphoneOn();
        int requestAudioFocus(Object client, String packageName);
        int abandonAudioFocus(Object client, String packageName);
        void setStreamVolume(int stream, int volume, int flags, String packageName);
        Object[] getDevices();
    }
    interface SmsApi {
        void sendTextForSubscriber(int subscriptionId, String packageName, String destination, String text);
        void sendStoredMultipartText(int subscriptionId, String packageName, String destination, List<String> parts);
    }
    interface BackupApi {
        boolean isBackupEnabled();
        String getCurrentTransport();
        String[] listAllTransports();
        long getAvailableRestoreToken(String packageName);
        int backupNow();
        Object beginRestoreSession();
    }
    interface DropBoxApi {
        boolean isTagEnabled(String tag);
        void addData(String tag, byte[] data, int flags);
        Object getNextEntry(String tag, long timeMillis);
    }

    static final class MediaSessionDelegate implements MediaSessionApi {
        public void createSession(Object callback, String packageName) { throw new AssertionError("delegate"); }
        public void releaseSession(Object callback) { throw new AssertionError("delegate"); }
        public boolean isGlobalPriorityActive() { return false; }
        public void dispatchMediaKeyEvent(String packageName) { throw new AssertionError("delegate"); }
    }
    static final class MediaRouterDelegate implements MediaRouterApi {
        public void registerClientAsUser(Object client, String packageName, int userId) { throw new AssertionError("delegate"); }
        public void setSelectedRoute(Object client, String routeId, boolean explicit) { throw new AssertionError("delegate"); }
        public void unregisterClient(Object client) { throw new AssertionError("delegate"); }
    }
    static final class AudioDelegate implements AudioApi {
        int calls;
        public int getMode() { calls++; return 0; }
        public int getStreamVolume(int stream) { calls++; return 0; }
        public boolean isSpeakerphoneOn() { calls++; return false; }
        public int requestAudioFocus(Object client, String packageName) { calls++; return 0; }
        public int abandonAudioFocus(Object client, String packageName) { calls++; return 0; }
        public void setStreamVolume(int stream, int volume, int flags, String packageName) { calls++; }
        public Object[] getDevices() { calls++; return new Object[]{new Object()}; }
    }
    static final class SmsDelegate implements SmsApi {
        public void sendTextForSubscriber(int subscriptionId, String packageName, String destination, String text) { throw new AssertionError("delegate"); }
        public void sendStoredMultipartText(int subscriptionId, String packageName, String destination, List<String> parts) { throw new AssertionError("delegate"); }
    }
    static final class BackupDelegate implements BackupApi {
        public boolean isBackupEnabled() { return false; }
        public String getCurrentTransport() { return "host"; }
        public String[] listAllTransports() { return new String[]{"host"}; }
        public long getAvailableRestoreToken(String packageName) { return 99L; }
        public int backupNow() { return 0; }
        public Object beginRestoreSession() { return new Object(); }
    }
    static final class DropBoxDelegate implements DropBoxApi {
        public boolean isTagEnabled(String tag) { return false; }
        public void addData(String tag, byte[] data, int flags) { throw new AssertionError("delegate"); }
        public Object getNextEntry(String tag, long timeMillis) { return new Object(); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
