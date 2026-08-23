# C3-T04 Hostile Native Isolation Design

## Scope and evidence boundary

`C3-T04` proves the ordinary-APK `ISOLATED_HOSTILE` boundary: an isolated UID
process plus Broker-only capability, not PLT/GOT interception. RD results are
`RD_BASELINE` on dynamically resolved MuMu `RD测试` (API32). They are not
Android-matrix, OEM, ARM/16KB, commercial-app, or VA Pro equivalence.

Two interpretations remain strictly separate:

- `TRUSTED_COMPAT` (C3-T01/T02/T03): libc/PLT compatibility.
- `ISOLATED_HOSTILE` (this task): kernel UID/SELinux plus process-local
  deny-only seccomp and Broker token ledger. A PLT hit cannot establish this
  mode.

`DIRECT_ALLOWED` / `KERNEL_LIMIT_EXPOSED` are fail-closed observations. They
must not be renamed `PASS`.

## DISCOVER / CLASSIFY

Existing P0A-02/P0A-03 already bind a debug isolated process
(`android:isolatedProcess="true"`), issue opaque Broker tokens, and install a
classic BPF deny filter in the isolated worker. The C3-T04 evidence gap is
that the attack matrix does not yet correlate:

| Surface | Pre-C3-T04 status | Classification |
|---|---|---|
| Isolated UID vs Host UID | Proven on RD for FS | keep and reuse |
| Host-private sentinel via libc/syscall/raw | Kernel deny | keep |
| Broker FS/FD/network grant | Production Broker present | keep |
| Other Guest / CAS core storage | No dedicated case | `TEST_EVIDENCE_GAP` (`KI-R03-044`) |
| Inherited FD scan | Deferred (`NATIVE-ENF-FD-001` was P0A-03 only) | `TEST_EVIDENCE_GAP` |
| `/dev/binder*` | Inventory only (`KI-R03-NATIVE-006`) | observe; residual kernel limit |
| socket/connect | Isolated UID still had `INTERNET` (`KI-R03-NATIVE-ENF-001`) | deny-only seccomp in hostile worker, residual if still allowed |
| ptrace / execve | Not intercepted in TRUSTED_COMPAT (`KI-R03-NATIVE-002`) | hostile seccomp deny-errno |
| clone/fork | Not denied (ART threads need `clone`) | residual same-UID kernel limit |
| capability scope/revision/expiry/replay | registry self-test only | device evidence required |
| death revocation | unbind existed, no PID/token correlation | device evidence required |

`KI-R03-044` records this evidence gap. Architecture gaps
`KI-R03-NATIVE-001/002/006/008` and `KI-R03-NATIVE-ENF-001` remain; C3-T04
must expose them rather than hide them.

## Process and capability model

```text
Host / Broker  (application UID, capability ledger)
    │ Binder + opaque token (session, generation, owner, user, expiry)
    ▼
Isolated worker  (UID 99000-99999)
    │ no ambient host /data, no INTERNET usefulness
    │ hostile seccomp deny: socket/connect/bind/sendto/ptrace/execve
    ▼
kernel UID / SELinux / inherited zygote filter
```

Tokens are issued only after `HostileAdmissionSnapshot` with
`NativeExecutionProfile.ISOLATED_HOSTILE`. The child never sends a host path
or arbitrary endpoint. Broker operations fail closed on
`GENERATION_MISMATCH`, `OWNER_MISMATCH`, `USER_MISMATCH`, `SESSION_MISMATCH`,
`CAPABILITY_EXPIRED`, `CAPABILITY_REVOKED`, and
`HOSTILE_NETWORK_ENDPOINT_NOT_ALLOWLISTED`.

Revocation stops new Broker use. An already-delegated kernel FD remains a
kernel object until the child closes it or dies; the death case must prove
the isolated PID is gone and subsequent Broker replay is denied.

## Attack matrix

