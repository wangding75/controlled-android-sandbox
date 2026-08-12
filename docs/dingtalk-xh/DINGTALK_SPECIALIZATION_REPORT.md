# T53 DingTalk specialization report

## Result

The DingTalk-specific control plane is implemented and isolated. The generic native Camera1 boundary is now closed for the verified MuMu contract; no DingTalk branch was added to solve it.

## Actual XH V(D)ing surface

The exact recovered names are `com.lody.virtual.client.ipc.VDingManager` and `com.lody.virtual.remote.VDingConfig`.

`VDingManager` is a process-local singleton with current-app, user/package and global get/set methods. The recovered getters return default configuration/boolean values and the setters are no-ops. Its constants identify the DingTalk package and a global user value. `VDingConfig` is a `Parcelable` with obfuscated fields and defaults; field semantics are not proven and were not guessed. No caller was proven from the pulled APK reference search. The call graph therefore treats this surface as a compatibility control-plane clue, not as authority for Android data-plane behavior.

The SX helper named `DingTalkHook` is a separate legacy surface. It contains package-gated privacy preference, exported-activity, class-loader, service-manager and process-exit workarounds. Runtime traces prove BlackBox service proxy initialization, but do not prove a DingTalk camera/location transaction. Those hooks were audited and not copied.

## Controlled Android implementation

`DingTalkCompatibilityManager` is under `app/.../compatibility/dingtalk/`. It performs only:

- package plus exact version gate: `com.alibaba.android.rimet`, version `7.8.10`, code `1178`;
- per-virtual-user persistent enabled state;
- default-off behavior and explicit enable/disable commands;
- generic profile pass-through for location, camera, device and network diagnostics;
- fail-closed unsupported-target diagnostics.

The manager does not install hooks and does not manufacture Android results. Generic profiles remain the single data-plane authority. A source audit found no DingTalk package literal in the generic framework/runtime/native modules; the only production package gate is in the isolated manager.

## Classification

Promoted to General Sandbox:

- virtual location fields, timestamps, callbacks and trajectory sampling;
- virtual camera source ownership, image/video decode, Camera2 preview and capture substitution;
- generic native Camera1 connect identity, preview callback and capture-result substitution;
- device identity/build/SIM/operator profile;
- Wi-Fi and cell profile projection;
- Activity/Context/UID/package/task/referrer identity semantics;
- Binder/service/cache/session lifecycle and recovery.

Retained as DingTalk-specific:

- exact package/version identification;
- explicit compatibility toggle and per-instance diagnostics;
- control-plane orchestration of already-generic profile objects.

`WHY_NOT_GENERAL`: a package/version gate and a user-visible compatibility switch have no Android-wide data-plane meaning. The profile data and hooks do, so they stay generic. The legacy privacy/exported/process-exit behavior is not retained because it changes app-owned state or masks a runtime defect.

## MuMu validation

Installed target: `com.alibaba.android.rimet`, version `7.8.10`, versionCode `1178`.

- Manager enable: `dingtalk/profile-final-result.json` reports `PROFILE_CONFIGURED`, target reason `SUPPORTED_REVISION`, static Shanghai location, IMAGE source and source SHA `258c57db...1682ae8f`.
- Manager disable: `DINGTALK_COMPATIBILITY_DISABLED`.
- Startup: `LaunchHomeActivity` was prepared and launched; DingTalk's normal process handoff produced a recovering generation and `PrivacyPolicyActivity` was created. No `System.exit` hook or fake READY was used.
- Final-code MuMu loop: corrected `dingtalk-profile` plus 10/10 `launch-component` and 10/10 `stop` operations passed on `127.0.0.1:16384`; `dingtalk/dingtalk-final-logcat.txt` has zero target Sandbox/DingTalk FATAL or ANR matches.
- Early dynamic receiver warnings from `HWReceiverANRCompat` remain recorded as a known startup observation from the T52 path; they are not converted into a false clean result.
- No real account was used. Protected business pages requiring a session are recorded as `REAL_USER_SESSION_REQUIRED`. Generic location/device/network/camera fixtures were run independently and are not replaced by page-data edits. The DingTalk manager can configure the shared Camera1/Camera2 profile for its instance; the generic adapter has no DingTalk package/version knowledge.
- The DingTalk camera and location business pages were not reachable without a real session. Consequently no DingTalk screenshot/result hash is claimed for those pages; the generic Camera1/Camera2 and Location fixtures provide the data-plane evidence independently.

## Avoided SX failures

The implementation does not preseed privacy XML, bypass exported checks, rewrite DingTalk dex, suppress process termination, install unbounded global hooks, expose host media paths, return fake READY, or swallow adapter errors. The Camera1 fix is at the generic native runtime boundary and retains explicit AppOps/adapter diagnostics; the MuMu shutter-audio warning is not hidden.

## Remaining real-device work

Real camera HAL/Camera1/Camera2 behavior, microphone/video-call paths, physical SIM/RIL, physical Wi-Fi/cell, Bluetooth/NFC, OEM background policy, ARM64 native variants and vendor metadata remain required before hardware-level sign-off. F6 remains deferred.
