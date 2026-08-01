# M5-T19.1-B Development Report — Native Network Buffer and FD Correctness

## Scope

- Baseline commit: `e33afe3437a6f47c2338735fb84d92c17c54b7c2`.
- Baseline task: M5-T19.1-A Native Guest trust boundary.
- Branch: `fix/m5-t19-1-native-network-correctness`.
- Review finding: P1-02, rejected Native socket calls could expose kernel-written payload/address data and the imported-symbol hook set omitted common socket and descriptor aliases.
- Capability matrix expansion: none.
- `ref/upstream` changes: none.

## Disposition

The finding is closed for hook-mediated calls made by an explicitly trusted Native Guest. Receive and peer/local-address wrappers no longer pass Guest-owned output buffers to libc before policy approval. Datagram source policy is checked with a bounded `MSG_PEEK` probe while a per-socket receive lock shared by all `dup` aliases is held; a denied datagram remains queued. Stream peer policy is checked before bytes are read. The actual receive is performed into bounded temporary payload, address and control buffers and copied to Guest memory only after the source is allowed.

This remains a PLT/GOT compatibility boundary. A direct syscall, inline assembly or a concurrent custom loader can bypass the wrapper and its receive lock. M5-T19.1-A therefore continues to deny untrusted Native Guests by default.

## Implementation

### Output-buffer isolation

- `controlled_recvfrom`, `controlled_recv` and socket-backed `controlled_read` use temporary payload and source-address buffers.
- `controlled_recvmsg` uses temporary iovec, address and ancillary-control storage.
- `controlled_getpeername` and `controlled_getsockname` use temporary address storage and preserve caller buffers on policy denial.
- `SCM_RIGHTS` descriptors received into a temporary control buffer are closed when a post-receive policy rejection occurs.
- Temporary receive payload is bounded to 8 MiB, ancillary control to 1 MiB and iovec count to 1,024. Oversized requests fail before consuming data.

### Non-consumption contract

- Tracked sockets use one receive mutex shared by every duplicated descriptor; untracked compatibility descriptors fall back to one of 64 deterministic lock stripes.
- Message-oriented sockets are inspected with `MSG_PEEK` while that mutex is held.
- A source rejected during the probe returns `EACCES` without consuming the datagram and without changing payload, address, length, flags or control outputs.
- Stream peers are validated with a temporary `getpeername` before the receive call, so rejected stream bytes remain queued.
- `accept` and `accept4` necessarily remove a connection from the kernel backlog before its peer can be inspected. A rejected accepted descriptor is closed immediately and no peer address is copied to Guest memory. This backlog-consumption limitation is explicit.

### Socket API coverage

The Guest import replacement set now includes:

- `send`, `sendto`, `sendmsg`;
- `recv`, `recvfrom`, `recvmsg`;
- socket-backed `read` and `write`;
- `accept` and `accept4`;
- `dup`, `dup2`, `dup3`, `fcntl` and `fcntl64` duplication paths.

Connected sends validate the current peer before emitting bytes. Named sends validate the destination. Accepted and duplicated descriptors inherit or clear the bounded socket registry deterministically. If a socket duplicate cannot be registered within the bounded table, the new descriptor is closed and the call fails with `EMFILE`; a usable but untracked socket alias is never returned.

### Architecture

Network/descriptor wrappers are implemented in the dedicated `native_network_interceptors.cpp` translation unit. `native_interceptors.cpp` remains the file/loader/audio adapter and symbol dispatch owner. Both 64-bit Host and 32-bit Companion CMake targets compile the new unit.

## Regression evidence

`native_network_interceptors_self_test.cpp` uses real UDP and TCP sockets and verifies:

- denied `recvfrom` leaves payload/address/length unchanged and the datagram queued;
- denied `recvmsg` leaves iovec and message metadata unchanged and the datagram queued;
- allowed short payload/address buffers preserve truncation and full-address-length semantics;
- two concurrent hook-mediated UDP receives through different `dup` aliases are serialized and return distinct datagrams;
- denied `send`, `sendmsg`, `sendto` and socket-backed `write` emit no bytes;
- denied `recv`, `recvmsg` and socket-backed `read` leave stream bytes queued;
- denied `getpeername` and `accept` outputs remain unchanged;
- allowed `send/recv/sendmsg/recvmsg/read/write/sendto/accept/accept4` paths work;
- `dup/dup2/dup3/F_DUPFD/F_DUPFD_CLOEXEC` preserve socket tracking;
- duplicating a non-socket over a tracked descriptor clears stale socket state;
- hooked close removes old policy state before Linux can recycle the descriptor number.

The same test passes with AddressSanitizer and UndefinedBehaviorSanitizer in the Host environment.

## Verification

- M5-T19.1-B dedicated source/test ownership gate: PASS.
- Existing M4-T17 Native network/audio gate: PASS after ownership was updated to the split adapter.
- Existing M5-T4 Native diagnostics gate: PASS.
- Native ABI Companion architecture gate: PASS.
- Native main suite and compile-only JNI/Companion checks: PASS.
- Android Gradle/APK build, Emulator and physical-device evidence: not produced.

## Residual risk

- Direct syscalls and inline assembly bypass these wrappers.
- A process concurrently consuming the same socket through a direct syscall is outside the per-wrapper receive lock. Hook-mediated calls are race-safe with each other.
- Rejected `accept/accept4` calls consume and close the accepted kernel connection because Linux does not expose a pre-accept peer-policy primitive.
- `readv`, `writev`, `recvmmsg`, `sendmmsg`, `splice`, `sendfile` and io_uring socket operations are not added in this task. Untrusted Native execution remains denied by M5-T19.1-A; future compatibility expansion must add each alias with direct regression evidence.
- Android Bionic/linker and real device behavior remain unverified.

## Status

- Source fix: PASS.
- Hook-mediated buffer non-disclosure: PASS.
- Hook-mediated denied datagram non-consumption: PASS.
- Common socket/FD alias coverage requested by P1-02: PASS.
- Strong hostile-Native isolation: not claimed.
- Production/device status: PARTIAL, device evidence 0.
