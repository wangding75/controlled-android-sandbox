# M5-T19.1-K Guest storage Context completion

- Finding: P2-04 left `moveDatabaseFrom`, `moveSharedPreferencesFrom` and `createDeviceProtectedStorageContext` unimplemented.
- Credential-protected root: `<instance>/data`.
- Device-protected root: `<instance>/device_protected`.
- External files/cache/OBB/media remain shared because Android protected-storage contexts only change internal storage.
- Database moves include `-journal`, `-wal` and `-shm`; the main database moves last as the commit marker.
- SharedPreferences moves include the `.cspf` main file and `.tmp` companion. Cached source and destination objects are invalidated with `SHARED_PREFERENCES_MOVED` so stale references cannot recreate old data.
- Move serialization uses a JVM lock and an OS file lock under the Guest instance root.
- Existing destinations are never overwritten.
- Partial companion moves are rolled back in reverse order.
- Source and destination must share package name, virtual user and canonical Guest instance root.
- Host Contexts and foreign Guest identities fail with `CROSS_GUEST_STORAGE_MOVE_DENIED`.
- Direct Host tests cover both move directions, missing sources, destination collisions, injected rollback and foreign identities.
- Android framework, encryption-at-rest, direct-boot lifecycle, Emulator and physical-device evidence: 0.
