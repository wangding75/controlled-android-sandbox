# M5-T19.1-P Internal Bundle Boundary

- Finding: P2-09 the internal `RuntimeBrokerOperationHandler` Bundle boundary was declared as a public Java API.
- Baseline: `1a0ccc6126322a4083efad94ba2d0d5cf0f15a89`.
- Scope: internal Runtime Broker compatibility dispatch only; public typed AIDL remains unchanged.

## Implemented behavior

- `RuntimeBrokerOperationHandler` moved from `runtime.protocol` to `runtime.broker` and is package-private.
- `RuntimeBrokerOperationAdapter` moved beside the Handler and is package-private.
- `RuntimeBrokerOperationAdapter.execute` is package-private.
- `RuntimeBrokerService` is the only production implementation and dispatch caller.
- Public `IRuntimeBroker` and `IGuestProcess` AIDL continue to expose typed `RuntimeOperationRequest` and `RuntimeOperationResult`; no Bundle AIDL endpoint was added.
- A direct Host test exercises the internal adapter.
- The stage gate compiles an external-package probe and requires compilation to fail when it imports the internal Handler.

## Evidence boundary

The Java visibility boundary and typed AIDL surface are verified by local compilation. Generated Android AIDL, Gradle module API publication, emulator, and physical-device evidence are not claimed.
