# B3-T3E-3 URI Grant lifecycle hardening

This stage strengthens Provider URI grants without relying on an Emulator or physical device.

## Implemented

- Every grant is bound to the Provider owner instance, owner Session/generation, target caller instance, target Session/generation and one virtual-user namespace.
- Cross-virtual-user grants are rejected.
- Grants accept only normalized `content://` URIs and read/write flag combinations.
- TTL is mandatory, bounded to 24 hours and purged before allocation, lookup, authorization and status reporting.
- The Broker enforces a global maximum of 256 active grants.
- Persistent grants remain active until expiry, explicit revoke, Session death or instance stop.
- One-time grants are authorized through a two-step preview/commit scope. Commit revalidates all requested URIs atomically and consumes matching one-time grants once.
- A one-time prefix grant may authorize one top-level batch request containing multiple matching operation URIs, but concurrent top-level requests have exactly one winner.
- Explicit revoke validates the Provider owner instance and the original owner Session/generation.
- Provider owner recovery, caller recovery, explicit Session stop, Binder disconnect and virtual-instance stop remove affected grants.
- Cursor and file leases retain the authorization fixed when the lease is created. Grant revocation blocks new leases; existing leases remain bounded by their own TTL and Session/generation lifecycle. Already delivered file-descriptor copies remain caller-owned Android capabilities.

## Tests

- Persistent and one-time authorization.
- Batch reuse of a one-time prefix grant within one top-level transaction.
- One-time replay denial.
- Sixteen-thread concurrent one-time consumption with exactly one winner.
- Owner Session validation on revoke.
- Owner and target Session cleanup.
- TTL expiry and 256-grant capacity fail-closed behavior.
- Provider routing integration through `BrokerProviderRuntime`.

## Deferred device evidence

Android platform URI permission APIs, `ContentResolver`, Binder identity propagation, OEM behavior and third-party Provider compatibility remain `not-tested` under the user-approved device-test deferral.
