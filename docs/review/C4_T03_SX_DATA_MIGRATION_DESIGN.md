# C4-T03 SX Data Migration

## Scope

Migrate desensitized SX `sx_config` / ConfigProvider documents onto CAS
instance-scoped profiles. Work is in this repository. License and time-guard
keys stay `DROP_NON_BUSINESS`.

RD is `RD_BASELINE` only. Not DingTalk PASS and not VA Pro equivalence.

## DISCOVER / CLASSIFY

SX stores location/device/network/camera/bluetooth JSON under `sx_config`
keys `baseKey_pkg:userId`, shared with virtual clients through ConfigProvider
on the same UID as BlackBox. CAS already has per-instance profile stores and
`VirtualCameraMediaStore`. There was no versioned, idempotent, rollbackable
bridge from SX documents to those stores. That is `KI-R03-048`
(`TEST_EVIDENCE_GAP`).

## Design

```text
sx-config-v1 document (desensitized fixture)
  -> SxInstanceProfileMigrator
       1. hash source, keep canonical text
       2. snapshot current CAS profiles as backup
       3. optional abort (interrupt) before apply
       4. copy camera bytes into instances/u{user}/{pkg}/...
       5. overlay location/device/wifi/cell/bluetooth/camera
       6. COMMITTED, sourceKept=true
```

Rules:

- Same source hash + COMMITTED = `IDEMPOTENT`, no second apply.
- Interrupt after backup does not mutate live profiles.
- Rollback restores the backup and deletes new media; source remains.
- User 0 and clone user do not share profile values or media files.
- Migrator never talks to BlackBoxCore.

## Acceptance

- Domain self-test: commit, replay, isolation, interrupt, rollback.
- RD `c4-t03-migrate` on `RD测试` with fixture package, two virtual users.
- `va_pro_equivalent` remains `NOT_PROVEN`.
