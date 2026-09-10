# P2-01 Xiaomi device and commercial-sample baseline

**Status:** `DONE` — 2026-09-10 (Asia/Shanghai)

P2-01 established the real-device and ARM64 fixture baseline, then completed
the required native Quark control after the device owner unlocked it through
the normal HyperOS UI.  The earlier Application Lock interception remains
retained as the first environment condition; it was not bypassed, disabled, or
altered by CAS or ADB.

## Fixed execution coordinate

| Field | Value |
| --- | --- |
| Discovery-time ADB serial | `192.168.137.210:35927` |
| Device | Xiaomi `25019PNF3C` (`xuanyuan`) |
| Android / API / build | Android 16 / API 36 / `OS3.0.312.0.WOACNXM` |
| Fingerprint | `Xiaomi/xuanyuan/xuanyuan:16/BP2A.250605.031.A3/OS3.0.312.0.WOACNXM:user/release-keys` |
| ABI / ABI list / page size | `arm64-v8a` / `arm64-v8a` / `4096` |
| WebView provider | `com.google.android.webview` `143.0.7499.192` (valid and enabled) |
| CAS source HEAD | `bc9570cc5e32e9249bcf3101d76ec4f6ef921676` |
| Device state at start | `user_setup_complete=1`, airplane mode off, device reported `adb_state=device` |

The serial was dynamically discovered in this session; the historical
`192.168.137.210:33259` endpoint was not reused.

## P1 candidate integrity and ARM64 short acceptance

The local artifacts still match the P1-16 candidate manifest exactly:

| Role | SHA-256 |
| --- | --- |
| Host `app-debug.apk` | `a136cea2897a0f0f2c61064fdf7da8db543efd2da01d17f68ced93afde2e787e` |
| ARM64 fixture `fixture-basic-debug.apk` | `35230e85bdc9a6c254cbfc3b2a226eb3c33da399b845b6ee1c0171e9bcc2969f` |

`run_rd_smoke.py --arm64-physical --expected-page-size 4096` installed those
two artifacts and ran the first effective attempt with diagnostic retry
disabled.  The ignored raw-evidence directory is
`out/verification/p2-01-xiaomi-s01-s04-20260910/`.

| Case | Result | Duration |
| --- | --- | --- |
| `S01-host-build-install-launch` | PASS | 9156 ms |
| `S02-guest-import-add` | PASS | 9703 ms |
| `S03-cold-launch-first-frame` | PASS | 7719 ms |
| `S04-warm-launch-reuse` | PASS | 6016 ms |

The runner records `companion32` and `fixture32` as
`NOT_IN_CURRENT_SCOPE`; neither was installed.  It also records an actual
visible Host frame, the Guest import/add operation, a cold first frame, and a
warm `activity_reuse=true` result.  The command with obsolete shorthand IDs
was rejected before device work; it created no attempt or test evidence.

## Sample manifest

All hashes below are remote `sha256sum` values for the paths returned by
`pm path`; no application package was copied into the repository.  Installer
and initiating-package records are device PackageManager provenance, not an
assertion about any upstream store account.