| Case ID | Attack | Expected |
|---|---|---|
| `C3-T04-ISO-001` | Isolated UID assigned and distinct from Host | `PASS_ISOLATED` |
| `C3-T04-FS-CORE-001` | libc/syscall/raw open of CAS core storage | `DENIED_BY_KERNEL_POLICY` |
| `C3-T04-FS-GUEST-001` | libc/syscall/raw open of another Guest secret | `DENIED_BY_KERNEL_POLICY` |
| `C3-T04-FS-UNGRANTED-001` | ungranted Host sentinel / path guess / wrong token | `DENIED` |
| `C3-T04-FD-INHERIT-001` | `/proc/self/fd` scan for Host-private paths | `PASS_NO_LEAK` |
| `C3-T04-BINDER-001` | open `/dev/binder` | `DENIED_*` or `KERNEL_LIMIT_EXPOSED` |
| `C3-T04-SOCKET-001` | libc/syscall/raw socket+connect after hostile filter | `DENIED_BY_SECCOMP` preferred; else residual |
| `C3-T04-PTRACE-001` | `ptrace` attach / traceme | `DENIED_BY_SECCOMP` or kernel deny |
| `C3-T04-CLONE-001` | `fork`/`clone` | residual same-UID if allowed; never a UID escape |
| `C3-T04-EXEC-001` | `execve` after hostile filter | `DENIED_BY_SECCOMP` or kernel deny |
| `C3-T04-CAP-GRANT-001` | Broker read of granted sentinel | `PASS_CAPABILITY` |
| `C3-T04-CAP-SCOPE-001` | wrong owner / session / user | `DENIED_BY_BROKER` |
| `C3-T04-CAP-REV-001` | generation mismatch / stale | `DENIED_BY_BROKER` |
| `C3-T04-CAP-EXPIRY-001` | expired token | `DENIED_BY_BROKER` |
| `C3-T04-CAP-REPLAY-001` | use after revoke | `DENIED_BY_BROKER` |
| `C3-T04-DEATH-001` | isolated PID gone; tokens revoked | `PASS_REVOKED` |
| `C3-T04-NET-BROKER-001` | Broker loopback using issued token | `PASS_CAPABILITY` |

Supporting `NATIVE-ENF-*` rows remain in the same JSON for continuity.

## Residual kernel limits (threat-model inputs)

These cannot be closed by an ordinary APK and must stay explicit:

1. Classic BPF is deny/errno only. It is not seccomp user-notify.
2. `clone`/`fork` are not denied because ART/threads need `clone`.
3. `/dev/binder*` is not a Native-mediated driver.
4. Isolated UID still lives in the Host SELinux domain.
5. Delegated FDs are kernel objects until close/death.
6. No true secondary Linux UID, mount namespace, or Binder-driver filter.

C3-T05 owns the user-notify product decision. C3-T04 must not claim that
decision.

## Fixture and runner

- Host debug command `c3-t04-hostile` (alias of the production
  `native-hostile` campaign) binds `NativeEnforcementIsolatedService`.
- JNI `cas_native_enf` records libc/syscall/raw and attack probes. The
  fixture copy of that library is not a hostile-isolation proof.
- Runner `tools/capability/run_c3_t04_rd.py` resolves `RD测试` via
  `scripts/mumu_instance.py`. No historical ADB serial is a device selector.
- Tracked summaries: `verification/catch-up/C3-T04/`.
- Raw ADB/logcat: ignored `artifacts/capability-audit/catch-up-c3-t04/`.

## Acceptance predicates

1. Static C3-T04 gate, hostile-profile gate, and capability registry self-test pass.
2. Isolated UID is in `99000-99999` and distinct from Host.
3. Core storage, other-Guest, and ungranted Host paths are not readable by
   libc/syscall/raw.
4. Granted Broker FS (and issued network token) succeed; stale / owner /
   expiry / replay fail closed.
5. Inherited FD scan reports zero Host-private leaks.
6. ptrace and execve are denied (seccomp or kernel).
7. After unbind, isolated PID is gone and Broker replay is denied.
8. Binder/clone/socket residuals are classified, not silently `PASS`.
9. `va_pro_equivalent` remains `NOT_PROVEN`.
