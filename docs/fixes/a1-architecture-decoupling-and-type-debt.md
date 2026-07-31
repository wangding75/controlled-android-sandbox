# A1 architecture decoupling and type-debt closure

M5-T19 removes the legacy Broker/Guest Bundle AIDL surface and separates central runtime responsibilities without adding capability claims.

Key boundaries:

- typed V2 is the sole cross-process generic execution contract;
- package Android Service lifecycle is separate from management/runtime/system-service sessions;
- Activity/Task checkpoint copying is separate from live state mutation;
- ordinary Guest connection ownership is separate from Broker business routing;
- durable service records are separate from Store scheduling and persistence;
- method classification is centralized and direct substring dispatch is gated;
- critical-path regression ownership is measured without claiming line coverage.
