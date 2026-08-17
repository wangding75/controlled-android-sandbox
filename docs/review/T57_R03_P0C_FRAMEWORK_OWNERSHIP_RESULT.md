# T57-R03-P0C Activity / Service Framework Ownership

RESULT: PASS with classified remainder

Normal Activity/Service lifecycle remains framework-owned
(ActivityThread / Instrumentation / AppComponentFactory). This campaign did
not restore manual lifecycle.

## Activity / Service

Existing static self-tests continue to cover launch/task/result, Service
coordinator, isolated process route policy, and JobService bridge.

## KI-R03-NATIVE-010

Reclassified from TEST_EVIDENCE_GAP to CURRENT_DEFECT.

Evidence: guest `NativeAdversarialProbeService` ClassNotFoundException against
host debug `DexPathList`. In-sandbox guest Service class resolution is still
wrong. Fixing it requires the framework-owned LoadedApk/ClassLoader path, not
a GuestComponentRuntime rewrite. Deferred to P1.

Native hostile evidence now comes from the isolated-UID production Broker
path (P0A-03), which does not instantiate that guest Service class.

VA Pro: NOT_PROVEN
