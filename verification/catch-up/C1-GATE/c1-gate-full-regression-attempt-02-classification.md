# C1-GATE full-regression attempt 02 classification

- **Result:** the first rerun stopped before the first regression case completed.
- **Observed error:** `IMPORT_PREPARE_FAILED` returned
  `IllegalArgumentException: Invalid install timestamps` while importing
  `com.warden.controlledsandbox.fixture`.
- **Device context:** MuMu `RD测试`, API 32, boot ID
  `773adc6f-e0aa-4997-a0ee-481a7773a10d`; the error occurred before any framework probe
  assertion or Guest runtime marker.
- **Classification:** `TEST_EVIDENCE_GAP` caused by persisted package metadata outliving a
  device wall-clock rollback across emulator reboot/snapshot state. This was repaired in
  `SandboxCatalogState.withImported()` by clamping `lastUpdateAt` to at least the historical
  `firstInstallAt`; the clock-rollback case is covered by `SandboxCatalogStateSelfTest`.
- **Repair verification:** Gradle build, static Android compile/self-tests, and the complete
  9-case suite then passed. The final suite transcript is `c1-gate-run.txt`; all nine device
  snapshots carry the same resolved serial, API level, boot ID, and current source HEAD.
