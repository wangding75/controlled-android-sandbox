# C4-R05 C1 Activity-token correlation fix

Date: 2026-08-26

## Result

The C1 user0 formal regression completed with 350/350 accepted cases and zero
non-environment failures. One `LOW_MEMORY` event occurred at loop 2 / standard;
the emulator was restarted and that case passed on the controlled second
attempt. The receipt is
`verification/catch-up/C4-R05/local-regressions-20260826/c1-t01-user0-summary.json`.

## Root cause

For a `singleTop` or `CLEAR_TOP|SINGLE_TOP` delivery, Android reuses the same
physical Activity token while the framework sends a new delivery request. The
runtime correlation map replaced the original creation request/operation with
that delivery request. `StubActivityBase` continued emitting lifecycle events
with the original identity, so a valid lifecycle was reported as
`LAUNCH_CORRELATION_MISMATCH` even though the fixture semantic assertions
passed.

## Change and verification

- `GuestLaunchObservation.linkActivity()` now retains the first correlation for
  an Activity token and only creates a mapping for a new token.
- `GuestLaunchGateSelfTest` covers a reused token with a new delivery request
  followed by CREATED/RESUMED/FIRST_FRAME lifecycle events.
- `python tools/static_android_compile.py` passed, including the guest launch
  gate and ActivityLaunchCoordinator self-tests.
- A targeted `single_top_top` user0 run passed with launch status PASS and
  `FIXTURE_SEMANTIC_PASS`.

This preserves the lifecycle correlation of the physical Activity instance;
new child Activity tokens continue to receive independent correlations.
