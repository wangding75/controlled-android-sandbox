# T57-R02 P1 Fix Matrix

| ID | Defect | Status | Evidence |
|---|---|---|---|
| P1-01 | Provider `applyBatch` real chain | `RESOLVED_BY_T57` | `GuestComponentRuntime` routes to one `ProviderBatchRuntime` transport; API32 RD probe returned two typed results and verified Cursor readback. `RD-06 PASS`. |
| P1-02 | Service binding records | `RESOLVED_BY_T57` | Runtime service records retain per-connection intent/token state; API32 RD probe received a live Guest `ServiceConnection` Binder callback. `RD-06 PASS`. |
| P1-03 | PendingIntent permission positional semantics | `RESOLVED_BY_T57` | Interceptor uses the real send argument position and a legacy test adapter position; API32 RD probe reached `IIntentSender` and `DetailActivity`. `RD-06 PASS`. |
| P1-04 | clear/delete consistency | `RESOLVED_BY_T57` | Single PackageManagementSession stop barrier precedes destructive mutation; isolated stop now waits for physical death; cleanup warnings remain explicit. Full API32 RD regression `9/9 PASS`. |
| P1-05 | Process-slot capacity | `PARTIAL_RD_PROOF` | Source contract now has 64 ordinary and 16 isolated slots; API32 proves remote process routing, isolated transport and recovery. Sustained 64/16 pressure and multi-OEM behavior remain open. |
| P1-06 | Virtual multi-package PMS | `RD_BASELINE_PASS` | API32 framework transport imported `fixture32`; package/application lookup, launcher resolve, provider authority visibility, remote Activity and remote Service process routing passed under virtual `<queries>` policy. Broader Android-version/OEM matrix remains open. |
