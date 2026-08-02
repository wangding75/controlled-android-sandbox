# M5-T19 architecture decoupling and type-debt elimination

## Goal

Close the remaining offline architecture debt before the locked Android build stage. This iteration does not add sandbox capability claims. It removes the legacy top-level Bundle runtime protocol, extracts high-risk responsibilities from central services, centralizes framework-method classification and adds explicit critical-path test ownership.

## Authorized source scope

1. Remove all legacy Bundle methods from `IRuntimeBroker` and `IGuestProcess`; retain Bundle only as a bounded private implementation payload behind typed V2 request/result contracts.
2. Reduce Android `Service` classes to lifecycle and capability issuance responsibilities by extracting package-management sessions and profile authorities.
3. Extract Activity/Task checkpoint and rollback behavior from `ActivityTaskLedger` without introducing a second state authority.
4. Extract ordinary Guest binding and Binder-death ownership from `RuntimeBrokerService`.
5. Extract durable virtual-system-service record types from the runtime Store while preserving schema 6 and atomic persistence.
6. Split application-environment parsing and UsageStats projection from its dispatch interceptor.
7. Route method-name classification through `InvocationMethodMatcher`; reject new direct `methodName.contains`, `startsWith` or `endsWith` dispatch.
8. Add a critical-path regression ownership gate. It must explicitly state that it is not JaCoCo line/branch coverage evidence.
9. Preserve all feature behavior, `ref/upstream`, the frozen 113-category capability matrix and device evidence count 0.

## Acceptance

- legacy Bundle AIDL declarations in Broker and Guest interfaces equal zero;
- repository-owned primary runtime routing remains typed V2 with protocol version, request correlation and stable error results;
- `PackageManagementService` is at most 200 lines and only owns Android service lifecycle/dependency issuance;
- App/Runtime/Framework production classes above 500 lines decrease from fourteen to at most twelve; the full `*/src/main/**/*.java` evidence must also include Domain and report thirteen;
- `ActivityTaskLedger`, `VirtualSystemServiceStore` and `RuntimeBrokerService` are smaller than their M5-T18 baselines and extracted owners have direct Host regression ownership;
- direct method-name substring/prefix/suffix dispatch count is zero outside the centralized matcher;
- all static Android, Java/Runtime/Framework, Native and reproducible-source gates pass;
- JaCoCo, Android Keystore, real AGP/AIDL and device evidence remain pending until M6.
