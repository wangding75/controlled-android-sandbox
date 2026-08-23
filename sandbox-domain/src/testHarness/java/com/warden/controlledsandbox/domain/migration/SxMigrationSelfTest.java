package com.warden.controlledsandbox.domain.migration;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SxMigrationSelfTest {
    private SxMigrationSelfTest() { }

    public static void run() {
        FakeStore store = new FakeStore();
        SxInstanceProfileMigrator migrator = new SxInstanceProfileMigrator(store);
        SxLegacyConfigDocument user0 = document("com.example.guest", 0, "User Zero",
                "31.23", "121.47", "02:00:00:00:00:10", "aaaaaaaaaaaaaaaa");
        SxLegacyConfigDocument user1 = document("com.example.guest", 1, "User One",
                "22.54", "113.93", "02:00:00:00:00:20", "bbbbbbbbbbbbbbbb");

        SxMigrationRecord first = migrator.migrate(user0);
        require(SxMigrationRecord.COMMITTED.equals(first.status), "first migrate must commit");
        require(first.sourceKept, "old source must be kept after commit");
        require(user0.sourceHash.equals(first.sourceHash), "source hash must be recorded");

        SxMigrationRecord replay = migrator.migrate(user0);
        require(SxMigrationRecord.IDEMPOTENT.equals(replay.status), "repeat migrate must be idempotent");
        require(store.applyCount.get(key(user0)) == 1, "idempotent replay must not re-apply");

        SxMigrationRecord clone = migrator.migrate(user1);
        require(SxMigrationRecord.COMMITTED.equals(clone.status), "user1 migrate must commit");
        Map<String, String> applied0 = store.readApplied(user0.packageName, 0);
        Map<String, String> applied1 = store.readApplied(user1.packageName, 1);
        require(!"31.23".equals(applied1.get("location.lat")), "users must not share latitude");
        require("31.23".equals(applied0.get("location.lat")), "user0 latitude must persist");
        require("22.54".equals(applied1.get("location.lat")), "user1 latitude must persist");
        require(!applied0.get("camera.mediaPath").equals(applied1.get("camera.mediaPath")),
                "media paths must be instance-scoped");

        SxLegacyConfigDocument changed = document("com.example.guest", 0, "User Zero",
                "39.90", "116.40", "02:00:00:00:00:10", "aaaaaaaaaaaaaaaa");
        SxMigrationRecord interrupted = migrator.migrate(changed, true);
        require(SxMigrationRecord.INTERRUPTED.equals(interrupted.status), "abort must interrupt");
        require("31.23".equals(store.readApplied(user0.packageName, 0).get("location.lat")),
                "interrupt must not replace live profiles");
        SxMigrationRecord rolled = migrator.rollback(user0.packageName, 0);
        require(SxMigrationRecord.ROLLED_BACK.equals(rolled.status), "rollback must restore");
        require("31.23".equals(store.profiles.get(key(user0)).get("location.lat")),
                "rollback of interrupted follow-up must restore last committed profiles");
        require(store.read(user0.packageName, 0).sourceKept, "old source remains after rollback");

        SxLegacyConfigDocument fresh = document("com.example.fresh", 0, "Fresh",
                "1.0", "2.0", "02:00:00:00:00:30", "cccccccccccccccc");
        SxMigrationRecord interruptedFresh = migrator.migrate(fresh, true);
        require(SxMigrationRecord.INTERRUPTED.equals(interruptedFresh.status),
                "fresh abort must interrupt");
        migrator.rollback(fresh.packageName, 0);
        require(store.applyCount.getOrDefault(fresh.packageName + "#0", 0) == 0,
                "interrupted first migrate must not apply");
    }

    private static SxLegacyConfigDocument document(String packageName, int userId, String label,
            String lat, String lng, String bssid, String androidId) {
        Map<String, String> location = map("enabled", "true", "lat", lat, "lng", lng,
                "accuracy", "5", "altitude", "10", "intervalMs", "1000");
        Map<String, String> device = map("enabled", "true", "brand", "Fixture", "model", "Model",
                "manufacturer", "Mfr", "androidId", androidId, "serial", "SER" + userId,
                "imei", "353322101234567");
        Map<String, String> network = map("enabled", "true", "ssid", "WiFi-" + userId,
                "bssid", bssid, "mac", bssid, "mcc", "460", "mnc", "1", "lac", "100", "cid", "200");
        Map<String, String> camera = map("enabled", "true", "type", "image", "path", "cam.png");
        Map<String, String> bluetooth = map("enabled", "true", "name", "BT-" + userId,
                "address", bssid);
        byte[] media = ("PNG" + userId).getBytes(StandardCharsets.UTF_8);
        return new SxLegacyConfigDocument(packageName, userId, label, location, device, network,
                camera, bluetooth, media, "image");
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put(pairs[index], pairs[index + 1]);
        return values;
    }

    private static String key(SxLegacyConfigDocument document) {
        return document.packageName + "#" + document.virtualUserId;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeStore implements SxMigrationStore {
        final Map<String, SxMigrationRecord> records = new HashMap<>();
        final Map<String, Map<String, String>> profiles = new HashMap<>();
        final Map<String, Integer> applyCount = new HashMap<>();

        FakeStore() {
            profiles.put("com.example.guest#0", new LinkedHashMap<>(Map.of("location.lat", "seed-lat")));
            profiles.put("com.example.guest#1", new LinkedHashMap<>(Map.of("location.lat", "seed-lat")));
        }

        private String key(String packageName, int userId) {
            return packageName + "#" + userId;
        }

        @Override public SxMigrationRecord read(String packageName, int virtualUserId) {
            return records.get(key(packageName, virtualUserId));
        }

        @Override public void write(SxMigrationRecord record) {
            records.put(key(record.packageName, record.virtualUserId), record);
        }

        @Override public Map<String, String> snapshotProfiles(String packageName, int virtualUserId) {
            return Map.copyOf(profiles.getOrDefault(key(packageName, virtualUserId), Map.of()));
        }

        @Override public void restoreProfiles(String packageName, int virtualUserId,
                Map<String, String> snapshot) {
            profiles.put(key(packageName, virtualUserId), new LinkedHashMap<>(snapshot));
        }

        @Override public void applyProfiles(String packageName, int virtualUserId,
                Map<String, String> mapped) {
            String id = key(packageName, virtualUserId);
            profiles.put(id, new LinkedHashMap<>(mapped));
            applyCount.put(id, applyCount.getOrDefault(id, 0) + 1);
        }

        @Override public Map<String, String> readApplied(String packageName, int virtualUserId) {
            return Map.copyOf(profiles.getOrDefault(key(packageName, virtualUserId), Map.of()));
        }

        @Override public String writeMedia(String packageName, int virtualUserId, String kind,
                byte[] bytes) {
            return "u" + virtualUserId + "/" + packageName + "/virtual-camera/source-" + bytes.length;
        }

        @Override public void deleteMedia(String packageName, int virtualUserId, String relativePath) {
        }
    }
}
