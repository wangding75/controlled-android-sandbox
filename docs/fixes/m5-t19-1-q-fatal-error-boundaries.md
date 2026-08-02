# M5-T19.1-Q Fatal Error Boundaries

## Problem

State, Binder, persistence, lifecycle and callback boundaries used `catch (Throwable)` and converted every failure into a typed result, rollback or best-effort cleanup. That included JVM `Error` values such as `OutOfMemoryError`, `ThreadDeath`, `LinkageError` and wrapped fatal causes.

## Resolution

- Added module-local `FatalErrorPolicy` helpers for App, Runtime, Framework, Domain persistence and Companion workspace transactions.
- The helper walks a bounded cause chain and immediately rethrows any `Error`.
- Added the guard to 100 critical `catch(Throwable)` blocks across transaction, Binder, provider, Guest lifecycle, component lifecycle, persistence and framework dispatch paths.
- Reflection-only compatibility discovery and optional native-library loading remain explicit exceptions because missing platform symbols are part of their feature-detection contract.
- Added an executable architecture gate and representative Runtime, persistence and capability-cleanup tests.

## Contract

Recoverable `Exception` values may be converted into typed failures or best-effort cleanup results. `Error` values are never converted into ordinary operation failures by the guarded boundaries.

## Evidence

- `scripts/check-m5-t19-1-q-fatal-error-boundary.py`
- `RuntimeBrokerOperationBoundarySelfTest`
- `sandbox-domain` `SelfTest.testRecoverableFileStoreFatalBoundary`
- `CapabilityServiceProxySelfTest.testFatalCleanupEscapes`
