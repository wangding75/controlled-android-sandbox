# C0-T04 CAS / VA PRO fact convergence

- status: `PASS`
- commit: `76d164795294b676a7cd9a2b20bbd829c5cd5ae3`
- tree: `9bab9afe4aa571be7393d267caf5c639e6874a00`
- maturity: `RD_BASELINE`
- VA Pro equivalent: `NOT_PROVEN`

## Authority and checks

The current registry, Known Issues registry, VA corpus and C0-T03 evidence are the active facts. Older FIX01 reconciliation notes are historical and are not used as current capability IDs or status authority.

- capabilities: 14
- Known Issues: 43
- VA corpus entries: 83
- C0-T03 evidence: `verification/catch-up/C0-T03`
- collect-all NEW_REGRESSION: `0`

## VA corpus classification

Scope classification is separate from CAS compatibility status. `PROVEN` means the mapped CAS surface has sufficient current evidence; it never means VA Pro equivalence. `NEEDS_FIXTURE` means the CAS source surface exists but the required package-neutral fixture is incomplete. `IN_SCOPE` records an implementation gap that remains in the product scope. `OUT_OF_SCOPE` is provisional for IDs absent from the locked public source snapshot. No duplicate corpus IDs were found.

| CAS compatibility status | Count |
|---|---:|
| CAS_ALREADY_COVERS | 1 |
| GAP | 51 |
| NEEDS_TEST | 28 |
| UNVERIFIED | 3 |

| Scope classification | Count |
|---|---:|
| IN_SCOPE | 51 |
| OUT_OF_SCOPE | 3 |
| DUPLICATE | 0 |
| NEEDS_FIXTURE | 28 |
| PROVEN | 1 |

## Failure classification

- No registry, corpus, Known Issues, C0-T03 evidence, or collect-all convergence errors.

## Evidence paths

- `docs/capability/CAPABILITY_REGISTRY.yaml`
- `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`
- `docs/review/KNOWN_ISSUES.yaml`
- `verification/catch-up/C0-T03/README.md`
