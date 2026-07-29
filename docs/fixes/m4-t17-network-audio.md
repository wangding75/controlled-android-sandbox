# M4-T17 B2 — Native network identity and audio capture

## Network boundary

Guest native imports for socket creation, connect, forward/reverse DNS, hostname, uname and interface enumeration are intercepted. IPv4 and IPv6 CIDR policy is fail-closed. A bounded typed network identity supplies a virtual hostname, one virtual interface, virtual IPv4/IPv6 addresses, proxy metadata and cleartext-policy metadata. `getifaddrs` returns only loopback and the virtual interface, so Host interfaces and addresses are not exposed.

## Audio boundary

The existing Java/Binder audio service hook remains the authoritative Binder path. M4-T17 B2 adds a generation-bound native capture policy and interceptors for AAudio and NDK MediaRecorder start/stop symbols when a Guest library imports them. RECORD_AUDIO/AppOps changes update this policy. Revocation invalidates native capture tokens, attempts best-effort stop of tracked native handles, and the existing capability lease registry invokes Binder cleanup methods.

## Evidence limit

Host-native tests verify policy, token revocation and synthetic network identity. Android audio-server symbols, OpenSL implementation details, VPN/Connectivity projection and OEM behavior are not device-proven in this iteration.
