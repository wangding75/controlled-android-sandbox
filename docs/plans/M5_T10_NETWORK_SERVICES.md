# M5-T10 — Connectivity, DNS, Proxy/VPN and Java network-service virtualization

## Goal

Expand repository-owned Java network-service virtualization before Android execution. M5-T10 adds one typed,
Package-Service-owned network-service profile per package and virtual user, revision-authorized Runtime access,
bounded process-local callback/VPN-session ownership and fail-closed framework proxy readiness for
ConnectivityManager, DnsResolver and VpnManager.

The bounded source domains are:

1. Connectivity: active/default/all network projection, NetworkCapabilities, LinkProperties, legacy NetworkInfo,
   metering/background policy, proxy queries and callback ownership.
2. DNS: deterministic resolver servers, search/private-DNS policy, typed A/AAAA records, NXDOMAIN behavior and
   raw-query denial.
3. Proxy: NONE/STATIC/PAC profiles with explicit guest-override policy.
4. VPN: state, always-on/lockdown projection, provisioning/establishment policy and bounded source-side sessions.

## Modes and isolation

Connectivity, DNS, Proxy and VPN use explicit `BLOCKED`, `STATIC` and `HOST` modes:

- `BLOCKED`: reject covered operations or return a neutral value only where the API cannot safely throw.
- `STATIC`: project the persisted virtual profile without querying covered Host network identity.
- `HOST`: explicitly use the Host implementation.

Profiles are keyed by package name and virtual-user ID. Runtime reads remain bound to the immutable package revision
of the active session. Updates use optimistic version checks, survive Package-Service restart and notify active
Runtime sessions. Package/instance deletion removes the matching scope.

Process-local ownership is separate from persistent policy:

- Connectivity callbacks are identity-owned and bounded.
- VPN sessions are identity-owned and bounded.
- Callback/session ownership is released on explicit unregister/stop and Guest-generation teardown.

Default STATIC values use documentation-reserved addresses rather than Host IP, DNS, proxy or VPN state.

## Reference-source boundary

`ref/upstream/VirtualApp/**` and `ref/upstream/NewBlackbox/**` remain read-only. Their ConnectivityManager,
DnsResolver and VPN proxy surfaces are used only for architecture and compatibility-pressure comparison. Product
modules do not import, compile, package or mechanically translate reference implementation classes.

## Validation boundary

Host/static evidence proves typed contracts, validation, deterministic defaults, persistence, virtual-user isolation,
revision-authorized Runtime access, optimistic concurrency, corrupt-state quarantine, callback/session limits,
explicit release and teardown cleanup, static result projection, deterministic DNS/NXDOMAIN, mutation denial, HOST
passthrough and fail-closed launch readiness.

The following remain Android-execution dependent:

- real `IConnectivityManager`, `IDnsResolver`, `IVpnManager` and related hidden Binder signatures across Android/API/OEM versions;
- concrete `Network`, `NetworkCapabilities`, `LinkProperties`, `NetworkInfo` and `ProxyInfo` hidden layouts;
- real callback Binder/PendingIntent parcel shapes, ordering, threading and cancellation;
- Android resolver/netd integration, private DNS, captive portal, validation and per-UID routing;
- `VpnService` consent, TUN file descriptors, always-on/lockdown enforcement and packet routing;
- VPN/network interaction with the existing native policy, SELinux, Doze and OEM network stacks.

M5-T10 stops at this boundary rather than claim Android network compatibility without the locked JDK 17/SDK/NDK
build and Emulator/physical-device evidence.
