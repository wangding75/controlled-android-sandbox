# P2-01 Xiaomi device and commercial-sample baseline

**Status:** `BLOCKED_DEVICE_APP_LOCK` — 2026-09-10 (Asia/Shanghai)

P2-01 has established the real-device and ARM64 fixture baseline, but cannot
close while the required native Quark control is behind HyperOS Application
Lock.  This is a device-side authentication condition, not a CAS product
failure.  No attempt was made to disable, bypass, or otherwise alter that
security control.

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
| Trichrome provider | `com.google.android.trichromelibrary` | not installed | not applicable | not applicable |
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
  That launch was redirected to
  `com.miui.securitycenter/com.miui.applicationlock.AppLockActivity`; Quark
  did not become top-resumed and no Quark PID was available.
* The first Quark condition is therefore `BLOCKED_DEVICE_APP_LOCK`, not
  `CAS_FAILURE`, `OEM_PRODUCT_DEFECT`, or `QUARK_BASIC_SMOKE=FAIL`.  It must
  not be hidden by retrying, by changing package/component routing, or by
  weakening the device security policy.

## Acceptance accounting and next action

| P2-01 requirement | State |
| --- | --- |
| Fresh Xiaomi / ARM64 / API / page-size evidence | PASS |
| Candidate Host hashes equal P1-16 manifest | PASS |
| ARM64-only lane excludes companion32 | PASS |
| Chrome native control operable | PASS (baseline only) |
| Quark native control operable | BLOCKED_DEVICE_APP_LOCK |
| P2-01 close | BLOCKED_DEVICE_APP_LOCK |

P2-02, P2-03, and P2-07 remain unstarted because their P2-01 dependency is
not complete.  Resume P2-01 only after the device owner unlocks Quark through
the phone's normal UI; do not provide or transmit the unlock credential.  On
resumption, rerun fresh discovery and the single native Quark control before
declaring this baseline complete.
