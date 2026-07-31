# M5-T19 comparison with VirtualApp and NewBlackbox

## Scope

M5-T19 is an architecture-quality iteration. It does not claim additional service compatibility. VirtualApp and NewBlackbox sources under `ref/upstream` remain read-only references and were not modified or compiled into the product.

| Architecture area | Controlled Sandbox M5-T19 | VirtualApp reference | NewBlackbox reference | Assessment |
|---|---|---|---|---|
| Runtime IPC | Typed request/result only at Broker and Guest AIDL boundaries; versioned and correlated | Historical Bundle and reflective contracts are common | Mixed Binder, Bundle and reflective contracts | Controlled Sandbox has the stricter source contract; device proof remains absent |
| Package service ownership | Thin Android Service plus independent capability sessions/authorities | Large historical service implementations | Multiple large service/proxy owners | Controlled Sandbox source boundaries are clearer after M5-T19 |
| Activity/Task state | Ledger plus separate checkpoint/rollback coordinator | Mature platform adaptation | Broad Activity/ATMS adaptation | Controlled Sandbox improves maintainability but still lacks platform execution evidence |
| Guest binding | Dedicated connection pool owns binding and Binder death | Mature process management | Mature process management | Controlled Sandbox source lifecycle is explicit; real process-death timing is unverified |
| Method routing | Central exact/prefix/fragment matcher with direct substring gate | Extensive method-name proxy tables | Extensive proxy classes and signatures | Controlled Sandbox reduces collision risk; VA/NBB retain broader device experience |
| Test governance | Twelve critical owners mapped to executed Host regressions; no false coverage percentage | Project/version dependent | Fork/version dependent | Controlled Sandbox evidence discipline is stronger, but real Android coverage is still zero |
| Real Android compatibility | Not built or executed | Extensive historical evidence | Extensive historical evidence | VA/NBB remain materially ahead |

## Conclusion

M5-T19 improves source quality and removes the last known top-level weakly typed runtime interface. It does not narrow the real-device evidence gap. Controlled Sandbox can reasonably claim a stronger clean-room, type and failure-closure baseline; it cannot claim VA/NBB-equivalent compatibility until M6 build, Emulator and physical-device evidence exists.
