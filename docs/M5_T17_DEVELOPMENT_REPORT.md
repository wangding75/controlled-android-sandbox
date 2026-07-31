# M5-T17 development report

## Result

- Source status: PASS
- Production status: PARTIAL
- Android build: BLOCKED by the unavailable locked JDK 17 in the current environment
- Device evidence: 0

## Delivered

1. Added eight typed Parcelable/AIDL contracts for Search, StorageStats, GraphicsStats, ContextHub, PersistentDataBlock, SystemUpdate and their aggregate profile.
2. Added Package-Service-owned per-package/per-virtual-user persistence with bounded JSON, CRC, atomic replacement, corrupt-state quarantine and optimistic version conflict handling.
3. Added management get/set/reset APIs, revision-bound Runtime delivery and observer hot refresh.
4. Added reversible service hooks for `search`, `storagestats`, `graphicsstats`, `contexthub`, `persistent_data_block` and `system_update`.
5. Added deterministic Search component/authority projection and fail-closed assist/searchable behavior.
6. Added deterministic total/free/cache/app/data/external storage accounting without reading Host storage identity.
7. Added bounded graphics-buffer and ContextHub-client ownership, deterministic statistics/hub metadata and explicit mutation denials.
8. Added policy-controlled PersistentDataBlock read/write/wipe/OEM-unlock projection with bounded process-local data.
9. Added deterministic SystemUpdate query and policy-controlled process-local status submission.
10. Added fail-closed Guest readiness and three dedicated Host regression suites.
11. Preserved the frozen 113-category matrix and modified no file under `ref/upstream`.

## Honest boundary

M5-T17 closes the six service candidates identified by M5-T16 at source level. It does not prove Android hidden object layouts or Binder signatures. Real GraphicsStats buffers/file descriptors, ContextHub callbacks and nanoapp execution, privileged persistent storage durability, FRP/OEM unlock behavior and the platform system-update engine remain Android-build/device work. Process-local mutation overlays are not reported as durable platform execution.
