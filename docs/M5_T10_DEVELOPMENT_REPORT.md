# M5-T10 development report

## Result

- Source status: PASS
- Production status: PARTIAL — typed policy, Binder authority, persistence, common Java query projection,
  callback/session ownership and proxy-readiness are source-wired; real Android network objects, resolver/netd and
  TUN/routing behavior remain build/device-dependent
- Android build: BLOCKED by the unavailable locked JDK 17 and Android SDK/NDK in the current environment
- Device evidence: 0

## Delivered

1. Added seven typed Parcelable/AIDL contracts for network descriptors, DNS records/profile, Connectivity,
   Proxy, VPN and the aggregate network-service profile.
2. Added deterministic defaults using documentation-reserved addresses; default profiles do not copy Host IP,
   DNS, proxy or VPN identity.
3. Added bounded atomic persistence keyed by package and virtual user, with Runtime access authorized against the
   active immutable package revision. Persistence includes size limits, CRC verification, atomic replacement,
   corrupt-file quarantine and optimistic version checks.
4. Added typed management get/set/reset methods, Runtime-session retrieval and asynchronous observer invalidation.
5. Added process-local Connectivity callback and VPN-session ownership ledgers with limits, explicit release and
   Guest-generation cleanup.
6. Added ConnectivityManager source mediation for active/default/all networks, capabilities, link properties,
   legacy network info, metering/background status, proxy queries and network callback registration/unregistration.
7. Added deterministic DNS source mediation for configured A/AAAA records, NXDOMAIN, resolver-server queries and
   raw-query/mutation denial.
8. Added Proxy NONE/STATIC/PAC projection with explicit guest-override policy.
9. Added VPN source mediation for state, always-on/lockdown queries, provisioning/establishment policy, bounded
   source-side sessions and mutation denial.
10. Added fail-closed Guest launch readiness for configured non-HOST Connectivity, DNS/Proxy and VPN domains.
11. Added Host regressions for persistence/reload/user isolation/version conflict/corrupt quarantine, network object
    projection, callback limit/release, static proxy, deterministic DNS/NXDOMAIN, raw-query denial, VPN session
    limit/release, HOST passthrough and readiness failures.
12. Fixed callback-unregistration dispatch ordering so `unregisterNetworkCallback` cannot be mistaken for a
    registration operation.
13. Preserved the frozen 113-category capability matrix; M5-T10 evidence is recorded separately and creates no
    device evidence.

## Reference review

The read-only VA/NBB snapshots were reviewed for ConnectivityManager, DnsResolver and VPN proxy architecture. No
reference source was imported into product modules. Controlled Sandbox uses its own typed policy, Package-Service
authority, revision-authorized Runtime access and bounded process-local ownership.

## Deferred to Android execution

- Android-version-specific service singleton fields and hidden Binder signatures;
- real hidden framework object construction and field compatibility;
- callback Binder/PendingIntent delivery and cancellation ordering;
- DNS resolver/netd/private-DNS/captive-portal behavior;
- VPN consent, TUN descriptors, packet routing, always-on/lockdown enforcement and native-network interaction;
- hidden API, SELinux, Doze and OEM network-stack compatibility/stability claims.
