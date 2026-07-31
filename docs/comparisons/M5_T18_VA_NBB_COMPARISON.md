# M5-T18 VA/NBB architecture-quality comparison

| Domain | Controlled Sandbox after M5-T18 | VirtualApp reference | NewBlackbox reference | Assessment |
|---|---|---|---|---|
| Binder client recovery | Shared typed connector with death handling, binding-died handling, retry/backoff and close semantics across five client classes | Mature service-fetch/recovery patterns, branch-dependent | Mature proxy/service lookup patterns, branch-dependent | Controlled source model is explicit and tested; Android timing remains unverified |
| Runtime IPC | Typed V2 request/result is the repository-owned primary route; twelve Bundle methods remain compatibility-only | Historically Bundle-heavy interfaces | Mix of typed and generic proxy transactions | Controlled contract discipline is stronger, but compatibility debt remains |
| Account secret persistence | AES-GCM schema-6 storage, migration and corruption quarantine | Snapshot-dependent; no equivalent evidence assumed | Snapshot-dependent; no equivalent evidence assumed | Controlled source at-rest policy is explicit; Android Keystore is still deferred |
| Large-class ownership | Account authority extracted; fourteen classes remain above 500 lines and are disclosed | Large manager/service classes are common | Large proxy/server classes are common | Partial improvement only; no claim of completed decomposition |
| Method dispatch safety | Exact-first matcher and direct inverse-name regressions | Extensive method-proxy classes reduce some string dispatch | Extensive proxy classes, but breadth increases maintenance surface | Controlled gates now catch known collisions; a complete semantic dispatch rewrite is not finished |
| CI/source evidence | Locked JDK-17 source workflow plus local full gates and reproducible artifacts | Historical project CI varies by snapshot | Snapshot/build evidence varies | Workflow definition is stronger evidence discipline, but it has not run remotely here |

Hook count and line count are not compatibility percentages. VA/NBB retain substantially more real-device history. Controlled Sandbox has clearer typed contracts, fail-closed behavior and source evidence, while Android build and device evidence remains 0.
