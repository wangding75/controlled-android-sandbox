# M5-T16 VA/NBB comparison

| Domain | Controlled Sandbox after M5-T16 | VirtualApp reference | NewBlackbox reference | Assessment |
|---|---|---|---|---|
| Runtime IPC | Typed V2 request/result with correlated identity; legacy Bundle adapters retained | Primarily historical Bundle/reflective contracts | Mixed Binder and reflective contracts | Controlled Sandbox source contract is stricter; device proof remains absent |
| RestrictionsManager | Independent hook, virtual application restrictions, mutation fail-closed | `RestrictionStub` present | `RestrictionsManagerStub` present | False coverage closed at source level |
| Method classification | Exact-first matcher plus inverse-operation regressions | Per-proxy method hooks | Per-proxy method hooks | Reduced substring collision risk |
| SearchManager | Not yet implemented | Explicit proxy | Reflected interface available | Remaining source gap |
| StorageStats | Not yet implemented | Limited/branch-dependent | Explicit proxy | Remaining source gap |
| GraphicsStats | Not yet implemented | Explicit proxy | Explicit proxy | Remaining source gap |
| ContextHub | Not yet implemented | Explicit proxy | Explicit proxy | Remaining source gap; physical behavior device-dependent |
| PersistentDataBlock | Not yet implemented | Explicit proxy | Explicit proxy | Remaining source gap; privileged storage device-dependent |
| SystemUpdate | Not yet implemented | No broad modern implementation in snapshot | Explicit proxy | Remaining source gap |

Hook counts are not compatibility percentages. VA/NBB retain substantially more real-device experience. Controlled Sandbox has stronger typed-contract and fail-closed evidence, while device evidence remains 0.
