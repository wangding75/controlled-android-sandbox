# B3-T3D Provider FileDescriptor and AssetFile leases

Date: 2026-07-26

## Scope

This stage adds locally verifiable Provider file transport without claiming Emulator or device compatibility.

- `openFile`, `openAssetFile` and `openTypedAssetFile` are explicit Provider operations.
- File modes are restricted to `r`, `w`, `wt`, `wa`, `rw` and `rwt`; URI permission flags are derived from the selected mode.
- Broker routing resolves the registered Authority owner and applies the same Caller Session/generation, virtual-user, exported and URI-grant checks as Provider CRUD operations.
- The Broker issues every file token before the Guest opens a descriptor. Guest-defined or mismatched tokens fail closed.
- Guest and Broker each retain a resource lease. Open failure, result validation failure, expiry, explicit close, Session death, instance removal and Runtime shutdown close the retained resources.
- Descriptor kind, mode, MIME type, start offset and declared length are cross-checked before the Broker commits a lease.
- Broker and Guest each enforce a 64-lease capacity and a 120-second default TTL.
- Close is single-winner and replay-protected by lease removal.
- The Fixture Provider implements all three file operations for later device-gated validation.

## Android semantics boundary

A descriptor already delivered through Binder is an OS-duplicated caller-owned file descriptor. Broker/Guest lease cleanup closes their retained copies; the caller remains responsible for closing the descriptor it received. This source stage does not claim remote revocation of an already delivered descriptor.

## Local evidence

- `GuestProviderFileTransportSelfTest` covers open/close, typed assets, mode rejection, owner checks, expiry, capacity and shutdown cleanup.
- `BrokerFileRuntimeSelfTest` covers Broker-issued tokens, descriptor metadata validation, Session binding, close replay, 16-thread single-winner close, expiry, invalidation and capacity.
- `BrokerProviderRuntimeSelfTest` covers file-mode permission classification, URI Grant routing, MIME requirements and operation allow-list behavior.
- Repository-wide `./scripts/verify-all.sh` remains the stage gate.

## Explicitly skipped

Per user instruction, this stage does not claim Android Binder descriptor duplication, ContentResolver, Emulator, device, OEM ROM or third-party App compatibility. Device evidence remains `not-tested`.
