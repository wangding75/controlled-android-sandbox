# M5-T19.1-E development report

## Scope

This task fixes P1-05, unbounded AIDL collection transactions, and P2-11, account enumeration carrying
passwords and authentication tokens. It does not add a new system-service hook or expand the frozen
113-capability matrix.

## Source changes

- Added `VirtualPageRequest(maxItems,maxBytes,pageToken)` with 128-item and 256-KiB hard ceilings.
- Added nine typed page result contracts with `items`, `VirtualPageBlob` descriptors,
  `nextPageToken` and `snapshotRevision`. New AIDL methods are appended after the complete legacy
  method sequence so existing transaction IDs remain unchanged.
- Added HMAC-protected continuation tokens bound to collection/query, capability scope, revision,
  offset and monotonic expiry.
- Added simultaneous item-count and serialized-byte enforcement.
- Kept old `list*` methods as 32-item/128-KiB complete-or-fail adapters using `PAGING_REQUIRED`.
- Added 64-KiB binary offload through bounded, expiring, session-scoped, one-time read-only
  `ParcelFileDescriptor` grants. Active handles are never silently evicted; pages stop and resume at
  the 64-grant window.
- Added `VirtualAccountSummary`; account list/page paths no longer serialize password or token fields.
- Migrated the repository-owned Runtime client to transparent paging and binary rehydration.

## Verification

- Source fix: PASS
- Direct paging/token/budget regression: PASS
- Large binary `ParcelFileDescriptor` round trip and replay rejection: PASS
- 64-grant page-window continuation without silent handle eviction: PASS
- Credential-free Account enumeration regression: PASS
- Legacy compatibility rejection regression: PASS
- Static Android-source compile and Host regression suite: PASS
- Existing M5-T19 architecture line threshold: PASS; no threshold was raised
- Android generated-AIDL build evidence: 0
- Android Binder-driver evidence: 0
- Emulator/physical-device evidence: 0

## Remaining boundary

The Host source implementation reserves a 256-KiB maximum page budget and the Runtime requests
224 KiB. Real parcel overhead and OEM Binder limits still require Android build and device evidence.
The scoped PFD path is source-tested with the local Android API stub; platform descriptor ownership,
SELinux and process-death behavior remain device-gated.
