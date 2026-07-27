# R8 framework signature and debug command security

Baseline: `main@0a51787741d9e24b3aedd089b419c2918c90f987`

## Changes

- MethodIdentityPolicy now validates every identity-rewrite argument against the reflected parameter type.
- Package rules require String, UID rules require int/Integer, package collections require String[]/List and AttributionSource rules require the exact Android type name.
- Same-name and same-argument-count overloads with incompatible parameter types fail installation audit before proxy replacement.
- DebugCommandActivity remains available to ADB shell but now requires `android.permission.DUMP`, preventing ordinary third-party applications from invoking debug import/launch/component commands.
- Repository self-test verifies the debug manifest security gate.

## Verification

FrameworkProxySelfTest includes a same-count wrong-type overload regression. The complete repository gate passed twice on the fix branch and once after merge.
