# R6 isolatedProcess fail-closed policy

Baseline: `main@ca30b37a3a47472e7a05f092ad60bfc900c337f5`

## Changes

- Guest sessions retain immutable manifest component metadata.
- Component invocation checks the actual manifest `isolatedProcess` flag before lifecycle creation.
- Isolated components now fail with `ISOLATED_PROCESS_UNAVAILABLE` instead of running under an ordinary Guest host UID.
- Runtime status explicitly reports `isolatedProcessSupported=false` and the fail-closed policy.
- VirtualPackageMetadata exposes normalized component lookup for runtime policy checks.

## Verification

The policy self-test covers normal services, isolated services, empty component operations and relative class-name lookup. The full repository verification passed twice before merge and once on main.

## Future implementation

Real support requires predeclared Android services with `android:isolatedProcess=true`, a capability-limited broker channel, UID evidence from the platform and API-level emulator tests. Until those conditions are met, rejection is the only safe behavior.
