# M5-T19.1-R Live Architecture Audit

## Problem

The M5-T19 architecture report claimed twelve production Java classes above 500 lines. Its scanner only visited App, Runtime and Framework sources and omitted Domain. A full `*/src/main/**/*.java` scan at the frozen M5-T19 commit finds thirteen classes; `ManifestReceiverRegistry.java` was missing from the report. The gate then trusted the pre-generated JSON instead of recomputing the metric.

## Resolution

- Added `tools/architecture_metrics.py` as the shared complete production-source scanner.
- The M5-T19 baseline is reproduced directly from Git commit `2071974236f55d3a94aac40bb70d834cea590218` and must equal thirteen.
- The live tree is rescanned on every run and written to `build/verification/m5-t19-1-r-live-architecture-audit.json`.
- The historical preflight and development documentation now disclose thirteen, including `sandbox-domain/.../ManifestReceiverRegistry.java`.
- The gate no longer treats a stale generated JSON count as source truth. Existing per-class hard limits remain enforced.

## Current result

At M5-T19.1-R the live full-scope count is fifteen. This is a truthful architecture-debt metric, not a PASS claim that those classes have been decomposed. Future runs recompute the list from source.
