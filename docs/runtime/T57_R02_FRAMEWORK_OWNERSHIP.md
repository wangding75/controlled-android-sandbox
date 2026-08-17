# T57-R02 Framework Ownership

> Historical / Superseded. This file records the earlier `HOST_TRAMPOLINE_MANUAL_LIFECYCLE` / `MANUAL_COMPONENT_RUNTIME` ownership bar. It is not the current T57 source conclusion.
>
> Current T57 status: normal Activity/Service paths are framework-owned (`ActivityThread`, `LoadedApk`, `AppComponentFactory`, Service record/token). PendingIntent `IIntentSender` is brokered and has RD API32 transport evidence. Fallback/manual paths remain for isolated or OEM cases. Authoritative handoff: `T57_R02_ARCHITECTURE_HANDOFF.md`. Capability ledger: `docs/capability/CAPABILITY_REGISTRY.yaml`.

## Activity

Current implementation is explicitly classified as `HOST_TRAMPOLINE_MANUAL_LIFECYCLE`. `StubActivityBase` is the host Activity; it schedules Guest creation after host resume and `GuestActivityController` reflectively attaches and invokes Guest lifecycle callbacks. The launch gate can prove prepared/created/resumed/window evidence, but that does not prove an Android `ActivityClientRecord` for the Guest class.

Required proof for promotion: framework-side record class/name, `ActivityThread` client record, task identity, and window token must all correlate to the same launch ID on RD API32.

## Service

`GuestComponentFactory.instantiateService` is used for declared factory construction. Service attach, `onCreate`, `onStartCommand`, bind, unbind and destroy are still driven by `GuestComponentRuntime`; the ownership status is therefore `MANUAL_COMPONENT_RUNTIME`, not Framework-owned.

## Provider and PendingIntent

Provider construction uses the declared factory and the broker owns provider resources. PendingIntent state is durable in `VirtualPendingIntentRegistry`, while the framework-facing sender is a descriptor-bearing Binder adapter. These are transport improvements, not proof that system-server owns the Guest lifecycle.

## Evidence rule

`GUEST_PREPARED`, `LAUNCH_PASS`, Java self-tests, or host-side proxy calls are insufficient by themselves. Each ownership claim requires a real RD trace with the framework record/token and the same correlation fields.
