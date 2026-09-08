# P1-02 Portable Fixture Specification

The fixture suite deliberately separates native platform observations from CAS results. It does not assert VA PRO equivalence.

| Fixture/package | Contract | Native witness | Later owner |
|---|---|---|---|
| `fixture-library-provider` | Regular provider package with a public marker class, string resource, asset, exported remote-process Service, and reentrant Provider. It is not an Android privileged shared library. | `ProviderProbeActivity`; provider log markers. | P1-03, P1-05, P1-06 |
| `fixture-library-consumer` | Uses a package context to read provider class/resource/asset; starts remote Service; performs outer→inner reentrant Provider call; records native absence of missing service/authority. | `ProjectionConsumerActivity`; `CS_P1_02_FIXTURE` markers. | P1-03, P1-05, P1-06 |
| `fixture-basic` + `fixture-compat32` | Existing Service, Provider, peer Provider, FD, observer, and process fixtures. | Existing S05/S07 and provider campaign. | P1-05 to P1-15 |
| `fixture-split-base` + `fixtureSplitFeature` | Existing dynamic split class-loading fixture. | `CS_SPLIT_FIXTURE` base and feature markers. | P1-13 |

## Native baseline contract

`tools/verification/run_p1_02_native_fixture_baseline.py` directly installs the provider, consumer, and peer fixture APKs on an explicitly selected device. A pass requires:

1. provider package launch;
2. provider class/resource/asset projection by the consumer;
3. missing Service returns `null`;
4. missing Provider has the exact native absence outcome recorded (API36 currently `IllegalArgumentException`);
5. remote Service create/start markers in its declared process;
6. outer Provider call returns the inner reentrant value.

The run has no retry path. The script persists raw ADB commands, APK SHA256, logs, and marker results under `out/verification`.

## Use constraints

- Run the native fixture first for a new platform coordinate; use its recorded absence behavior as the later CAS comparison, not an assumed universal return value.
- A CAS failure fixture must preserve first failure (`attempt=1`) and identify the relevant decision record before any runtime modification.
- Only the named later task may broaden a fixture's scope. This task creates no production runtime behavior and no commercial App claim.
