# M5-T19.1-S Durable Atomic Persistence

## Scope

This stage closes the P3 finding that files described as atomic were not durable across a crash because parent directory entries were not synchronized, and that unsupported atomic moves silently degraded to ordinary replacement.

## Contract

Every strict state publication uses this order:

1. Write a temporary sibling file.
2. Flush and `fsync` the temporary file.
3. Replace the destination with `ATOMIC_MOVE` in the same filesystem.
4. `fsync` the destination parent directory.
5. For moves across two directories, `fsync` both directory entries.

If the filesystem cannot provide atomic move or parent-directory synchronization, publication fails with a specific error. It does not use a non-atomic fallback.

## Coverage

The shared boundary is used by Host service stores, install-session state, account-key state, domain recoverable state, Guest storage registry and preferences, Activity task checkpoints, Guest storage moves, package publication, and Companion32 artifact publication.

## Device boundary

Host tests prove ordering and failure behavior. Android filesystem and SELinux support for opening directory descriptors remains a device-test requirement. Unsupported devices fail closed instead of claiming durable atomic publication.
