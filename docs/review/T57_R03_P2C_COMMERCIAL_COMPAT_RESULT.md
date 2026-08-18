# T57-R03-P2C Commercial Compatibility Corpus

RESULT: PASS with classified remainder

VA Pro: `NOT_PROVEN`

## Rule

Real-app failures are evidence. They are not permission to add
package-specific core hacks.

## Available targets on `RD测试`

| App | Class | Result |
| --- | --- | --- |
| Quark `com.quark.browser` | WebView/Chromium | `LAUNCH_SMOKE_PASS` import-launch + relaunch + clear |
| DingTalk `com.alibaba.android.rimet` | Framework-heavy | `LAUNCH_SMOKE_PASS` |
| Tomato/DragonRead `com.dragon.read` | Native/content | first FAIL `APK has too many ZIP entries` (20k bound). Generic importer bound raised to 200k. Retry `LAUNCH_SMOKE_PASS`. |
| Fixture / Flash2 self-comparison | fixture | PASS |

XH and old SX: `ENVIRONMENT_NOT_AVAILABLE` (not installed, no local APK).

Evidence: `artifacts/capability-audit/p2c/20260817T121055Z`

## Classification

- DragonRead ZIP bound: `GENERAL_RUNTIME_DEFECT` then generic fix. Not APP_OR_SDK_SPECIFIC.
- No package-name core patches.

## Remainder

- 10–30 minute per-app soak was not run here (deferred to P2E).
- No real user accounts; login-gated surfaces UNVERIFIED.
- XH/SX absent.
