# Capability Campaign Workflow

T57-R03 Capability Campaign Development Model.

## Maturity

| Level | Name | Meaning |
|---|---|---|
| 1 | `RD_BASELINE` | MuMu instance `RD测试` is the lowest dynamic bar |
| 2 | `ANDROID_MATRIX` | API 33-36 executed |
| 3 | `OEM_COMMERCIAL_MATRIX` | OEM and commercial apps executed |

RD测试 PASS is only `RD_BASELINE`. It is not Android-matrix PASS, OEM PASS, commercial-app PASS, VA Pro Equivalent, or hostile-native-boundary PASS.

## Forbidden loop

Do not:

1. Discover one problem
2. Immediately patch
3. Full build
4. RD retest
5. Discover the next problem
6. Patch again

## Required loop

1. `DISCOVER` — collect-all / RD campaign / review. Do not edit production runtime while discovering.
2. `CLASSIFY` — record in `docs/review/KNOWN_ISSUES.yaml` using the fixed classification enum.
3. `DESIGN` — write the smallest design that addresses a classified batch.
4. `IMPLEMENT_BATCH` — implement the batch only after classification and design.
5. `LOCAL_VERIFY` — targeted static/schema/self-test of the batch.
6. `RD_CAMPAIGN` — resolve `RD测试` dynamically and run the campaign runner.

## Diagnostic-only collect-all

`python tools/capability/run_local_capability_audit.py --all`

Rules:

- Continue after a required gate FAIL.
- Exit non-zero if any required gate FAILs.
- Do not modify source, tests, gates, formatting, files, or the Git index.
- Classify `PASS`, `KNOWN_ISSUE`, `EXPECTED_WARNING`, `NEW_REGRESSION`, `UNVERIFIED`.

## RD environment

Every session must re-resolve MuMu instance name `RD测试`.

Never hard-code `127.0.0.1:16416`, `127.0.0.1:16384`, or `127.0.0.1:7555`.

If the instance cannot be uniquely mapped: `RD_ENVIRONMENT_RESOLUTION_BLOCKED`.

## Registries

- `docs/capability/CAPABILITY_REGISTRY.yaml`
- `docs/review/KNOWN_ISSUES.yaml`
- `docs/capability/VA_PRO_COMPATIBILITY_CORPUS.yaml`

VA commercial changelog rows are compatibility signals, not CAS implementation evidence.

## Next recommended campaign

`T57-R03-P0A Native Boundary Architecture & Adversarial Fixture`
