# T57-R03-P0B Process / processName / Slot

RESULT: PASS

Production contract unchanged: ordinary=64, isolated=16.

## Changes

- Isolated architecture self-test no longer assumes an 8-slot ordinary pool.
- `ProcessSlotConcurrencySelfTest` covers ordinary 0..63, isolated 0..15,
  exhaustion independence, same-processName sharing, different-processName
  slots, death/generation, and multi-user.

## Hostile vs ordinary isolated

ISOLATED_HOSTILE is an execution profile on an isolated UID worker. It does
not consume a second slot pool. Ordinary isolated Service slots remain the
16-slot isolated SessionRegistry.

## RD

Slot allocator is process-local and is proven by host self-test. Device
exhaustion of 64 guest processes is not required to accept the contract
alignment. Companion32 still initializes.

VA Pro: NOT_PROVEN
