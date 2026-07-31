# M5-T17 VA/NBB comparison

| Domain | Controlled Sandbox after M5-T17 | VirtualApp reference | NewBlackbox reference | Assessment |
|---|---|---|---|---|
| SearchManager | Typed virtual profile, deterministic components/authorities, assist and unsupported paths fail closed | Explicit SearchManager proxy | Search proxy/service surface present | Source gap closed; real searchable objects/device behavior unverified |
| StorageStats | Deterministic total/free/quota and package stats isolated per virtual user | Branch-dependent/limited coverage | Explicit StorageStats proxy | Controlled source policy is explicit; platform parcelables remain unverified |
| GraphicsStats | Deterministic counters and bounded process-local buffer leases | Explicit proxy | Explicit proxy | Source entry point closed; no real ashmem/PFD graphics buffer evidence |
| ContextHub | Typed hub/nanoapp metadata, bounded clients and mutation policy | Explicit proxy in reference surface | Explicit ContextHub proxy | Query/state source gap closed; physical hub and callbacks remain device-only |
| PersistentDataBlock | Bounded read/write/wipe/OEM-unlock projection with fail-closed defaults | Explicit proxy | Explicit proxy | Source policy is safer; no privileged partition/FRP durability claim |
| SystemUpdate | Deterministic query and controlled status submission | Limited modern surface in snapshot | Explicit SystemUpdate proxy | Source entry point closed; no update-engine execution claim |

Hook counts are not compatibility percentages. VA/NBB retain more real-device history. Controlled Sandbox provides stronger typed contracts, per-user persistence and explicit failure closure, while Android build and device evidence remain 0.
