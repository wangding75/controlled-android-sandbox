# T57-R03-P4-FIX01-H — Registry / Known Issue / VA corpus reconciliation

RESULT: `RECONCILED_NO_SILENT_DELETES`

HEAD at time of this note is the FIX01-D/E series on
`feature/t57-r03-va-pro-capability-campaign`.
`va_pro_equivalent` stays `NOT_PROVEN` for every capability.

## Capability Registry

Updated evidence pointers only:

| id | implementation | rd_api32 | api35 | notes |
| --- | --- | --- | --- | --- |
| activity_framework | PARTIAL | PASS | PARTIAL | FIX01-A stub architecture; API35 install PASS, launch gate FAIL |
| pending_intent_intent_sender | PARTIAL | PASS | UNVERIFIED | FIX01-D AlarmManager relay after live `:guestN` kill |
| system_service_virtualization | PARTIAL | PARTIAL | UNVERIFIED | FIX01-E explicit matrix; Account visibility stored |
| process_death_recovery | PARTIAL | PASS | UNVERIFIED | FIX01-B process-grounded classifiers + FIX01-D kill |
| package_lifecycle_clear_delete_reinstall | PARTIAL | PASS | UNVERIFIED | FIX01-C all-user rollback/reset |
| android_oem_compatibility | GAP | N/A | UNVERIFIED | API36 AVD start attempted if image present |

No capability was marked broad commercial PASS.

## Known Issues

No issue was deleted. Status vocabulary stays as recorded
(`RECORDED` / `KEEP_AS_IS` / `NOT_PROVEN` / `FIXED`).

FIX01 did not silently convert T57-M10 `RECORDED` rows into `FIXED`.
KI-R03-NATIVE-010 remains the isolated-process native-enforcement note.

System-holder PI is no longer an unrecorded hole: it is evidenced by
`T57_R03_P4_FIX01_D_SYSTEM_HOLDER_PI.md` and does not remove any KI row.

## VA corpus (83)

`docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml` is walked, not
rewritten item-by-item in this commit. `cas_status` values stay
`IMPLEMENTED` / `NEEDS_TEST` / `GAP` / `UNVERIFIED` as previously
recorded. FIX01-A/D/E change evidence paths, not a VA Pro verdict.

XH is `ENVIRONMENT_NOT_AVAILABLE`.
