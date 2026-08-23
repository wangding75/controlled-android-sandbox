# C3-T06 ADR: ART/Xposed Compatibility Extension is out of product scope

Status: `NOT_APPLICABLE`
Date: 2026-08-23
Depends: C2 (F1-F5 adapters), C3-T04 isolated-hostile boundary
Maturity: `RD_BASELINE`
VA Pro equivalent: `NOT_PROVEN`

## Product question

C3-T06 must answer one question before any engine work:

1. Does CAS have to load **arbitrary third-party Xposed modules**?
2. Or does the product only need F2-F5 / known compatibility behavior?

The catch-up plan, SX inventory, and XH split already answered **(2)**.

| Product lane | What it actually is | Xposed host required? |
|---|---|---|
| CAS generic F1-F5 | Framework / Binder / native adapters (C2) | No |
| SX Host migration | BlackBox+Pine is legacy; CAS-only Host is the target | No |
| Original XH `com.xin.h6` | Custom VA Host, not a module | No |
| `xh/spoofer_project` | Independent LSPosed boilerplate | Only if that SKU is explicitly ordered |

This campaign does **not** order the `spoofer_project` module SKU. C5-T04 remains
the later optional module-hosting task and inherits this ADR unless a new
product decision supersedes it.

## Decision

Do **not** build an ART/Xposed/Pine Compatibility Extension in CAS core:

- no module classloader
- no `xposed_init` production bootstrap
- no generic `XposedBridge` / `XposedHelpers` runtime
- no Pine/SandHook/Dobby as a product Hook engine

Continue F2-F5 through existing framework service virtualization. SX/XH Host
migration (C4/C5) must not reintroduce a production Xposed engine.

## Threat and authority

A module host would sit beside Guest code with method-rewrite power. That is
a new trusted computing base.

Hard rules if a later SKU ever enables it (not this task):

- default off, exact package/user/revision scope
- engine must **not** receive PackageService or RuntimeBroker root
- hook/unhook, generation cleanup, exception isolation, resource quotas
- proof is a Guest method callback, not “service started”
- isolated-hostile guests remain C3-T04 Broker-only; modules cannot widen that

Those rules are recorded so they are not invented later. They are **not** an
implementation plan for this campaign.

## Source and license

VA/NBB/Pine/Xposed trees are behavior references only. They are not a CAS
implementation spec.

Local SX Pine tree is **not** copyable: no root LICENSE was found; mixed
Anti-996, GPLv2-classpath, and Apache notices appear. “Apache-2.0 clean-room”
cannot be claimed from that tree.

CAS production modules therefore stay free of `de.robv.android.xposed`,
`XposedBridge`, `PineXposed`, and `assets/xposed_init` runtime bootstrap.

## Alternatives

| Option | Use now? |
|---|---|
| Framework/Binder/native F1-F5 adapters | **Yes — current product** |
| Controlled Xposed module host | No, unless `spoofer_project` is later in scope |
| Copy SX Pine / BlackBox Xposed | Forbidden (license + dual-engine) |
| Treat VA README “Xposed support” as CAS evidence | Forbidden |

## Production impact

- C3 stage condition task closes as `NOT_APPLICABLE`.
- C4 SX migration deletes, rather than ports, Pine/Xposed production runtime.
- C5 original XH is a CAS Host product, not a module loader.
- C5-T04 may later record `NOT_APPLICABLE` by reference to this ADR, or
  implement a scoped host only after an explicit product change.

This ADR does not claim VA Pro Xposed-changelog equivalence.
