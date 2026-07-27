package com.warden.controlledsandbox;

import java.io.File;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable member of one installed multi-APK revision. */
final class PackageArtifactRecord implements Comparable<PackageArtifactRecord> {
    static final String TYPE_BASE = "BASE";
    static final String TYPE_FEATURE = "FEATURE";
    static final String TYPE_CONFIG = "CONFIG";

    final String splitName;
    final String type;
    final String configForSplit;
    final String usesSplit;
    final String path;
    final String sha256;

    PackageArtifactRecord(String splitName, String type, String configForSplit,
                          String usesSplit, String path, String sha256) {
        this.splitName = splitValue(splitName, "splitName");
        this.type = normalizeType(type);
        this.configForSplit = splitValue(configForSplit, "configForSplit");
        this.usesSplit = splitValue(usesSplit, "usesSplit");
        this.path = required(path, "path");
        this.sha256 = requiredDigest(sha256);
        if (TYPE_BASE.equals(this.type) && !this.splitName.isEmpty()) {
            throw new IllegalArgumentException("Base artifact cannot have a split name");
        }
        if (TYPE_BASE.equals(this.type)
                && (!this.configForSplit.isEmpty() || !this.usesSplit.isEmpty())) {
            throw new IllegalArgumentException("Base artifact cannot declare split dependencies");
        }
        if (!TYPE_BASE.equals(this.type) && this.splitName.isEmpty()) {
            throw new IllegalArgumentException("Split artifact requires a split name");
        }
        if (TYPE_CONFIG.equals(this.type) && this.configForSplit.isEmpty()) {
            throw new IllegalArgumentException("Configuration split requires configForSplit");
        }
        if (!TYPE_CONFIG.equals(this.type) && !this.configForSplit.isEmpty()) {
            throw new IllegalArgumentException("Only configuration splits may declare configForSplit");
        }
    }

    static PackageArtifactRecord legacyBase(String apkPath, String sha256) {
        return new PackageArtifactRecord("", TYPE_BASE, "", "", apkPath, sha256);
    }

    boolean base() { return TYPE_BASE.equals(type); }
    String fileName() { return base() ? "base.apk" : "split_" + safe(splitName) + ".apk"; }

    PackageArtifactRecord withPath(String newPath) {
        return new PackageArtifactRecord(splitName, type, configForSplit, usesSplit, newPath, sha256);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("splitName", splitName).put("type", type)
                .put("configForSplit", configForSplit).put("usesSplit", usesSplit)
                .put("path", path).put("sha256", sha256);
    }

    static PackageArtifactRecord fromJson(JSONObject value) throws JSONException {
        return new PackageArtifactRecord(value.optString("splitName"), value.optString("type", TYPE_BASE),
                value.optString("configForSplit"), value.optString("usesSplit"),
                value.getString("path"), value.getString("sha256"));
    }

    @Override public int compareTo(PackageArtifactRecord other) {
        if (base() != other.base()) return base() ? -1 : 1;
        return splitName.compareTo(other.splitName);
    }

    private static String normalizeType(String value) {
        String normalized = value(value).toUpperCase(Locale.ROOT);
        if (!TYPE_BASE.equals(normalized) && !TYPE_FEATURE.equals(normalized)
                && !TYPE_CONFIG.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported artifact type: " + value);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static String requiredDigest(String value) {
        String normalized = required(value, "sha256").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be 64 hex characters");
        return normalized;
    }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String splitValue(String value, String name) {
        String normalized = value(value);
        if (!normalized.isEmpty() && !"base".equals(normalized)
                && !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
    static String safe(String value) {
        String normalized = splitValue(value, "splitName");
        if (normalized.isEmpty() || "base".equals(normalized)) {
            throw new IllegalArgumentException("splitName is invalid");
        }
        return normalized;
    }
}
