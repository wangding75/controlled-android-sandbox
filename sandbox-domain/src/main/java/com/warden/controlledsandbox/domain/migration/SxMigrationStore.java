package com.warden.controlledsandbox.domain.migration;

import java.util.Map;

/** Persistence port. Implementations must not share state across virtual users. */
public interface SxMigrationStore {
    SxMigrationRecord read(String packageName, int virtualUserId);
    void write(SxMigrationRecord record);
    Map<String, String> snapshotProfiles(String packageName, int virtualUserId);
    void restoreProfiles(String packageName, int virtualUserId, Map<String, String> snapshot);
    void applyProfiles(String packageName, int virtualUserId, Map<String, String> mapped);
    Map<String, String> readApplied(String packageName, int virtualUserId);
    String writeMedia(String packageName, int virtualUserId, String kind, byte[] bytes);
    void deleteMedia(String packageName, int virtualUserId, String relativePath);
}
