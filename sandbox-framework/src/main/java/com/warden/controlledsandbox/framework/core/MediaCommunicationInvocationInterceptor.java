package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualAudioRoutingProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualBackupProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualDropBoxProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaCommunicationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaRouterProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMediaSessionProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualMessagingProfileSnapshot;
import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Source-side media, routing, messaging, backup and DropBox virtualization. */
final class MediaCommunicationInvocationInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final Set<Object> mediaSessions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> routerClients = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> focusOwners = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<Long> messageSends = new ArrayDeque<>();
    private final ArrayDeque<MessageRecord> sentMessages = new ArrayDeque<>();
    private final ArrayDeque<DropBoxRecord> dropBoxEntries = new ArrayDeque<>();
    private int syntheticSessionSequence;

    MediaCommunicationInvocationInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
    }

    synchronized Decision before(Method method, Object[] arguments) {
        VirtualMediaCommunicationProfileSnapshot profile;
        try {
            profile = identity.virtualServices().mediaCommunicationProfile();
        } catch (IllegalStateException unavailable) {
            if ("VIRTUAL_MEDIA_COMMUNICATION_PROFILE_AUTHORITY_REQUIRED".equals(unavailable.getMessage())
                    || "VIRTUAL_MEDIA_COMMUNICATION_PROFILE_NOT_AVAILABLE".equals(unavailable.getMessage())) {
                return Decision.passThrough();
            }
            throw unavailable;
        }
        return switch (service) {
            case "mediasession" -> mediaSession(method, arguments, profile.mediaSession());
            case "mediarouter" -> mediaRouter(method, arguments, profile.mediaRouter());
            case "audio" -> audio(method, arguments, profile.audioRouting());
            case "isms", "isms2", "ismsmsim" -> messaging(method, arguments, profile.messaging());
            case "backup" -> backup(method, arguments, profile.backup());
            case "dropbox" -> dropBox(method, arguments, profile.dropBox());
            default -> Decision.passThrough();
        };
    }

    synchronized int mediaSessionCount() { return mediaSessions.size(); }
    synchronized int routerClientCount() { return routerClients.size(); }
    synchronized int focusOwnerCount() { return focusOwners.size(); }
    synchronized int messageSendCount() { return messageSends.size(); }
    synchronized int storedMessageCount() { return sentMessages.size(); }
    synchronized int dropBoxEntryCount() { return dropBoxEntries.size(); }

    private Decision mediaSession(Method method, Object[] arguments,
            VirtualMediaSessionProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "releasesession", "destroy", "unregister")) {
            removeFirstIdentity(mediaSessions, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (cleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "createsession")) {
            if (!profile.allowSessionCreation()) throw new SecurityException("VIRTUAL_MEDIA_SESSION_CREATION_DENIED");
            Object token = firstIdentity(arguments);
            if (token == null) token = new SyntheticToken(++syntheticSessionSequence);
            addBounded(mediaSessions, token, profile.maximumSessions(), "VIRTUAL_MEDIA_SESSION_LIMIT_EXCEEDED");
            Class<?> result = method.getReturnType();
            if (result == void.class || result == Void.class) return Decision.handled(null);
            if (result == boolean.class || result == Boolean.class) return Decision.handled(true);
            // Platform session objects are version-specific; do not fabricate an invalid Binder object.
            mediaSessions.remove(token);
            throw new IllegalStateException("VIRTUAL_MEDIA_SESSION_RESULT_ADAPTER_REQUIRED:" + result.getName());
        }
        if (containsAny(name, "getactivesessions", "getsessiontokens", "getmediasessionservice2tokens")) {
            return Decision.handled(emptyCollection(method.getReturnType()));
        }
        if (containsAny(name, "isglobalpriorityactive", "istrusted")) {
            return Decision.handled(booleanValue(method.getReturnType(), profile.active()));
        }
        if (containsAny(name, "getplaybackstate")) {
            if (method.getReturnType() == String.class || method.getReturnType() == Object.class) {
                return Decision.handled(profile.playbackState());
            }
            throw new IllegalStateException("VIRTUAL_MEDIA_PLAYBACK_STATE_ADAPTER_REQUIRED:" + method.getReturnType().getName());
        }
        if (containsAny(name, "getplaybackposition")) {
            return Decision.handled(numeric(method.getReturnType(), profile.playbackPositionMs()));
        }
        if (containsAny(name, "getmetadatatitle")) return Decision.handled(stringValue(method.getReturnType(), profile.title()));
        if (containsAny(name, "getmetadataartist")) return Decision.handled(stringValue(method.getReturnType(), profile.artist()));
        if (containsAny(name, "dispatchmediakeyevent", "dispatchvolumekeyevent", "dispatchadjustvolume")) {
            if (!profile.allowTransportControls()) {
                throw new SecurityException("VIRTUAL_MEDIA_TRANSPORT_CONTROL_DENIED");
            }
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "addsessionslistener", "addsessiontokenslistener", "register")) {
            Object callback = firstIdentity(arguments);
            if (callback == null) throw new IllegalArgumentException("VIRTUAL_MEDIA_SESSION_LISTENER_REQUIRED");
            addBounded(mediaSessions, callback, profile.maximumSessions(), "VIRTUAL_MEDIA_SESSION_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("media_session", method);
    }

    private Decision mediaRouter(Method method, Object[] arguments,
            VirtualMediaRouterProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "unregisterclient", "unregisterrouter")) {
            removeFirstIdentity(routerClients, arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (cleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "registerclient", "registerrouter")) {
            Object client = firstIdentity(arguments);
            if (client == null) throw new IllegalArgumentException("VIRTUAL_MEDIA_ROUTER_CLIENT_REQUIRED");
            addBounded(routerClients, client, profile.maximumClients(), "VIRTUAL_MEDIA_ROUTER_CLIENT_LIMIT_EXCEEDED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "setselectedroute", "selectroute", "requestroute", "setdiscoveryrequest")) {
            if (!profile.allowRouteChanges()) throw new SecurityException("VIRTUAL_MEDIA_ROUTE_CHANGE_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "getselectedrouteid")) return Decision.handled(stringValue(method.getReturnType(), profile.selectedRouteId()));
        if (containsAny(name, "getselectedroutename")) return Decision.handled(stringValue(method.getReturnType(), profile.selectedRouteName()));
        if (containsAny(name, "getroutevolume")) return Decision.handled(numeric(method.getReturnType(), profile.routeVolume()));
        if (containsAny(name, "getroutevolumemax")) return Decision.handled(numeric(method.getReturnType(), profile.routeVolumeMax()));
        if (containsAny(name, "getroutetype")) return Decision.handled(numeric(method.getReturnType(), profile.routeType()));
        if (containsAny(name, "getstate", "getsystemroutes", "getroutes", "getrouters")) {
            return Decision.handled(emptyCollection(method.getReturnType()));
        }
        if (containsAny(name, "requestsetvolume", "requestupdatevolume")) {
            if (!profile.allowRouteChanges()) throw new SecurityException("VIRTUAL_MEDIA_ROUTE_VOLUME_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("media_router", method);
    }

    private Decision audio(Method method, Object[] arguments, VirtualAudioRoutingProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "abandonaudiofocus", "unregisteraudiofocusclient")) {
            removeFirstIdentity(focusOwners, arguments);
            return Decision.handled(numericSuccess(method.getReturnType()));
        }
        if (blocked(profile.mode())) {
            if (cleanup(name)) return Decision.handled(successValue(method.getReturnType()));
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "getmode")) return Decision.handled(numeric(method.getReturnType(), profile.audioMode()));
        if (containsAny(name, "getringermode")) return Decision.handled(numeric(method.getReturnType(), profile.ringerMode()));
        if (containsAny(name, "isspeakerphoneon")) return Decision.handled(booleanValue(method.getReturnType(), profile.speakerphoneOn()));
        if (containsAny(name, "isbluetoothscoon")) return Decision.handled(booleanValue(method.getReturnType(), profile.bluetoothScoOn()));
        if (containsAny(name, "ismicrophonemuted")) return Decision.handled(booleanValue(method.getReturnType(), profile.microphoneMuted()));
        if (containsAny(name, "getstreammaxvolume")) return Decision.handled(numeric(method.getReturnType(), profile.musicVolumeMax()));
        if (containsAny(name, "getstreamvolume", "getlastaudible")) return Decision.handled(numeric(method.getReturnType(), profile.musicVolume()));
        if (containsAny(name, "requestaudiofocus", "registeraudiofocusclient")) {
            if (!profile.allowAudioFocus()) throw new SecurityException("VIRTUAL_AUDIO_FOCUS_DENIED");
            Object owner = firstIdentity(arguments);
            if (owner == null) owner = new SyntheticToken(++syntheticSessionSequence);
            addBounded(focusOwners, owner, profile.maximumFocusOwners(), "VIRTUAL_AUDIO_FOCUS_LIMIT_EXCEEDED");
            return Decision.handled(numericSuccess(method.getReturnType()));
        }
        if (containsAny(name, "setstreamvolume", "adjuststreamvolume", "adjustsuggestedstreamvolume",
                "adjustvolume", "setmode", "setringermode", "setspeakerphoneon", "setbluetoothscoon",
                "startbluetoothsco", "stopbluetoothsco", "setmicrophonemute")) {
            if (!profile.allowVolumeChanges()) throw new SecurityException("VIRTUAL_AUDIO_ROUTE_MUTATION_DENIED:" + method.getName());
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "record", "capture", "audioinput", "inputclient")) {
            // Preserve the pre-existing microphone capability gate for capture-specific calls.
            return Decision.passThrough();
        }
        return unsupported("audio_routing", method);
    }

    private Decision messaging(Method method, Object[] arguments, VirtualMessagingProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        requireSmsIdentity(name, arguments, profile);
        if (blocked(profile.mode())) return Decision.handled(emptyValue(method.getReturnType()));
        if (containsAny(name, "getpreferredsmsubscription", "getdefaultsmssubscriptionid")) {
            return Decision.handled(numeric(method.getReturnType(), profile.subscriptionId()));
        }
        if (containsAny(name, "getdefaultsmspackage", "getdefaultsmsapplication")) {
            return Decision.handled(stringValue(method.getReturnType(), profile.defaultSmsPackage()));
        }
        if (containsAny(name, "getallmessagesfromiccef")) return Decision.handled(emptyCollection(method.getReturnType()));
        if (containsAny(name, "sendmultiparttext", "sendstoredmultiparttext")) {
            requireMessageAllowed(profile.allowMultipartMessages(), profile, "MULTIPART");
            recordMessage(profile, "MULTIPART", arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "senddata")) {
            requireMessageAllowed(profile.allowDataMessages(), profile, "DATA");
            recordMessage(profile, "DATA", arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "injectsms")) {
            throw new SecurityException("VIRTUAL_SMS_INJECTION_DENIED");
        }
        if (containsAny(name, "sendtext", "sendstoredtext")) {
            requireMessageAllowed(profile.allowTextMessages(), profile, "TEXT");
            recordMessage(profile, "TEXT", arguments);
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "updateMessageOnIcc", "copymessagetoiccef")) {
            throw new SecurityException("VIRTUAL_SMS_ICC_MUTATION_DENIED");
        }
        return unsupported("messaging", method);
    }

    private Decision backup(Method method, Object[] arguments, VirtualBackupProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "isbackupenabled")) return Decision.handled(booleanValue(method.getReturnType(), profile.backupEnabled()));
        if (containsAny(name, "isbackupprovisioned")) return Decision.handled(booleanValue(method.getReturnType(), profile.backupProvisioned()));
        if (containsAny(name, "getcurrenttransport")) return Decision.handled(stringValue(method.getReturnType(), profile.currentTransport()));
        if (containsAny(name, "listalltransports", "gettransportwhitelist")) {
            return Decision.handled(stringArrayOrList(method.getReturnType(), profile.transports()));
        }
        if (containsAny(name, "datachanged")) {
            if (!profile.allowDataChanged()) throw new SecurityException("VIRTUAL_BACKUP_DATA_CHANGED_DENIED");
            return Decision.handled(successValue(method.getReturnType()));
        }
        if (containsAny(name, "backupnow", "requestbackup", "fullbackup")) {
            if (!profile.allowBackupNow()) throw new SecurityException("VIRTUAL_BACKUP_EXECUTION_DENIED");
            return Decision.handled(numericSuccess(method.getReturnType()));
        }
        if (containsAny(name, "getavailablerestoretoken", "getancestralserialnumber")) {
            return Decision.handled(numeric(method.getReturnType(), 0L));
        }
        if (containsAny(name, "beginrestoresession", "requestrestore", "restoreatinstall",
                "restorepackage", "restoreall")) {
            if (!profile.allowRestore()) throw new SecurityException("VIRTUAL_RESTORE_DENIED");
            return Decision.handled(emptyValue(method.getReturnType()));
        }
        if (containsAny(name, "setbackupenabled", "setbackupprovisioned", "selectbackuptransport",
                "clearbackupdata", "setbackuppassword", "agentconnected", "agentdisconnected")) {
            throw new SecurityException("VIRTUAL_BACKUP_MUTATION_REQUIRES_PACKAGE_SERVICE:" + method.getName());
        }
        return unsupported("backup", method);
    }

    private Decision dropBox(Method method, Object[] arguments, VirtualDropBoxProfileSnapshot profile) {
        String name = normalize(method.getName());
        if (host(profile.mode())) return Decision.passThrough();
        if (containsAny(name, "istagenabled")) {
            String tag = firstString(arguments);
            return Decision.handled(booleanValue(method.getReturnType(), profile.enabledTags().contains(tag)));
        }
        if (containsAny(name, "getnextentry")) {
            if (!profile.exposeEntries() || dropBoxEntries.isEmpty()) return Decision.handled(null);
            throw new IllegalStateException("VIRTUAL_DROPBOX_ENTRY_ADAPTER_REQUIRED");
        }
        if (containsAny(name, "add", "adddata", "addfile")) {
            if (!profile.allowWrites()) throw new SecurityException("VIRTUAL_DROPBOX_WRITE_DENIED");
            String tag = firstString(arguments);
            if (!profile.enabledTags().isEmpty() && !profile.enabledTags().contains(tag)) {
                throw new SecurityException("VIRTUAL_DROPBOX_TAG_DISABLED:" + tag);
            }
            byte[] payload = firstBytes(arguments);
            int size = payload == null ? 0 : payload.length;
            if (size > profile.maximumEntryBytes()) throw new IllegalArgumentException("VIRTUAL_DROPBOX_ENTRY_SIZE_EXCEEDED");
            if (dropBoxEntries.size() >= profile.maximumEntries()) throw new IllegalStateException("VIRTUAL_DROPBOX_ENTRY_LIMIT_EXCEEDED");
            dropBoxEntries.addLast(new DropBoxRecord(tag, size, System.currentTimeMillis()));
            return Decision.handled(successValue(method.getReturnType()));
        }
        return unsupported("dropbox", method);
    }

    private void requireMessageAllowed(boolean allowed, VirtualMessagingProfileSnapshot profile, String kind) {
        if (!allowed) throw new SecurityException("VIRTUAL_SMS_" + kind + "_DENIED");
        long now = System.currentTimeMillis();
        pruneMessages(now, profile.quotaWindowMs());
        if (messageSends.size() >= profile.maximumMessagesPerWindow()) {
            throw new IllegalStateException("VIRTUAL_SMS_QUOTA_EXCEEDED");
        }
    }

    private void recordMessage(VirtualMessagingProfileSnapshot profile, String kind, Object[] arguments) {
        long now = System.currentTimeMillis();
        pruneMessages(now, profile.quotaWindowMs());
        messageSends.addLast(now);
        if (profile.storeSentMessages()) {
            sentMessages.addLast(new MessageRecord(kind, firstDestination(arguments), now));
            while (sentMessages.size() > Math.max(1, profile.maximumMessagesPerWindow())) sentMessages.removeFirst();
        }
    }

    private void requireSmsIdentity(String methodName, Object[] arguments,
            VirtualMessagingProfileSnapshot profile) {
        if (arguments == null) return;
        for (Object argument : arguments) {
            if (identity.hostPackageName().equals(argument)) {
                throw new SecurityException("VIRTUAL_SMS_HOST_PACKAGE_IDENTITY_DENIED");
            }
        }
        if (!containsAny(methodName, "subscriber", "subscription")) return;
        int subscription = firstInt(arguments);
        if (subscription == Integer.MIN_VALUE) return;
        if (profile.subscriptionId() < 0) {
            throw new SecurityException("VIRTUAL_SMS_SUBSCRIPTION_UNAVAILABLE");
        }
        if (subscription != profile.subscriptionId()) {
            throw new SecurityException("VIRTUAL_SMS_SUBSCRIPTION_MISMATCH:"
                    + subscription + " expected=" + profile.subscriptionId());
        }
        if (arguments.length > 1 && arguments[1] instanceof String packageName
                && !identity.packageName().equals(packageName)) {
            throw new SecurityException("VIRTUAL_SMS_GUEST_PACKAGE_REQUIRED");
        }
    }

    private static int firstInt(Object[] arguments) {
        for (Object argument : arguments) if (argument instanceof Integer value) return value;
        return Integer.MIN_VALUE;
    }

    private void pruneMessages(long now, long windowMs) {
        long threshold = now - windowMs;
        while (!messageSends.isEmpty() && messageSends.peekFirst() < threshold) messageSends.removeFirst();
    }

    private static boolean host(String mode) { return VirtualLocationProfileSnapshot.MODE_HOST.equals(mode); }
    private static boolean blocked(String mode) { return VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(mode); }
    private static boolean cleanup(String name) { return containsAny(name, "release", "destroy", "unregister", "remove", "cancel", "stop"); }

    private static void addBounded(Set<Object> target, Object value, int maximum, String message) {
        if (!target.contains(value) && target.size() >= maximum) throw new IllegalStateException(message);
        target.add(value);
    }

    private static Object firstIdentity(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || value instanceof String || value instanceof Number
                    || value instanceof Boolean || value.getClass().isEnum()) continue;
            return value;
        }
        return null;
    }

    private static void removeFirstIdentity(Set<Object> target, Object[] arguments) {
        Object value = firstIdentity(arguments);
        if (value != null) target.remove(value);
    }

    private static String firstString(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String) return (String) value;
        return "";
    }

    private static String firstDestination(Object[] arguments) {
        if (arguments == null) return "";
        int strings = 0;
        for (Object value : arguments) {
            if (!(value instanceof String)) continue;
            strings++;
            if (strings >= 2) return (String) value;
        }
        return firstString(arguments);
    }

    private static byte[] firstBytes(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) if (value instanceof byte[]) return (byte[]) value;
        return null;
    }

    private static Decision unsupported(String domain, Method method) {
        throw new UnsupportedOperationException("VIRTUAL_" + domain.toUpperCase(Locale.ROOT)
                + "_OPERATION_UNSUPPORTED:" + method.getName());
    }

    private static Object emptyCollection(Class<?> type) {
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) return List.of();
        return null;
    }

    private static Object stringArrayOrList(Class<?> type, List<String> values) {
        if (type == String[].class) return values.toArray(new String[0]);
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) return List.copyOf(values);
        if (type == String.class) return values.isEmpty() ? "" : values.get(0);
        return null;
    }

    private static Object stringValue(Class<?> type, String value) {
        if (type == String.class || type == Object.class) return value;
        return null;
    }

    private static Object booleanValue(Class<?> type, boolean value) {
        if (type == boolean.class || type == Boolean.class) return value;
        if (type == int.class || type == Integer.class) return value ? 1 : 0;
        if (type == long.class || type == Long.class) return value ? 1L : 0L;
        return value;
    }

    private static Object numeric(Class<?> type, long value) {
        if (type == int.class || type == Integer.class) return (int) value;
        if (type == long.class || type == Long.class) return value;
        if (type == short.class || type == Short.class) return (short) value;
        if (type == byte.class || type == Byte.class) return (byte) value;
        if (type == boolean.class || type == Boolean.class) return value != 0L;
        return value;
    }

    private static Object numericSuccess(Class<?> type) {
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == boolean.class || type == Boolean.class) return true;
        return null;
    }

    private static Object emptyValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)
                || type == Object.class) return List.of();
        return null;
    }

    private static Object successValue(Class<?> type) {
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        return null;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(normalize(fragment))) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("_", "").replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    record Decision(boolean handled, Object result) {
        static Decision passThrough() { return new Decision(false, null); }
        static Decision handled(Object result) { return new Decision(true, result); }
    }

    private record SyntheticToken(int id) { }
    private record MessageRecord(String kind, String destination, long timestampMs) { }
    private record DropBoxRecord(String tag, int sizeBytes, long timestampMs) { }
}
