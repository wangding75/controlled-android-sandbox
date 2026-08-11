# T52 evidence index

Evidence root:

`D:\controlled-android-sandbox-evidence\T52-20260811-commit\`

| Area | Primary artifacts | Interpretation |
|---|---|---|
| Simple app / Stage A | `simple-app/stage-a-10x-each-user-after-bind-fix.json`, `stage-a-component-suite-final.json`, `stage-a-lifecycle-delete-recreate.json`, `D:\controlled-android-sandbox-evidence\T52-20260811-final\stage-a-final-smoke-20260811\` | Preserved 20/20 two-user cycle evidence plus final fixture64/fixture32 × user0/user1 import/prepare/component/launch/stop smoke: 16/16 PASS |
| Quark / Stage B | `quark/sx-stability-final-20260811\`, `quark/sx-thin-core-formal-20260811\`, `D:\controlled-android-sandbox-evidence\T52-20260811-final\quark-stage-b-10x-20260811-r2\`, `quark-stage-b-5min-20260811\` | SX formal prepare/launch, 10/10 launch+stop, and 300-second stability pass; BrowserActivity created, zero Activity failures/disconnects/fatal/ANR markers |
| DingTalk preparation | `dingtalk-prep/t52-final-dingtalk-prepare-result.json`, `dingtalk-base.apk`, `dingtalk-base-latest.apk`, `aapt2-manifest-xmltree.txt`, `dumpsys-package.txt` | Static import and trusted prepare pass |
| DingTalk launch | `dingtalk-prep/t52-final-dingtalk-launch-result.json`, `dingtalk-runtime-recovery-20260811-r3\`, `D:\controlled-android-sandbox-evidence\T52-20260811-final\dingtalk-stage-c-10x-20260811-r3\`, `dingtalk-stage-c-5min-20260811\` | System.exit handoff recovers to generation 2 PrivacyPolicyActivity; 10/10 launch+stop and 300-second stability pass; zero post-recovery Activity failures/fatal/ANR markers |
| App label | `D:\controlled-android-sandbox-evidence\T52-20260811-final\app-label-sx-20260811\` | APK `application-label:'闪现2'`, SX package/Launcher/UI evidence retained; legacy SX search records `闪现` |
| Source audit | `docs/sx-migration/SX_LEGACY_FEATURE_INVENTORY.md`, `SX_TO_SANDBOX_MAPPING.md`, `SX_LEGACY_HOOK_AUDIT.md` | SX migration inventory, mapping, and hook audit |
| Recovery | `backup/controlled-android-sandbox-feature-sx-migration.bundle`, source ZIP, restore script, SHA-256 manifest | Restore and integrity artifacts for the final branch |

The final acceptance report is [`T52-FINAL-REPORT.md`](T52-FINAL-REPORT.md).
