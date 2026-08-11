# SX legacy pitfalls carried into T53 review

Source: `D:\github\all_project\sx\docs\26_钉钉专项现象与问题.md`, related SX source/reverse output, and T52 regression reports.

| Historical symptom | Attempted fix | Result / why it failed | T53 protection |
|---|---|---|---|
| DingTalk `checkPrivacyPolicy` failed and current foreground check failed | Preseed privacy XML / force privacy methods | Hid an app-owned state transition and did not repair the actual runtime identity | No automatic privacy XML; standard Activity/Context identity |
| `checkExportedActivityStartup` caused exit from `LaunchHomeActivity` | Skip `ExportedActivityUtils` | Changed a DingTalk-specific guard instead of fixing exported/ATMS semantics | No exported flag fabrication; manifest and resolver truth remain authoritative |
| `getCallingPackage` exposed `com.sx.app.debug` | Return host package / ad-hoc caller rewrite | Host identity leaked across Binder and created inconsistent caller chains | Generic caller UID/package/token projection and audit |
| `getCallingActivity`, referrer and launched-from package were inconsistent | Patch one Activity field | Partial changes left task/token/ComponentName identity inconsistent | Generic Activity/ATMS identity path |
| DingTalk process exited | Hook `System.exit`, `Runtime.exit`, `killProcess` | Masked the failure and created lifecycle leaks | No process-exit suppression |
| Native anti-suicide crash | SIGSEGV / anti-suicide handling | Made failure look like readiness and destabilized teardown | Fatal-error policy and explicit failure |
| Camera hook was installed for every package | Unbounded `Thread.start` / global Hook install | Cross-package and cross-instance contamination | Generic camera service only, bounded session/callback state |
| Camera source used `CameraConfig.mediaPath` absolute host path | Read the configured path directly | Host media path could be exposed to guest and media source was not instance isolated | Copy into instance-owned Guest files; only relative metadata crosses contract |
| Location hook changed getters and `getLastKnownLocation` only | Return fixed fields | Callbacks, provider state and service Binder path remained incomplete | Location service projection, callbacks, timestamp policy and route samples |
| Device hook changed `Build` static fields per call | Reflectively mutate final fields | Not durable, not isolated, and swallowed errors | One deterministic per-instance device profile |
| Wi-Fi spoof did not align with location/cell | Return independent SSID/BSSID values | Environment consistency was not guaranteed | Shared network/cell/location configuration and evidence |
| Fake `READY` or catch-and-ignore | Return success after partial install | Hid missing adapters | Explicit `*_ADAPTER_REQUIRED` / fail-closed results |

T53 does not copy the old hooks. F6 license/activation/VMP/Dex2C remains deferred and no credential, payment, or authentication bypass is implemented.

