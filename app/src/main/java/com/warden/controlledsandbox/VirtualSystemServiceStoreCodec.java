package com.warden.controlledsandbox;

import static com.warden.controlledsandbox.VirtualSystemServiceStore.*;

import com.warden.controlledsandbox.contract.VirtualAlarmSnapshot;
import com.warden.controlledsandbox.contract.VirtualJobSnapshot;
import com.warden.controlledsandbox.contract.VirtualNotificationSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** JSON schema codec separated from the runtime behavior owner. */
final class VirtualSystemServiceStoreCodec {
    record Decoded(
            Map<Scope, ScopeState> states,
            int nextNotificationHostId,
            int nextJobHostId,
            long nextPendingIntentToken) { }

    private VirtualSystemServiceStoreCodec() { }

    static Decoded decode(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            int schema = root.optInt("schemaVersion", -1);
            if (schema < 1 || schema > SCHEMA) {
                throw new IllegalStateException("Unsupported virtual service schema");
            }
            int nextNotificationHostId = root.optInt("nextNotificationHostId", 0x51000000);
            int nextJobHostId = root.optInt("nextJobHostId", 0x52000000);
            long nextPendingIntentToken = Math.max(1L, root.optLong("nextPendingIntentToken", 1L));
            Map<Scope, ScopeState> states = new LinkedHashMap<>();
            JSONArray scopes = root.optJSONArray("scopes");
            if (scopes == null) {
                return new Decoded(states, nextNotificationHostId, nextJobHostId, nextPendingIntentToken);
            }
            requireArrayLimit(scopes, MAX_SCOPES, "scope");
            for (int i = 0; i < scopes.length(); i++) {
                JSONObject item = scopes.getJSONObject(i);
                Scope scope = new Scope(item.getString("packageName"), item.getInt("virtualUserId"));
                if (states.containsKey(scope)) throw new IllegalStateException("Duplicate virtual service scope");
                ScopeState state = new ScopeState();
                state.clipboard = decodeBytes(item.optString("clipboard", ""));

                JSONArray accounts = item.optJSONArray("accounts");
                requireArrayLimit(accounts, MAX_ACCOUNTS_PER_SCOPE, "account");
                if (accounts != null) for (int j = 0; j < accounts.length(); j++) {
                    JSONObject account = accounts.getJSONObject(j);
                    AccountKey key = accountKey(account.getString("name"), account.getString("type"));
                    if (state.accounts.containsKey(key)) throw new IllegalStateException("Duplicate virtual account");
                    AccountRecord record = new AccountRecord(account.optString("password", ""));
                    JSONObject tokens = account.optJSONObject("tokens");
                    if (tokens != null) {
                        if (tokens.keySet().size() > MAX_TOKENS_PER_ACCOUNT) {
                            throw new IllegalStateException("Virtual account token limit exceeded");
                        }
                        for (String tokenType : tokens.keySet()) {
                            record.tokens.put(required(tokenType, "tokenType"),
                                    tokens.optString(tokenType, ""));
                        }
                    }
                    state.accounts.put(key, record);
                }

                if (schema >= 3) {
                    JSONArray pending = item.optJSONArray("pendingIntents");
                    requireArrayLimit(pending, MAX_PENDING_INTENTS_PER_SCOPE, "PendingIntent");
                    if (pending != null) for (int j = 0; j < pending.length(); j++) {
                        JSONObject value = pending.getJSONObject(j);
                        PendingIntentRecord record = new PendingIntentRecord(value.getString("tokenId"),
                                value.getString("kind"), value.getInt("requestCode"),
                                value.optString("action", ""), value.optString("component", ""),
                                value.optString("data", ""), value.optString("filterIdentity",
                                        "a=" + value.optString("action", "") + "|c="
                                                + value.optString("component", "") + "|d="
                                                + value.optString("data", "")), value.optInt("flags", 0),
                                value.getString("creatorPackage"), value.getInt("creatorUid"),
                                value.optString("requiredPermission", ""),
                                value.optString("ownerProcessName", scope.packageName()),
                                value.optLong("ownerGeneration", 0L), value.getString("packageRevision"),
                                decodeBytes(value.optString("payload", "")), value.optInt("sends", 0),
                                value.optBoolean("cancelled", false), value.optLong("updatedAtMs", 0L));
                        if (!record.cancelled) putUnique(state.pendingIntents, record.tokenId, record,
                                "PendingIntent");
                    }
                }

                JSONArray alarms = item.optJSONArray("alarms");
                requireArrayLimit(alarms, MAX_ALARMS_PER_SCOPE, "alarm");
                if (alarms != null) for (int j = 0; j < alarms.length(); j++) {
                    JSONObject alarm = alarms.getJSONObject(j);
                    AlarmRecord record = new AlarmRecord(alarm.getString("id"), alarm.getLong("triggerAtMs"),
                            alarm.optLong("intervalMs", 0L), alarm.optBoolean("exact", false),
                            alarm.optBoolean("allowWhileIdle", false),
                            alarm.optString("deliveryPath", VirtualAlarmSnapshot.LISTENER),
                            alarm.optString("pendingIntentTokenId", ""), decodeBytes(alarm.optString("token", "")),
                            alarm.optString("ownerProcessName", scope.packageName()),
                            alarm.optLong("ownerGeneration", 0L),
                            alarm.optString("packageRevision", "legacy-revision"),
                            alarm.optInt("deliveryCount", 0), alarm.optLong("updatedAtMs", 0L));
                    putUnique(state.alarms, record.id, record, "alarm");
                }

                JSONObject namespaces = item.optJSONObject("namespaces");
                if (namespaces != null) {
                    if (namespaces.keySet().size() > MAX_NAMESPACES_PER_SCOPE) {
                        throw new IllegalStateException("Virtual namespace limit exceeded");
                    }
                    for (String name : namespaces.keySet()) {
                        JSONObject namespace = namespaces.getJSONObject(name);
                        NamespaceState value = new NamespaceState(namespace.getInt("next"));
                        JSONArray mappings = namespace.optJSONArray("mappings");
                        requireArrayLimit(mappings, MAX_NAMESPACE_MAPPINGS, "namespace mapping");
                        if (mappings != null) for (int j = 0; j < mappings.length(); j++) {
                            JSONObject mapping = mappings.getJSONObject(j);
                            int guest = mapping.getInt("guest");
                            int host = mapping.getInt("host");
                            if (value.guestToHost.putIfAbsent(guest, host) != null
                                    || value.hostToGuest.putIfAbsent(host, guest) != null) {
                                throw new IllegalStateException("Duplicate virtual namespace mapping");
                            }
                            if ("notification".equals(name)) {
                                nextNotificationHostId = Math.max(nextNotificationHostId, host + 1);
                            }
                            if ("job".equals(name)) nextJobHostId = Math.max(nextJobHostId, host + 1);
                        }
                        state.namespaces.put(normalizeRequired(name, "namespace"), value);
                    }
                }

                if (schema >= 2) {
                    JSONArray notifications = item.optJSONArray("notifications");
                    requireArrayLimit(notifications, MAX_NOTIFICATIONS_PER_SCOPE, "notification");
                    if (notifications != null) for (int j = 0; j < notifications.length(); j++) {
                        JSONObject value = notifications.getJSONObject(j);
                        NotificationRecord record = new NotificationRecord(value.getInt("guestId"),
                                value.getInt("hostId"), value.optString("guestTag", ""),
                                value.getString("hostTag"), value.optString("channelId", ""),
                                value.optString("state", VirtualNotificationSnapshot.ACTIVE),
                                value.optString("packageRevision", "legacy-revision"),
                                value.optString("contentIntentTokenId", ""),
                                value.optString("deleteIntentTokenId", ""),
                                jsonStrings(value.optJSONArray("actionIntentTokenIds")),
                                value.optBoolean("foregroundService", false),
                                value.optString("foregroundServiceKey", ""),
                                decodeBytes(value.optString("payload", "")),
                                value.optLong("updatedAtMs", 0L));
                        NotificationKey key = new NotificationKey(record.guestId, record.guestTag);
                        putUnique(state.notifications, key, record, "notification");
                        nextNotificationHostId = Math.max(nextNotificationHostId, record.hostId + 1);
                    }

                    JSONArray channels = item.optJSONArray("notificationChannels");
                    requireArrayLimit(channels, MAX_NOTIFICATION_CHANNELS_PER_SCOPE, "notification channel");
                    if (channels != null) for (int j = 0; j < channels.length(); j++) {
                        JSONObject value = channels.getJSONObject(j);
                        NotificationChannelRecord record = new NotificationChannelRecord(value.getString("kind"),
                                value.getString("id"), value.optString("groupId", ""),
                                value.optString("packageRevision", "legacy-revision"),
                                decodeBytes(value.optString("payload", "")), value.optLong("updatedAtMs", 0L));
                        putUnique(state.notificationChannels, channelKey(record.kind, record.id), record,
                                "notification channel");
                    }

                    JSONArray jobs = item.optJSONArray("jobs");
                    requireArrayLimit(jobs, MAX_JOBS_PER_SCOPE, "job");
                    if (jobs != null) for (int j = 0; j < jobs.length(); j++) {
                        JSONObject value = jobs.getJSONObject(j);
                        JobRecord record = new JobRecord(value.getInt("guestId"), value.getInt("hostId"),
                                value.optString("state", VirtualJobSnapshot.SCHEDULED),
                                value.optString("ownerProcessName", scope.packageName()),
                                value.optLong("ownerGeneration", 0L),
                                value.optString("packageRevision", "legacy-revision"),
                                value.optInt("requiredNetworkType", VirtualJobSnapshot.NETWORK_NONE),
                                value.optBoolean("requiresCharging", false),
                                value.optBoolean("requiresBatteryNotLow", false),
                                value.optBoolean("requiresStorageNotLow", false),
                                value.optBoolean("requiresDeviceIdle", false),
                                value.optBoolean("periodic", false), value.optLong("intervalMs", 0L),
                                value.optLong("flexMs", 0L), value.optLong("minimumLatencyMs", 0L),
                                value.optLong("overrideDeadlineMs", 0L), value.optBoolean("expedited", false),
                                value.optBoolean("persisted", false),
                                value.optInt("backoffPolicy", VirtualJobSnapshot.BACKOFF_EXPONENTIAL),
                                value.optLong("initialBackoffMs", 30_000L), value.optInt("failureCount", 0),
                                value.optLong("nextRunAtMs", 0L), value.optLong("lastFailureAtMs", 0L),
                                decodeBytes(value.optString("payload", "")), value.optLong("updatedAtMs", 0L));
                        putUnique(state.jobs, record.guestId, record, "job");
                        nextJobHostId = Math.max(nextJobHostId, record.hostId + 1);
                    }
                }
                states.put(scope, state);
            }
            return new Decoded(states, nextNotificationHostId, nextJobHostId, nextPendingIntentToken);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot decode virtual system-service store", error);
        }
    }

    static String encode(Map<Scope, ScopeState> states, int nextNotificationHostId,
                         int nextJobHostId, long nextPendingIntentToken) {
        try {
            if (states.size() > MAX_SCOPES) throw new IllegalStateException("Virtual scope limit exceeded");
            JSONObject root = new JSONObject().put("schemaVersion", SCHEMA)
                    .put("nextNotificationHostId", nextNotificationHostId)
                    .put("nextJobHostId", nextJobHostId)
                    .put("nextPendingIntentToken", nextPendingIntentToken);
            JSONArray scopes = new JSONArray();
            List<Scope> keys = new ArrayList<>(states.keySet());
            keys.sort(Comparator.comparing(Scope::packageName).thenComparingInt(Scope::virtualUserId));
            for (Scope scope : keys) {
                ScopeState state = states.get(scope);
                validateStateBounds(state);
                JSONObject item = new JSONObject().put("packageName", scope.packageName())
                        .put("virtualUserId", scope.virtualUserId()).put("clipboard", encodeBytes(state.clipboard));
                JSONArray accounts = new JSONArray();
                for (Map.Entry<AccountKey, AccountRecord> account : state.accounts.entrySet()) {
                    JSONObject tokens = new JSONObject();
                    for (Map.Entry<String, String> token : account.getValue().tokens.entrySet()) {
                        tokens.put(token.getKey(), token.getValue());
                    }
                    accounts.put(new JSONObject().put("name", account.getKey().name())
                            .put("type", account.getKey().type()).put("password", account.getValue().password)
                            .put("tokens", tokens));
                }
                item.put("accounts", accounts);
                JSONArray pending = new JSONArray();
                for (PendingIntentRecord value : state.pendingIntents.values()) pending.put(new JSONObject()
                        .put("tokenId", value.tokenId).put("kind", value.kind).put("requestCode", value.requestCode)
                        .put("action", value.action).put("component", value.component).put("data", value.data)
                        .put("filterIdentity", value.filterIdentity).put("flags", value.flags)
                        .put("creatorPackage", value.creatorPackage).put("creatorUid", value.creatorUid)
                        .put("requiredPermission", value.requiredPermission)
                        .put("ownerProcessName", value.ownerProcessName).put("ownerGeneration", value.ownerGeneration)
                        .put("packageRevision", value.packageRevision).put("payload", encodeBytes(value.payload))
                        .put("sends", value.sends).put("cancelled", value.cancelled)
                        .put("updatedAtMs", value.updatedAtMs));
                item.put("pendingIntents", pending);
                JSONArray alarms = new JSONArray();
                for (AlarmRecord alarm : state.alarms.values()) alarms.put(new JSONObject().put("id", alarm.id)
                        .put("triggerAtMs", alarm.triggerAtMs).put("intervalMs", alarm.intervalMs)
                        .put("exact", alarm.exact).put("allowWhileIdle", alarm.allowWhileIdle)
                        .put("deliveryPath", alarm.deliveryPath)
                        .put("pendingIntentTokenId", alarm.pendingIntentTokenId)
                        .put("token", encodeBytes(alarm.tokenPayload)).put("ownerProcessName", alarm.ownerProcessName)
                        .put("ownerGeneration", alarm.ownerGeneration).put("packageRevision", alarm.packageRevision)
                        .put("deliveryCount", alarm.deliveryCount).put("updatedAtMs", alarm.updatedAtMs));
                item.put("alarms", alarms);
                JSONObject namespaces = new JSONObject();
                for (Map.Entry<String, NamespaceState> namespace : state.namespaces.entrySet()) {
                    JSONArray mappings = new JSONArray();
                    for (Map.Entry<Integer, Integer> mapping : namespace.getValue().guestToHost.entrySet()) {
                        mappings.put(new JSONObject().put("guest", mapping.getKey()).put("host", mapping.getValue()));
                    }
                    namespaces.put(namespace.getKey(), new JSONObject().put("next", namespace.getValue().next)
                            .put("mappings", mappings));
                }
                item.put("namespaces", namespaces);
                JSONArray notifications = new JSONArray();
                for (NotificationRecord value : state.notifications.values()) notifications.put(new JSONObject()
                        .put("guestId", value.guestId).put("hostId", value.hostId).put("guestTag", value.guestTag)
                        .put("hostTag", value.hostTag).put("channelId", value.channelId).put("state", value.state)
                        .put("packageRevision", value.packageRevision)
                        .put("contentIntentTokenId", value.contentIntentTokenId)
                        .put("deleteIntentTokenId", value.deleteIntentTokenId)
                        .put("actionIntentTokenIds", new JSONArray(value.actionIntentTokenIds))
                        .put("foregroundService", value.foregroundService)
                        .put("foregroundServiceKey", value.foregroundServiceKey)
                        .put("payload", encodeBytes(value.payload)).put("updatedAtMs", value.updatedAtMs));
                item.put("notifications", notifications);
                JSONArray channels = new JSONArray();
                for (NotificationChannelRecord value : state.notificationChannels.values()) channels.put(new JSONObject()
                        .put("kind", value.kind).put("id", value.id).put("groupId", value.groupId)
                        .put("packageRevision", value.packageRevision)
                        .put("payload", encodeBytes(value.payload)).put("updatedAtMs", value.updatedAtMs));
                item.put("notificationChannels", channels);
                JSONArray jobs = new JSONArray();
                for (JobRecord value : state.jobs.values()) jobs.put(new JSONObject()
                        .put("guestId", value.guestId).put("hostId", value.hostId).put("state", value.state)
                        .put("ownerProcessName", value.ownerProcessName).put("ownerGeneration", value.ownerGeneration)
                        .put("packageRevision", value.packageRevision)
                        .put("requiredNetworkType", value.requiredNetworkType)
                        .put("requiresCharging", value.requiresCharging)
                        .put("requiresBatteryNotLow", value.requiresBatteryNotLow)
                        .put("requiresStorageNotLow", value.requiresStorageNotLow)
                        .put("requiresDeviceIdle", value.requiresDeviceIdle)
                        .put("periodic", value.periodic).put("intervalMs", value.intervalMs).put("flexMs", value.flexMs)
                        .put("minimumLatencyMs", value.minimumLatencyMs)
                        .put("overrideDeadlineMs", value.overrideDeadlineMs)
                        .put("expedited", value.expedited).put("persisted", value.persisted)
                        .put("backoffPolicy", value.backoffPolicy).put("initialBackoffMs", value.initialBackoffMs)
                        .put("failureCount", value.failureCount).put("nextRunAtMs", value.nextRunAtMs)
                        .put("lastFailureAtMs", value.lastFailureAtMs)
                        .put("payload", encodeBytes(value.payload)).put("updatedAtMs", value.updatedAtMs));
                item.put("jobs", jobs);
                scopes.put(item);
            }
            root.put("scopes", scopes);
            return root.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode virtual system-service store", error);
        }
    }

    private static void validateStateBounds(ScopeState state) {
        if (state.accounts.size() > MAX_ACCOUNTS_PER_SCOPE
                || state.pendingIntents.size() > MAX_PENDING_INTENTS_PER_SCOPE
                || state.alarms.size() > MAX_ALARMS_PER_SCOPE
                || state.namespaces.size() > MAX_NAMESPACES_PER_SCOPE
                || state.notifications.size() > MAX_NOTIFICATIONS_PER_SCOPE
                || state.notificationChannels.size() > MAX_NOTIFICATION_CHANNELS_PER_SCOPE
                || state.jobs.size() > MAX_JOBS_PER_SCOPE) {
            throw new IllegalStateException("Virtual system-service state limit exceeded");
        }
        for (AccountRecord account : state.accounts.values()) {
            if (account.tokens.size() > MAX_TOKENS_PER_ACCOUNT) {
                throw new IllegalStateException("Virtual account token limit exceeded");
            }
        }
        for (NamespaceState namespace : state.namespaces.values()) {
            if (namespace.guestToHost.size() > MAX_NAMESPACE_MAPPINGS
                    || namespace.hostToGuest.size() > MAX_NAMESPACE_MAPPINGS) {
                throw new IllegalStateException("Virtual namespace mapping limit exceeded");
            }
        }
    }

    private static void requireArrayLimit(JSONArray values, int maximum, String name) {
        if (values != null && values.length() > maximum) {
            throw new IllegalStateException("Virtual " + name + " limit exceeded");
        }
    }

    private static <K, V> void putUnique(Map<K, V> target, K key, V value, String name) {
        if (target.putIfAbsent(key, value) != null) {
            throw new IllegalStateException("Duplicate virtual " + name);
        }
    }

    private static List<String> jsonStrings(JSONArray values) {
        if (values == null) return List.of();
        if (values.length() > 32) throw new IllegalArgumentException("notificationActionTokens limit exceeded");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) out.add(values.optString(i, ""));
        return boundedTokenIds(out, "notificationActionTokens");
    }

    private static String encodeBytes(byte[] value) {
        byte[] bounded = boundedPayload(value, "storePayload");
        return Base64.getEncoder().encodeToString(bounded);
    }

    private static byte[] decodeBytes(String value) {
        if (value == null || value.isEmpty()) return new byte[0];
        if (value.length() > (MAX_PAYLOAD_BYTES * 4L / 3L) + 16L) {
            throw new IllegalArgumentException("Encoded payload too large");
        }
        return boundedPayload(Base64.getDecoder().decode(value.getBytes(StandardCharsets.UTF_8)), "storePayload");
    }
}
