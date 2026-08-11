# M5-T19.1-T Architecture Governance

## Scope

This stage extends source governance beyond class line counts. Metrics are recalculated from every production Java file under `*/src/main/**/*.java`.

## Enforced checks

- The Gradle project dependency graph must contain no cycle.
- No new package dependency strongly connected component may appear relative to the immutable M5-T19.1-S baseline.
- Maximum approximate cyclomatic complexity may not increase.
- Counts of methods at complexity 15, 25, and 40 may not increase.
- Maximum production methods per source file may not increase.
- Counts of source files with at least 40, 80, and 120 methods may not increase.
- New public or protected Java signatures require an exact reviewed allowlist entry.
- Existing dependency-direction rules in `scripts/check-architecture.py` remain mandatory.

The metric is deliberately conservative. It is a regression gate, not a claim that current package cycles, complexity, or public API size are ideal.

## Evidence

The current report is generated in `build/verification/m5-t19-1-t-architecture-governance.json`. The historical M5-T19 baseline remains immutable, while the T51 gate recalculates its final-tree comparison from the verified T51 Start Commit `1ebf56e912246b3b8f3deb9e0b9b7019c24b751d4`. T51's reviewed framework-capability additions have an exact complexity/method-count budget; a pre-generated JSON file is not treated as source truth.
