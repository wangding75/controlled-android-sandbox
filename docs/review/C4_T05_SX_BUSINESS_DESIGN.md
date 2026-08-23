# C4-T05 SX F1-F5 / DingTalk / 100-round business

## Scope

Prove SX F1-F5 call surfaces and specified DingTalk 7.8.10 / 1178 run on
CAS-only Host. Package-neutral fixture first. Compatibility extension stays
default-off. This is `RD_BASELINE` only. `va_pro_equivalent` remains
`NOT_PROVEN`. Task book 1.1 removed the explicit 8-hour soak; 100 rounds and
existing pressure remain.

Work is in this repository. Do not rewrite `all_project/sx`.

## DISCOVER / CLASSIFY

C4-T02..T04 closed the CAS engine, sx-config-v1 migration, and BlackBox/Pine
production gate. Missing was a fail-closed business campaign that:

1. Exercises F1 camera, F2 location, F4 network, F5 bluetooth, F3 device as
   actual Guest API calls, not profile hashes alone.
2. Covers ConfigProvider (migrated instance store), FileProvider,
   shortcut, FGS, notification, Job, WebView, and a second Guest process.
3. Runs specified DingTalk revision cold start, hot start, upgrade re-import,
   pre-login surface, and background/foreground.
4. Repeats key start/stop business 100 times.
5. Proves DingTalk specialization off does not mutate the generic fixture.

That gap is `KI-R03-050` (`TEST_EVIDENCE_GAP`).

## Design

```text
generic fixture (no DingTalk package branch)
  -> migrate sx-config-v1 F1-F5 onto instance profiles
  -> F1 CameraCampaignActivity smoke
  -> F2 LocationCampaignActivity
  -> F4/F5/F3 C2T06DeviceNetworkMediaActivity (wifi, bluetooth, identity)
  -> Job / FGS / notification via C2T05SchedulingInteractionActivity
  -> WebView MainActivity
  -> RemoteActivity (:remote multi-process)
  -> FileProvider prepareProvider
  -> 100 launch/killSettled rounds
  -> createShortcut
  -> assert DingTalkCompatibilityManager.enabled(fixture)=false
  -> assert F1-F5 profiles unchanged

DingTalk 7.8.10/1178 (host-installed, imported)
  -> identify SUPPORTED_REVISION, default-off
  -> cold launch, wait LaunchHomeActivity System.exit recovery
  -> killSettled
  -> hot launch
  -> HOME (background) then launch (foreground)
  -> re-import same revision (upgrade path)
  -> dumpsys activity must show PrivacyPolicy/Login/Home pre-login surface
```

Rules:

- No DingTalk / SX package special-case in the generic fixture path.
- Compatibility manager is identify + prefs gate only; no Hook.
- Login evidence is the Guest pre-login Activity after cold/hot recovery.
  Account credentials are not part of this Host gate.
- Historical ADB serials are forbidden except as `FORBIDDEN_SERIALS` guards.
- Native extract ABI-checks ELF files only. Packed `lib/<abi>/*.so` blobs
  (zip/7z/version stamps) are stored as Android PackageManager does.

## Acceptance

- Static checker PASS.
- RD `c4-t05-sx-business --ei loops 100` PASS with F1-F5 log markers.
- RD `c4-t05-dingtalk` PASS for 7.8.10/1178 cold/hot/upgrade/fg-bg.
- dumpsys shows DingTalk pre-login Activity.
- Specialization off leaves generic fixture profiles unchanged.
- `va_pro_equivalent` remains `NOT_PROVEN`.
