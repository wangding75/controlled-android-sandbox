# P1-03 路径、版本与投影对照

## 受控来源链

| 层 | provider/consumer 事实 | 允许路径/版本来源 | 禁止的回退 |
|---|---|---|---|
| Android PMS（原生基准） | 直装同一 debug 签名 revision 的 `fixture-library-provider` 与 `fixture-library-consumer`；两者版本、签名和 codePath 由 PMS 给出 | `dumpsys package` 输出：`out/verification/p1-03-cas-library-final-20260908/provider-package.txt`、`consumer-package.txt` | 用普通 APK 伪造 static library，或把 PMS 未给 consumer 的 generic host path 作为 `sharedLibraryFiles` |
| CAS Package Authority | 先导入 provider，再导入 consumer；consumer 的 `cas.p1.fixture.provider` 只由同一 catalog 的 provider immutable revision 解析 | `VirtualPackageStateBuilder.availableLibraries` + `appendProvidedLibraries`；导入结果均为 `IMPORTED` | 以 provider 包名、旧文件名或任意目录猜测新 revision |
| CAS Guest PMS / loader | 同一 `VirtualSharedLibrarySnapshot` 决定 provider package、dex/source、`ApplicationInfo.sharedLibraryFiles` | `GuestSharedLibraryPathResolver.resolvedJavaLibraryProjections` 和 `resolvedSharedLibraryFiles` | classloader 与 ApplicationInfo 走不同 provider version，或重复 source file |
| CAS resource/context | provider 的 PackageContext 读字符串和 asset | 已投影 provider APK；fixture marker 证明 | consumer 直接读取 provider 的普通宿主 data/private path |

## 实测版本指纹

API36 x86_64、4KB AVD（`emulator-5554`）在最终执行中使用：

| APK | SHA-256 |
|---|---|
| provider | `330651bfe5209dd99a40abf2c94848426b99ce7b9313f46ad58f511221c0612c` |
| consumer | `46a1f264bb693163b784a89ea0c064ba029267aac8cafd21e0612b380f4a0fc6` |

`run.json` 记录 provider/consumer 两次导入均为 `IMPORTED`、projection Activity 为 `LAUNCH_PASS`。该 fixture 没有 split；因此 PMS base source、CAS immutable provider base、consumer `sharedLibraryFiles` 是同一单一来源集合。真实多 split/static provider 的 PMS path/version/cert 验收未被冒充为本结果，留在 P2-02。
