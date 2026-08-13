# XH → SX → Flash2 product gap matrix

| Feature ID | XH source evidence | XH UI | SX fallback | Document requirement | Flash2 implementation | UI chain | Application chain | Runtime chain | Gap | Action | Final status | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F1-01 | `ListActivity`, `ListViewModel` | installed app list | existing PackageManager contract | real installed discovery | `SxSandboxAdapter.installedApplications` | Apps → installed picker | ApplicationLayer → adapter | PackageManager | MATCH | keep host metadata source | UI_PRESENT / PRODUCT_FLOW_PRESENT | InstalledApplication |
| F1-02 | XH add installed app | select host app | none | physical host app → sandbox | `importInstalledApplication` | picker action | ApplicationLayer → PackageService | sourceDir/splits → ApkImportManager | MISSING → fixed | use server-side PM resolution | CONTRACT_PRESENT / RUNTIME_PRESENT | AIDL + lifecycle |
| F1-03 | XH import path | file import | document picker | APK file import | `importApk` | Apps → document picker | ApplicationLayer → PackageService | artifact pipeline | MATCH | keep separate wording | PRODUCT_FLOW_PRESENT | MainActivity |
| F1-04 | XH package install behavior | multi artifact implied | package artifact docs | base + split revision | existing artifact order/validation | import result | install session | immutable revision | PARTIAL → fixed | installed source collects all splits | CONTRACT_PRESENT / RUNTIME_PRESENT | PackageArtifactOrder |
| F1-06 | XH clone/add user | clone/add instance | catalog contract | multiple real instances | `createClone` | card action | ApplicationLayer → PackageService | virtual user + instance root | MATCH | preserve naming | PRODUCT_FLOW_PRESENT |
| F1-13 | XH clear menu | clear selected app | catalog contract | package + instance only | `clearInstanceData` | card action | PackageService | selected instance root | MATCH | retain record | CONTRACT_PRESENT |
| F1-14 | XH remove menu | delete selected app | catalog contract | cleanup and last-package removal | `deleteInstance` | card action | PackageService | catalog + file sweep | MATCH | disable exact shortcut | PRODUCT_FLOW_PRESENT |
| F1-16 | `ShortcutUtil` | create shortcut menu | none | launcher shortcut | `SandboxShortcutManager` | card action | ApplicationLayer/UI | ShortcutActivity | MISSING → fixed | exact ID package#instance | UI_PRESENT |
| F1-17 | `ShortcutActivity` | direct instance launch | none | no Home-only shortcut | exported shortcut activity | launcher | shortcut activity → ApplicationLayer | exact launch | MISSING → fixed | revalidate catalog | CONTRACT_PRESENT |
| F1-19 | XH app adapter | icon/label | placeholder resource | real icon projection | archive icon + placeholder | card/list | package record | package archive parser | UI_DRIFT → fixed | stable resource fallback | UI_PRESENT |
| F2 | XH fake location | partial | current profile editor | full location contract | InstanceSettingsActivity | settings | ApplicationLayer | device profile store | PARTIAL | map remains not implemented | ENVIRONMENT_LIMITATION |
| F3 | XH_NOT_FOUND | absent | current camera editor | media source UI | InstanceSettingsActivity | settings | ApplicationLayer | media store | XH_NOT_FOUND | use SX UI contract only | PRODUCT_FLOW_PRESENT |
| F4 | XH_NOT_FOUND | absent | current device editor | identity UI | InstanceSettingsActivity | settings | ApplicationLayer | device store | XH_NOT_FOUND | use SX UI contract only | PRODUCT_FLOW_PRESENT |
| F5 | XH_NOT_FOUND | absent | current network editor | Wi-Fi/cell UI | InstanceSettingsActivity | settings | ApplicationLayer | network store | XH_NOT_FOUND | use SX UI contract only | PRODUCT_FLOW_PRESENT |

## D01 reclassification

D01 is **not** “Installed App Clone” when the user uses the APK document picker. The installed-app
source flow is now a separate action and resolves the physical package through `PackageManager`,
including `sourceDir` and every `splitSourceDirs` entry. APK file import remains a separate product
flow. This closes the semantic drift without creating a second package runtime.
