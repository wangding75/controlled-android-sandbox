# M5-T17 privileged environment services source convergence

## Goal

Close the six source-feasible service gaps identified by the M5-T16 audit: SearchManager, StorageStats, GraphicsStats, ContextHub, PersistentDataBlock and SystemUpdate. The iteration adds deterministic per-package/per-virtual-user profiles and reversible Framework entry points without claiming physical hardware, privileged storage or update-engine execution.

## Authorized source scope

1. Add eight typed Parcelable/AIDL contracts for Search, storage accounting, graphics statistics, ContextHub metadata, persistent-data state, system-update state and the aggregate profile.
2. Persist the aggregate profile in Package Service with bounded atomic JSON, CRC validation, corrupt-state quarantine and optimistic versions.
3. Deliver revision-bound Runtime snapshots and observer refresh.
4. Install reversible Search, StorageStats, GraphicsStats, ContextHub, PersistentDataBlock and SystemUpdate service hooks.
5. Project deterministic queries, bound process-local sessions and deny unsupported privileged mutations.
6. Block Guest startup when a configured non-HOST domain lacks its required hook.
7. Preserve the frozen 113-category matrix and keep `ref/upstream` read-only.

## Acceptance

- static Android compilation and all Host regressions pass;
- the six service hooks are registered and readiness-enforced;
- Search does not expose Host components or authorities;
- StorageStats returns deterministic bounded values;
- GraphicsStats and ContextHub ownership are quota-bounded and released;
- PersistentDataBlock and SystemUpdate mutations follow explicit policy and remain process-local source projections;
- profile persistence, isolation, version conflicts and corruption quarantine are tested;
- Android/device evidence remains zero until the locked toolchain and device execution are available.
