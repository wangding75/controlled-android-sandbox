# M5-T10 comparison — Controlled Sandbox vs VirtualApp and NewBlackbox

This comparison uses the vendored read-only snapshots under `ref/upstream`. It compares source architecture and
coverage only. Historical VA/NBB execution does not become evidence for Controlled Sandbox.

| Area | VirtualApp reference | NewBlackbox reference | Controlled Sandbox M5-T10 |
|---|---|---|---|
| Policy ownership | Proxy-centric state and virtual-core/network rewriting | Proxy-centric state varies by fork | Typed aggregate per package + virtual user + immutable revision, owned by Package Service |
| Connectivity | `ConnectivityStub` rewrites identity and proxies common manager/Binder calls | `IConnectivityManagerProxy` covers broader modern API methods | Active/all networks, capabilities/link properties, legacy info, metering/background, proxy and callback ownership |
| DNS | Mostly inherited Host resolver behavior or branch-specific mediation | Dedicated `IDnsResolverProxy` in relevant branches | Deterministic A/AAAA records, resolver configuration, NXDOMAIN and raw-query denial |
| Proxy | Connectivity-path projection, branch-dependent | Connectivity/proxy paths vary by fork | Typed NONE/STATIC/PAC profile with explicit override policy |
| VPN | Limited/branch-specific policy and identity mediation | Broader VPN/service proxy surfaces in some forks | Typed state/always-on/lockdown projection and bounded source-side session policy; no TUN claim |
| Java/native boundary | Legacy Java proxy plus project native hooks | Java service proxies plus fork-specific native layers | Explicit Java network profile coordinated with existing native policy; real routing integration deferred |
| State cleanup | Established through process/client lifecycle | Fork-specific Binder/proxy cleanup | Explicit generation-scoped callback and VPN-session ownership ledgers |
| Failure behavior | Historical compatibility often favors passthrough | Fork behavior varies | Explicit `BLOCKED`/`STATIC`/`HOST`; missing required proxy blocks Guest launch |
| Persistence safety | Legacy/project-specific | Fork-specific | Bounded atomic state, CRC, quarantine, optimistic versioning and revision cleanup |
| Android/OEM evidence | Strong historical use but old-version constraints | Broader recent service hook set; fork quality varies | Device evidence remains 0 |

## Current comparative judgment

- M5-T10 closes the repository-owned source-architecture gap for common Java Connectivity, DNS, Proxy and VPN
  policy/query paths.
- Controlled Sandbox is stronger in typed policy, virtual-user isolation, revision-authorized access, deterministic
  non-Host defaults, explicit limits and source/device evidence separation.
- VA/NBB remain stronger in accumulated Android-version/OEM Binder signatures, callback parcel compatibility,
  netd/resolver/VPN integration and real application evidence.
- Source wiring does not establish parity for per-UID routing, private DNS, captive-portal validation, TUN packet
  transport, always-on lockdown enforcement or OEM network stacks.
