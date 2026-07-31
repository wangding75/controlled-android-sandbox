# M5-T19 development report

## Status

- Source status: PASS
- Production status: PARTIAL
- Device evidence: 0
- Feature expansion: none
- Reference-source modifications: 0

## Delivered architecture changes

1. Deleted all twelve legacy Bundle methods from `IRuntimeBroker` and `IGuestProcess`. The only cross-process generic execution endpoint is typed `executeV2`.
2. Added private `RuntimeBrokerOperationHandler` as the bounded in-process Bundle payload boundary; Bundle is no longer a top-level AIDL protocol.
3. Reduced `PackageManagementService` from 1,191 lines to 147 lines. Management, runtime-permission and virtual-system-service capabilities now have independent session owners.
4. Extracted profile management into `PackageProfileAuthority` and dependency creation/cleanup into `PackageServiceDependencies`.
5. Extracted checkpoint/rollback/deep-copy behavior into `ActivityTaskCheckpointCoordinator`; `ActivityTaskLedger` decreased from 1,741 to 1,512 lines.
6. Extracted Guest process binding, Binder death and unbind ownership into `RuntimeGuestConnectionPool`; `RuntimeBrokerService` decreased from about 1,388 to about 1,254 lines.
7. Extracted durable service record types into `VirtualSystemServiceRecords`; `VirtualSystemServiceStore` decreased from 1,654 to 1,482 lines without changing schema 6.
8. Split application-environment argument/return helpers and UsageStats projection; its interceptor decreased from 647 to 473 lines.
9. Centralized exact, prefix and fragment method routing in `InvocationMethodMatcher`; direct method-name substring routing is zero.
10. Added `InvocationMethodMatcherSelfTest` and an explicit critical-path regression ownership gate for twelve architecture-critical owners.

## Quantitative result

| Metric | M5-T18 | M5-T19 |
|---|---:|---:|
| Legacy Bundle AIDL declarations | 12 | 0 |
| Production classes above 500 lines | 14 | 12 |
| `PackageManagementService` | 1,191 | 147 |
| `ActivityTaskLedger` | 1,741 | 1,512 |
| `VirtualSystemServiceStore` | 1,654 | 1,482 |
| `RuntimeBrokerService` | about 1,388 | about 1,254 |
| Application-environment interceptor | 647 | 473 |
| Direct method-name substring routing | present | 0 |
| Critical source test owners mapped | not measured | 12/12 |

The critical-path ownership metric is not a line-coverage or branch-coverage percentage. JaCoCo has not been executed because the locked JDK-17/AGP environment is unavailable here.

## Verification

- Static Android-source compilation and all Java/Runtime/Framework Host regressions: PASS.
- Architecture, package boundary, AIDL and M5-T2 through M5-T19 stage gates: PASS.
- Native file/network/audio/loader/PLT, real Host SIGSEGV, Companion32/JNI and strict M3 gates: PASS.
- Reproducible source package and script-structure gates: PASS.
- Full verification is recorded as locally executable segmented PASS if the combined command exceeds the execution window; no uninterrupted result is fabricated.

## Remaining architecture debt

- Twelve production classes remain above 500 lines.
- `ActivityTaskLedger`, `VirtualSystemServiceStore` and `RuntimeBrokerService` remain large and should be changed only with Android build/device evidence or narrowly scoped follow-up refactors.
- Host regressions provide no real JaCoCo percentage.
- AES-GCM key material remains app-private rather than Android Keystore-backed.
- Android instrumentation, AGP-generated AIDL, Emulator and physical-device evidence remain zero.
