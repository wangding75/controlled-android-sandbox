# Development B3-T4B local test report

Date: **2026-07-27**

Status: **LOCAL PASS / SOURCE 94.6% / PRODUCTION WIRING 90.3% / DEVICE EVIDENCE 0.0%**

## Evidence dimensions

The capability matrix contains 37 tracked capabilities and reports three independent dimensions:

- Source: 33 complete, 4 partial, 0 missing; weighted 94.6%.
- Production wiring: 30 wired, 5 partial, 1 blocked, 1 not applicable; weighted 90.3%.
- Device verification: 0 verified, 0 partial, 35 not tested, 1 blocked, 1 not applicable; weighted 0.0%.

The production percentage decreases slightly because Ordered Broadcast is now tracked as a real capability with
partial production wiring instead of being omitted from the denominator. The blocked production capability
remains real Android `isolatedProcess` execution.

## Passed locally

- Domain, architecture, repository and deterministic SBOM checks.
- Static compilation of all Java production/test sources with local Android/AIDL stubs.
- Persistent package/instance metadata recovery and collision-free virtual UID tests.
- Mandatory Framework Hook fail-closed and atomic rollback tests.
- Broker-owned Activity, Service, dynamic Receiver and Provider lifecycle tests.
- Explicit manifest Receiver indexing, exported/permission checks, deterministic on-demand process request,
  Session/generation rebinding and concurrent resolution tests.
- Implicit manifest Receiver action index and package-replacement/removal consistency tests.
- Action, category, scheme, host, path, MIME and target-package matching tests.
- Priority ordering, Receiver permission and sender-required Receiver permission tests.
- Cross-user rejection and 128-match fail-closed tests.
- Ordered result code/data/extras propagation, abort, clear-abort and failure-policy tests.
- Broadcast payload size and unsupported payload-type admission tests.
- Parser tests for intent-filter priority, categories and data/MIME metadata.
- Cursor, FileDescriptor, ContentObserver, Batch and URI Grant tests.
- Native policy, PLT hook, crash recorder and JNI boundary tests.
- Strict device-gate self-tests, PowerShell structure checks and Gradle bootstrap delegation.

## Not executed

Per the current development instruction, simulator, physical-device, ADB, real AGP/SDK/NDK build and
third-party application tests are skipped. No platform `BroadcastReceiver.PendingResult`, sticky/protected
broadcast, background-execution restriction, real Binder parcel limit, framework-originated broadcast or OEM
Receiver behavior has been verified. Device evidence therefore remains 0.0%.
