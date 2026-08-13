# XH/SX product feature registry

Coverage is intentionally split by layer. `UI_PRESENT` does not imply a working product action.

| Feature ID | Product feature | UI source | Product flow | Contract | Runtime/data | Current coverage |
| --- | --- | --- | --- | --- | --- | --- |
| F1-01 | Installed App Discovery | XH-03 + Flash2 installed picker | present | PackageManager discovery | host package metadata | UI_PRESENT / PRODUCT_FLOW_PRESENT |
| F1-02 | Clone Installed App Into Sandbox | XH-03 | present | `importInstalledApplication` | sourceDir + splits into immutable revision | UI_PRESENT / PRODUCT_FLOW_PRESENT / CONTRACT_PRESENT |
| F1-03 | APK File Import | XH-03 fallback | present | `importApk` | document provider → artifact pipeline | UI_PRESENT / PRODUCT_FLOW_PRESENT / RUNTIME_PRESENT |
| F1-04 | Split APK / Revision Import | XH package artifact semantics | present | multi-artifact import session | base/split ordering, signer/version validation | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-05 | Default Instance | XH-02 | present | catalog default instance | user 0 instance root | PRODUCT_FLOW_PRESENT / RUNTIME_PRESENT |
| F1-06 | Create Additional Instance | XH clone/add-user vocabulary | present | `createClone` | new virtual user and isolated root | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-07 | Multi-instance Listing | XH-02 | present | catalog instances | package + user identity | UI_PRESENT / PRODUCT_FLOW_PRESENT |
| F1-08 | Instance Name / Identity | XH-02 | present | `SandboxInstance` | persisted display name and user ID | UI_PRESENT / RUNTIME_PRESENT |
| F1-09 | Per-instance Data Isolation | XH user semantics | present | scoped package service | `instances/uN/package` | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-10 | Per-instance Settings | XH settings vocabulary + SX profile fallback | present | scoped profile APIs | device/network/peripheral stores | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-11 | Per-instance Launch | XH-02/XH-04 | present | runtime launch | exact package + user | PRODUCT_FLOW_PRESENT / RUNTIME_PRESENT |
| F1-12 | Per-instance Stop | XH-02 | present | runtime stop | exact package + user | PRODUCT_FLOW_PRESENT / RUNTIME_PRESENT |
| F1-13 | Clear Single Instance | XH menu | present | `clearInstanceData` | selected instance root only | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-14 | Delete Single Instance | XH menu | present | `deleteInstance` | cleanup profile/media/job/provider/shortcut roots | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-15 | Delete Last Instance / Package Removal | XH uninstall semantics | present | catalog package removal | package revision cleanup | PRODUCT_FLOW_PRESENT / RUNTIME_PRESENT |
| F1-16 | Desktop Shortcut Creation | XH `ShortcutUtil` | present | `ShortcutManager` | pinned shortcut contract | UI_PRESENT / PRODUCT_FLOW_PRESENT |
| F1-17 | Shortcut Direct Instance Launch | XH `ShortcutActivity` | present | exact package/user extras | launch revalidates catalog | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-18 | Package Update / Revision Handling | XH import/update path | present | package upgrade policy | revision digest and rollback-safe cleanup | CONTRACT_PRESENT / RUNTIME_PRESENT |
| F1-19 | App Icon / Label Projection | XH app cards | present | package record + resource | archive icon then placeholder | UI_PRESENT / PRODUCT_FLOW_PRESENT |
| F1-20 | Import Error / Unsupported UI | XH install result | present | typed package errors | failed transaction cleanup | UI_PRESENT / CONTRACT_PRESENT |
| F2 | Location | XH partial; SX fallback | present | device profile | location profile | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |
| F3 | Camera | XH missing; SX fallback | present | camera media contract | media source store | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |
| F4 | Device | XH missing; SX fallback | present | device profile | identity store | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |
| F5 | Network/Cell | XH missing; SX fallback | present | network profile | Wi-Fi/cell store | UI_PRESENT / CONTRACT_PRESENT / RUNTIME_PRESENT |

Runtime-tested status is recorded separately in `T56_PRODUCT_COVERAGE.md`; it is not inferred from
the presence of an Activity or a Binder method.
