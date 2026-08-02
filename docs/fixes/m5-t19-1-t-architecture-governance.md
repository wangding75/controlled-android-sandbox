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

The current report is generated in `build/verification/m5-t19-1-t-architecture-governance.json`. The baseline is recalculated from Git Commit `b7372b5002fd205a31acb323c921100d6f9096d4`; a pre-generated JSON file is not treated as source truth.
