package com.warden.controlledsandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable per-virtual-user permission and AppOps overrides owned by the package catalog. */
final class SandboxPolicyState {
    static final String PERMISSION_DEFAULT = "DEFAULT";
    static final String PERMISSION_GRANTED = "GRANTED";
    static final String PERMISSION_DENIED = "DENIED";
    static final String APP_OP_DEFAULT = "DEFAULT";
    static final String APP_OP_ALLOWED = "ALLOWED";
    static final String APP_OP_IGNORED = "IGNORED";
    static final String APP_OP_ERRORED = "ERRORED";

    final String packageName;
    final int virtualUserId;
    private final Map<String, String> permissionDecisions;
    private final Map<String, String> appOpModes;

    SandboxPolicyState(String packageName, int virtualUserId,
                       Map<String, String> permissionDecisions,
                       Map<String, String> appOpModes) {
        this.packageName = required(packageName, "packageName");
        if (virtualUserId < 0 || virtualUserId > 999) {
            throw new IllegalArgumentException("virtualUserId out of range: " + virtualUserId);
        }
        this.virtualUserId = virtualUserId;
        this.permissionDecisions = immutableValidated(permissionDecisions, true);
        this.appOpModes = immutableValidated(appOpModes, false);
    }

    static SandboxPolicyState empty(String packageName, int virtualUserId) {
        return new SandboxPolicyState(packageName, virtualUserId, Map.of(), Map.of());
    }

    Map<String, String> permissionDecisions() {
        return permissionDecisions;
    }

    Map<String, String> appOpModes() {
        return appOpModes;
    }

    String permissionDecision(String permission) {
        return permissionDecisions.getOrDefault(permission, PERMISSION_DEFAULT);
    }

    String appOpMode(String opName) {
        return appOpModes.getOrDefault(opName, APP_OP_DEFAULT);
    }

    SandboxPolicyState withPermissionDecision(String permission, String decision) {
        String key = stateKey(permission, "permission");
        String normalized = permissionDecisionValue(decision);
        Map<String, String> next = new LinkedHashMap<>(permissionDecisions);
        if (PERMISSION_DEFAULT.equals(normalized)) next.remove(key);
        else next.put(key, normalized);
        return new SandboxPolicyState(packageName, virtualUserId, next, appOpModes);
    }

    SandboxPolicyState withAppOpMode(String opName, String mode) {
        String key = stateKey(opName, "opName");
        String normalized = appOpModeValue(mode);
        Map<String, String> next = new LinkedHashMap<>(appOpModes);
        if (APP_OP_DEFAULT.equals(normalized)) next.remove(key);
        else next.put(key, normalized);
        return new SandboxPolicyState(packageName, virtualUserId, permissionDecisions, next);
    }

    JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("packageName", packageName);
        out.put("virtualUserId", virtualUserId);
        out.put("permissions", entries(permissionDecisions));
        out.put("appOps", entries(appOpModes));
        return out;
    }

    static SandboxPolicyState fromJson(JSONObject value) throws Exception {
        return new SandboxPolicyState(
                value.getString("packageName"),
                value.getInt("virtualUserId"),
                decodeEntries(value.optJSONArray("permissions"), true),
                decodeEntries(value.optJSONArray("appOps"), false));
    }

    static String permissionDecisionValue(String value) {
        String normalized = normalizedValue(value);
        if (!List.of(PERMISSION_DEFAULT, PERMISSION_GRANTED, PERMISSION_DENIED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported permission decision: " + value);
        }
        return normalized;
    }

    static String appOpModeValue(String value) {
        String normalized = normalizedValue(value);
        if (!List.of(APP_OP_DEFAULT, APP_OP_ALLOWED, APP_OP_IGNORED, APP_OP_ERRORED).contains(normalized)) {
            throw new IllegalArgumentException("Unsupported AppOps mode: " + value);
        }
        return normalized;
    }

    private static Map<String, String> immutableValidated(Map<String, String> source, boolean permission) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (source != null) {
            for (Map.Entry<String, String> item : source.entrySet()) {
                String key = stateKey(item.getKey(), permission ? "permission" : "opName");
                String value = permission ? permissionDecisionValue(item.getValue()) : appOpModeValue(item.getValue());
                if ((permission && !PERMISSION_DEFAULT.equals(value))
                        || (!permission && !APP_OP_DEFAULT.equals(value))) {
                    sorted.put(key, value);
                }
            }
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static JSONArray entries(Map<String, String> values) throws Exception {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String> item : values.entrySet()) {
            JSONObject entry = new JSONObject();
            entry.put("name", item.getKey());
            entry.put("value", item.getValue());
            out.put(entry);
        }
        return out;
    }

    private static Map<String, String> decodeEntries(JSONArray values, boolean permission) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) return out;
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.getJSONObject(index);
            String name = stateKey(item.getString("name"), permission ? "permission" : "opName");
            String state = permission
                    ? permissionDecisionValue(item.getString("value"))
                    : appOpModeValue(item.getString("value"));
            if (out.put(name, state) != null) {
                throw new IllegalArgumentException("Duplicate policy entry: " + name);
            }
        }
        return out;
    }

    private static String stateKey(String value, String name) {
        String normalized = required(value, name).trim();
        if (normalized.length() > 180 || !normalized.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String normalizedValue(String value) {
        return required(value, "state").toUpperCase(java.util.Locale.ROOT);
    }
}
