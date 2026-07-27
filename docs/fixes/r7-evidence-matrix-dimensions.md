# R7 multidimensional capability evidence

Baseline: `main@c6d0dc5eec965cb5e5c90b71c52e7efd4ae253c5`

## Changes

- Replaced the single self-declared source percentage with schema-v2 source, production-wiring and device-verification dimensions.
- Updated stale route, Activity and virtual-UID evidence to the actual production implementations.
- Marked real isolated UID execution blocked instead of complete.
- Marked bounded Native hooks and diagnostics partial instead of complete.
- Added strict `--require-source-complete` and `--require-device-verified` modes.
- Updated current test and roadmap documents; retained the original B2 report as an explicitly historical snapshot.

## Current result

- Source weighted: 93.8%.
- Production wiring weighted: 90.3%.
- Device verification weighted: 0.0%.

These percentages describe different evidence sets and must not be combined into an application compatibility rate.