| Sample | Package / version | Form / ABI | Installer and signing record | SHA-256 |
| --- | --- | --- | --- | --- |
| Chrome | `com.android.chrome` `148.0.7778.180` / `777818033` | base + `split_chrome`, `split_config.zh`, `split_on_demand`; `arm64-v8a` | `com.android.vending`; APK Signature Scheme v3; PM signature record `e3ca78d8` | base `6c86fcf78b74da48ab73353fdf67012086b6a507052b85f3c31f58166af3f32d`; chrome split `b1dfa5f1a8bae4680aa9998f79017eea939d941b84358ca9ff838e88e3592558`; zh split `3c1802da3b6a16a55162d43dc5776d0b30a8da23b059b9feb69032a14eb888d1`; on-demand split `53165d668601264efecf24c632e03bf53b23c13618238c5a65e3ff2f1a17e34a` |
| Trichrome provider | `com.google.android.trichromelibrary` / static-library version `777818033` | PackageManager static-library catalog record; direct `pm path` did not expose a standalone package path | Chrome's current shared-library projection resolved provider `com.google.android.trichromelibrary` and its system APK path during the P2-02 first attempt | System-owned static-library path; not copied or hash-frozen as an ordinary app artifact |
| Quark | `com.quark.browser` `10.15.5.1130` / `1130` | base only; `arm64-v8a` | `com.xiaomi.market`; APK Signature Scheme v2; PM signature record `a1644d4a` | `81bddb678f3918b683cdc48c0ccf8682137e0c1cbf23930df17b36120446206a` |
| DingTalk | `com.alibaba.android.rimet` | not installed | not applicable | not applicable |
| Hongguo | `com.phoenix.read` `7.3.2.32` / `73232` | base only; `arm64-v8a` | `com.xiaomi.market`; APK Signature Scheme v3; cert SHA-256 `1F:E0:1F:14:25:C7:2D:C1:CD:15:8E:05:CB:69:6C:B9:FC:17:25:9A:70:F9:D2:33:FC:48:E8:E4:2E:65:10:EC` | `4ba4ee1f8699333a682e224403d8906f51eacb6f165752915589b9f64866e288` |
| Fanqie | `com.dragon.read` `7.3.2.32` / `73232` | base only; `arm64-v8a` | `com.xiaomi.market`; APK Signature Scheme v3; cert SHA-256 `1A:5A:89:13:2E:82:ED:5B:9B:2A:A0:5C:BF:13:8B:6A:00:09:63:26:CF:90:A1:C1:74:80:AA:0A:F1:F0:A1:63` | `cdc8d6999a4e2c43bddc48d936af939f5b61934f3b8ac6066afdcb877d95e1fd` |

## Native controls and blocker

* Chrome's resolved launcher was
  `com.android.chrome/com.google.android.apps.chrome.Main`.  A forced cold
  native launch completed in 493 ms; the top resumed activity and current
  focus were Chrome's own launcher window.  This is only a native baseline,
  not the P2-02 browsing smoke.
* Quark's resolved launcher was
  `com.quark.browser/com.ucpro.MainActivity`.  Package-only launch syntax was
  not resolvable on this device, so the returned explicit component was used.
  Before owner intervention, that route reached
  `com.miui.securitycenter/com.miui.applicationlock.AppLockActivity`; this
  first condition remains recorded as `BLOCKED_DEVICE_APP_LOCK` and is not a
  CAS failure.
* After the normal device-side unlock, a fresh dynamic-device check followed by
  a forced cold native launch completed in 331 ms.  The top-resumed activity
  and current focus were both `com.quark.browser/com.ucpro.BrowserActivity`,
  the Quark PID was `19557`, and the WindowManager reported a valid Quark
  Surface.  The direct capture at
  `out/verification/p2-01-xiaomi-s01-s04-20260910/native-controls/quark-native-unlocked-screenshot.png`
  is a valid non-black 1080×2400 PNG (mean luma 208.86).  The launch log
  contained no Java `FATAL EXCEPTION` or ANR for Quark.  It did record normal
  platform-denied provider and proc accesses, which are native-baseline facts,
  not a permission expansion or a CAS result.
* This establishes only the original installed app's native launch baseline.
  It is not `QUARK_BASIC_SMOKE=PASS`; Service/Provider ownership and browsing
  remain P2-03.

## Acceptance accounting and next action

| P2-01 requirement | State |
| --- | --- |
| Fresh Xiaomi / ARM64 / API / page-size evidence | PASS |
| Candidate Host hashes equal P1-16 manifest | PASS |
| ARM64-only lane excludes companion32 | PASS |
| Chrome native control operable | PASS (baseline only) |
| Quark native control operable | PASS (baseline only) |
| P2-01 close | DONE |

P2-02, P2-03, and P2-07 are now eligible but unstarted.  P2-02 is next by the
task-book priority.  The sample manifest is also available in machine-readable
form at `P2-01_XIAOMI_BASELINE.json`.
