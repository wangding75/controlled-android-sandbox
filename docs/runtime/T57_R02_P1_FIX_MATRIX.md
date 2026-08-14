# T57-R02 P1 Fix Matrix

| ID | Defect | Status | Evidence |
|---|---|---|---|
| P1-01 | Provider `applyBatch` real chain | `RESOLVED_BY_T57` | `GuestComponentRuntime` routes to one `ProviderBatchRuntime` transport; API32 RD probe returned two typed results and verified Cursor readback. `RD-06 PASS`. |
| P1-02 | Service binding records | `RESOLVED_BY_T57` | Runtime service records retain per-connection intent/token state; API32 RD probe received a live Guest `ServiceConnection` Binder callback. `RD-06 PASS`. |
| P1-03 | PendingIntent permission positional semantics | `RESOLVED_BY_T57` | Interceptor uses the real send argument position and a legacy test adapter position; API32 RD probe reached `IIntentSender` and `DetailActivity`. `RD-06 PASS`. |
| P1-04 | clear/delete consistency | `RESOLVED_BY_T57` | Stop barrier precedes destructive mutation; store cleanup warnings become explicit partial failures. `DEVICE_REGRESSION_PENDING`. |
| P1-05 | Process-slot capacity | `PARTIAL_RD_PROOF` | API32 RD probe now proves main slot 6 plus declared `:remote` slot 2, different PID and remote Service stop; isolated pool capacity and sustained slot pressure remain open. `RD-06 PARTIAL`. |
| P1-06 | Virtual multi-package PMS | `PARTIAL_RD_PROOF` | Runtime requests now carry a metadata-only installed-package universe and enforce `<queries>` visibility. API32 RD imported `fixture32`; `getApplicationInfo`, `getPackageInfo`, and launcher resolution passed as `FRAMEWORK_PROBE_PACKAGE_UNIVERSE_PASS`. Cross-package component launch and provider-authority query remain open. `RD-06 PARTIAL`. |
