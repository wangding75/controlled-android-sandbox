# T57-R03-P1F Binder / SystemService interception

RESULT: PASS

P1F is architecture convergence, not a rewrite to VA-style transaction hooks.

## Classification

`docs/system/T57_R03_BINDER_INTERCEPTION_MATRIX.yaml`

- A Java Proxy: PackageManager, ServiceManager, ReflectiveServiceHook, Settings identity
- B typed authority/store: Alarm, Notification, Job, Account, Clipboard, Settings,
  UsageStats, Shortcut, AppWidget
- C transaction relay: not introduced
- D kernel `/dev/binder*`: remains REQUIRES_PRIVILEGE / PARTIAL

## Decision

No P1E service proved Java Proxy unstable on API drift, class absence, parcel
signature, performance, or callback/death. Therefore no service was moved to a
lower Binder adapter.

VA commercial Binder items (VA-604/619/629/631/683) stay compatibility signals.

## Counts

Production `Proxy.newProxyInstance` call sites in sandbox-framework/main: 15.
Migrated this campaign: 0.

VA Pro: NOT_PROVEN
