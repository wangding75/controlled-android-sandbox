# XH / DingTalk call graph

## Proven static graph

```text
SX host process
  -> BlackBoxCore / BlackBoxSandboxEngine
  -> HookManager
       -> ILocationManagerProxy
       -> IWifiManagerProxy / IWifiScannerProxy
       -> ITelephonyManagerProxy / IPhoneSubInfoProxy
       -> IDeviceIdentifiersPolicyProxy
       -> IConnectivityManagerProxy
       -> IActivityManagerProxy / IActivityTaskManagerProxy
       -> package and class-loader services
  -> DingTalkHook.install(ClassLoader, packageName)
       -> package gate for com.alibaba.android.rimet
       -> legacy helper methods (privacy/exported/process/service/class-loader)
```

## V(D)ing manager graph

```text
VDingManager.get()
  -> getCurAppDkConfig / getDkConfig / getGlobalDkConfig
       -> new VDingConfig()   [DECOMPILED]
  -> getCurAppEnable / isEnable / getGlobalEnable
       -> true                [DECOMPILED]
  -> setDkConfig / setEnable / setGlobalDkConfig / setGlobalEnable
       -> return              [DECOMPILED no-op]
```

No caller was returned by the decompiler's reference-tree query. **INFERENCE:** this is a compatibility-shaped stub or dead API surface in the recovered SX build, not evidence of a real DingTalk configuration service.

## DingTalk-specific helper graph

`DingTalkHook` contains helpers for privacy preference files, privacy UI clicks, `ExportedActivityUtils`, service manager filtering, class-loader/split setup, safeguard/MTOP calls, and process termination. The historical SX docs tie the first two to observed DingTalk launch failures. These helpers are recorded for avoidance, not copied.

## Runtime status

**RUNTIME_OBSERVED:** the saved trace proves BlackBox service hook installation and Activity startup only. It does not prove a full DingTalk guest caller → Binder → native → final API chain. Consequently, the report never labels a DingTalk business-page result as PASS without a real trace.

