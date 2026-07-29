# M4-T17 B2 Development Report — Native Network and Audio

## Result

PASS

## Baseline and commit

- B1 baseline: `cf3cdc105bdde6998acc0457813f5d7cf0c862be`
- Functional commit: `336889ec19b8d9ddba81c8e5d1666cf4f558ef78`
- Device/emulator testing: not performed; device evidence remains zero

## Delivered capabilities

### Native network identity

- IPv4 and IPv6 CIDR policy.
- Fail-closed socket family filtering and connect authorization.
- Forward DNS and reverse DNS interception.
- Virtual hostname and `uname` node name.
- Bounded synthetic `getifaddrs` output containing loopback and one Guest interface.
- Typed `NativeNetworkIdentity` with virtual IPv4/IPv6, interface, proxy and cleartext metadata.
- Host interfaces and Host node identity are not returned to Guest native code.

### Audio capture lifecycle

- Generation-bound native audio capture policy.
- RECORD_AUDIO/AppOps state configures the native gate at Guest startup.
- Permission updates immediately replace native capture authority.
- Revocation invalidates active tokens and attempts best-effort stop of tracked AAudio/NDK MediaRecorder handles.
- Existing Java/Binder audio capability leases continue to invoke stop/release cleanup.
- AAudio and NDK MediaRecorder start/stop symbols are included in Guest-library hook targeting.

## Verification

PASS evidence includes architecture and typed-contract checks, static Android compilation, all Host self-tests, native network identity tests, native audio capture tests, existing filesystem/procfs/loader/hook tests, strict M3 evidence gate and byte-identical reproducible source ZIP comparison.

The unified verification command reached the execution limit after all Native tests completed without failure. The strict M3 and reproducible-package gates were then continued in their original order and passed.

## Limitations

- No Android audio server, VPN, proxy, ConnectivityService or OEM device validation.
- Native symbol availability differs by Android release and vendor libraries.
- OpenSL ES capture has policy coverage through the shared permission boundary but no stable capture-specific PLT symbol is claimed.
- Network Security Config is carried as typed policy metadata; device-specific platform enforcement remains unverified.
