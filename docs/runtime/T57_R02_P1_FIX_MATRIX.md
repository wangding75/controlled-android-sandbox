# T57-R02 P1 Fix Matrix

| ID | Defect | Status | Evidence |
|---|---|---|---|
| P1-01 | Provider `applyBatch` real chain | `RESOLVED_BY_T57` | `GuestComponentRuntime` routes to `ProviderBatchRuntime`; success/failure and affected-row evidence are logged. | `DEVICE_REGRESSION_PENDING` |
| P1-02 | Service binding records | `RESOLVED_BY_T57` | Runtime service records retain per-connection intent/token state and expose bind/unbind/rebind counts. | `DEVICE_REGRESSION_PENDING` |
| P1-03 | PendingIntent permission positional semantics | `RESOLVED_BY_T57` | Interceptor uses the real send argument position and a legacy test adapter position; no string-content heuristic remains. | `DEVICE_REGRESSION_PENDING` |
| P1-04 | clear/delete consistency | `RESOLVED_BY_T57` | Stop barrier precedes destructive mutation; store cleanup warnings become explicit partial failures. | `DEVICE_REGRESSION_PENDING` |
| P1-05 | Process-slot capacity | `NEEDS_RD_DEVICE_PROOF` | Four-slot isolated-process policy and capacity telemetry exist; live concurrent slot evidence is not available. | `RD_TEST_REQUIRED` |
| P1-06 | Virtual multi-package PMS | `NEEDS_RD_DEVICE_PROOF` | Package state now carries queries and component contract fields; a live multi-package visibility/resolver trace is not available. | `RD_TEST_REQUIRED` |
