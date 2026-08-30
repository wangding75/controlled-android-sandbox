# C4-TEMP-01 / 2026-08-30 first-failure classification

## Decision

`C4-TEMP-01` is `BLOCKED`, not `DONE`. The dynamic Quark sandbox gate failed twice in separate
one-shot lanes while the runner preserved the first failure and used zero retry budget. The task
cannot close and C4-R05 cannot start.

## Boundaries verified

1. Import and revision handling are not the observed gate failure. Import-only completed, broker
   revision proof was accepted once, native extraction and catalog publication completed, and no
   staging or in-flight transaction residue was used as a reason to retry.
2. Generic framework readiness is not universally broken. The package-neutral fixture produced
   `GUEST_READY`, `ACTIVITY_RESUMED`, and `FIRST_FRAME_DRAWN` on both cold and hot observations,
   with non-black screenshots and normal Window/Surface/ViewRoot evidence.
3. The direct Quark process path is healthy for the sampled cold launches: 3/3 passed in each
   dynamic lane. This does not substitute for sandbox acceptance.
4. The remaining boundary is the sandbox host Stub/guest logical Activity and nested
   `com.ucpro.BrowserActivity` handoff. The Window/Surface state and guest readiness state do not
   reach an accepted first frame within the fixed 30-second cold deadline. The evidence does not
   uniquely distinguish a generic CAS readiness defect from a Quark/app-SDK/Guest interaction, so
   the root cause remains `NEEDS_REPRODUCTION_AND_CLASSIFICATION` / `待验证`.

## VA/NBB comparison

The comparison used the C4-R01 VA/NBB mapping, C4-R03 readiness contract, and C4-R05
ActivityThread/token evidence. The reference contract is: the framework owns the ordinary
ActivityThread/WindowSession/IWindow lifecycle and a successful `FIRST_FRAME_DRAWN` must be tied to
the current package, user, revision, request and target Window. CAS additionally owns broker
revision verification, guest process slot, host Stub, logical Activity identity and nested task/
token correlation. The current trace reaches the latter mapping but remains short of a visible
drawn Window. No second post-resume `addView` was introduced because the diagnostic state already
shows a registered Window/ViewRoot boundary and a duplicate add would be an unproven behavior
change.

## Retry and acceptance discipline

- First lane: request `cc25809c3091482c849f7fa231f26230`, operation
  `cc25809c3091482c849f7fa231f26230-launch`, `attempt=1`, `retryBudget=0`, no automatic retry,
  `93515 ms`, black screenshot.
- Independent lane: request `ab1ecc26cc1e4511919dc45482794de6`, operation
  `ab1ecc26cc1e4511919dc45482794de6-launch`, `attempt=1`, `retryBudget=0`,
  `automaticRetryPerformed=false`, `91889 ms`, black screenshot.
- The independent lane was run to validate the read-only ViewRoot diagnostic, not to turn the
  first failure into a pass. No fixed sleep, timeout expansion, static marker or late frame was
  accepted.

## Changes in this window

- `2bb1f2864bc2b556fa5987bff2c69445be0a0862`: one-time post-resume Window state logging.
- `ffef74c31edcb4497a51bf18b9e6a869d6593e53`: one-time reflective ViewRoot lifecycle sampling.
- Both commits are diagnostic-only. They do not alter readiness deadlines, retry behavior,
  visibility, addView behavior, package routing or error classification.

## Recovery condition

Reopen this task only after a bounded CAS-versus-App/SDK/Guest ownership fix or classification is
available and the dynamic Quark sandbox reaches real `FIRST_FRAME_DRAWN` with non-black screenshot,
valid Window/Surface and matching package/user/revision/request identity. Then rerun the task's
dynamic direct/sandbox and fixture gates on one clean commit. Until then, C4-R05 remains blocked.
