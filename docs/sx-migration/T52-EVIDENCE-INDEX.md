# T52 evidence index

Evidence root:

`D:\controlled-android-sandbox-evidence\T52-20260811-commit\`

| Area | Primary artifacts | Interpretation |
|---|---|---|
| Simple app / Stage A | `simple-app/stage-a-10x-each-user-after-bind-fix.json`, `stage-a-component-suite-final.json`, `stage-a-lifecycle-delete-recreate.json`, filtered logcat | Preserved 20/20 two-user cycle evidence, component/lifecycle isolation pass; final fixture smoke was also run after the latest bridge changes |
| Quark / Stage B | `quark/sx-thin-core-formal-20260811/acceptance.json`, `logcat.txt`, `activities.txt`, `apk-sha256.txt`, plus the historical prepare/launch artifacts | SX formal prepare/launch reaches `BrowserActivity`; the isolated thin core loads and remains stable for 335 seconds with zero fatal/disconnect markers. The cold pristine/10-start gate is explicitly not claimed; historical thick-path failures remain preserved for diagnosis |
| DingTalk preparation | `dingtalk-prep/t52-final-dingtalk-prepare-result.json`, `dingtalk-base.apk`, `dingtalk-base-latest.apk`, `aapt2-manifest-xmltree.txt`, `dumpsys-package.txt` | Static import and trusted prepare pass |
| DingTalk launch | `dingtalk-prep/t52-final-dingtalk-launch-result.json`, `logcat-after-explicit-launch.txt`, `logcat-after-launch.txt`, process/task dumps | Request layer passes, but stable Stage C launch is blocked by app/ROM startup exit |
| Source audit | `docs/sx-migration/SX_LEGACY_FEATURE_INVENTORY.md`, `SX_TO_SANDBOX_MAPPING.md`, `SX_LEGACY_HOOK_AUDIT.md` | SX migration inventory, mapping, and hook audit |
| Recovery | `backup/controlled-android-sandbox-feature-sx-migration.bundle`, source ZIP, restore script, SHA-256 manifest | Restore and integrity artifacts for the final branch |

The final acceptance report is [`T52-FINAL-REPORT.md`](T52-FINAL-REPORT.md).
