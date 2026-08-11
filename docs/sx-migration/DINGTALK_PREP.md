# DingTalk preparation and Stage C acceptance

## Scope

Target APK: `com.alibaba.android.rimet`, version `7.8.10`, versionCode `1178`, targetSdk `33`, ABI `arm64-v8a`.

The prepared artifact is the base APK only. SHA-256:

`2a1e50c004d97530cdad65d65bca87e06f8aa031072ec29574e5de7971d320bb`

Static inventory found application class `com.alibaba.android.rimet.LauncherApplication`, explicit entry component `com.alibaba.android.rimet.biz.LaunchHomeActivity`, 782 intent filters, and a maximum of 334 data rules in one filter. The host launch reference resolved to `com.alibaba.android.user.login.v3.LoginByMultiFactorActivity` and launched on the host outside the sandbox.

## Identity and isolation audit

The runtime identity is broker/session sourced and carried through `SandboxIdentity` and `SandboxInstance`:

- package name: imported package specification
- virtual user ID: broker-selected user slot
- virtual UID, data root, session ID, generation, process slot, and package revision: broker/catalog/session state
- process name: virtual package component metadata
- native guest trust: explicit trusted fixture/app record, not a general bypass

The guest does not reuse the host package UID or host data root. Package state is transported as a bounded compressed parcelable, and the guest class loader uses the framework `PathClassLoader` path to preserve multidex behavior.

## Compatibility boundary

Compatibility patches are represented by a generic registry and are disabled by default. The only DingTalk-shaped entry is a test-harness fixture proving the default-off/explicit-enable contract; it is not a production package branch.

The generic runtime work attempted for this APK includes:

- virtual Activity metadata projection;
- caller-task propagation through the guest activity instrumentation;
- host trampoline `FLAG_ACTIVITY_NEW_TASK` handling;
- safe handling of host/guest `mFragments` type mismatch;
- main-thread application preparation and nearest declared `attachBaseContext` invocation.

No package-private anti-check result, hard-coded DingTalk component, or host identity/security bypass was added. `checkExportedActivityStartup` remains an app/ROM-private runtime decision and is the remaining failure boundary.

## Current results

| Gate | Result | Evidence |
|---|---|---|
| Static APK inventory and hash | PASS | `dingtalk-prep/aapt2-manifest-xmltree.txt`, APK copies, `pm-path.txt` |
| Sandbox import and trusted prepare | PASS | `dingtalk-prep/t52-final-dingtalk-prepare-result.json` (`PREPARED`, session `8f880e0a-4761-4612-932a-15a620348d22`, generation 1, slot 4) |
| Launch request and Guest READY request layer | PASS | `dingtalk-prep/t52-final-dingtalk-launch-result.json` (`LAUNCH_REQUESTED`) and prior READY log evidence |
| Stable Activity/login/home | BLOCKED | Guest reaches `checkExportedActivityStartup`, calls `System.exit(0)`, disconnects/re-enters recovery; no stable login/home surface |
| 10 starts and 5-minute stability | NOT RUN | prerequisite stable launch did not pass |

The prepare run also records an early `GUEST_NOT_PREPARED` warning from DingTalk's dynamic receiver registration before the runtime publishes READY. It is followed by READY, but it is not treated as a clean Stage C pass.

Evidence root: `D:\controlled-android-sandbox-evidence\T52-20260811-commit\dingtalk-prep\`.
