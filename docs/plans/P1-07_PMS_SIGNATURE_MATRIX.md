# P1-07 PMS 隐藏接口签名矩阵与验收回执

状态：`COMPLETE`（2026-09-08）。源码基线：`40f629d03bbb0927f171a0e318c242398d8a141d` 上的 dirty P1-01--07 工作树；本项未提交、未推送。

## 适配合同

| 调用 | API32 已实测签名 | API35/36 已实测签名 | 实参 | 空/失败合同 |
|---|---|---|---|---|
| `resolveService` | `(Intent,String,int,int)` | `(Intent,String,long,int)` | `Intent`, Java `null` MIME, `0` flags, physical userId | 两次解析均空为 `EMPTY`；缺签名/反射异常/意外回包分别为结构化状态。 |
| `queryIntentServices` | `(Intent,String,int,int)` -> `ParceledListSlice` | `(Intent,String,long,int)` -> `ParceledListSlice` | 同上 | `getList()` 解包；空 list 是 `EMPTY`。 |
| `resolveContentProvider` | `(String,int,int)`（静态精确重载；API32 Provider campaign） | `(String,long,int)` -> `ProviderInfo` | authority、flags、physical userId | `EMPTY` 才能映射为普通 provider 缺失；其余状态不降格。 |

实现只枚举上述完整签名。它不枚举同名方法、不按类型推测、不截断超过 API32 `int` 位宽的 flags。未知 OEM 形态保留 `UNSUPPORTED_SIGNATURE` 诊断，转交 P2-07。

## 参考采用与排除

- NBB N04 按固定 `args[0..2]` 取得 Service 的 `Intent/resolvedType/flags`，虚拟优先、未命中才回物理 PMS。
- VA V04 按固定位置读取 `queryIntentServices` 参数，并按返回形态解包 list/slice。
- 两者的老接口 flags 宽度不能直接套用于 API33+；CAS 以 AOSP API32 int、API33+ long 及当前 AVD `IPackageManager$Stub$Proxy` 选中签名为准。
- 不采用 NBB 的 Play/权限伪造分支，也不采用 VA 的宽泛 Host 可见性回退。CAS 仍只允许已启用、已导出且非虚拟包的 system-owner 窄路由。

## 代码与静态验证

- `HostPackageManagerBridgeSelfTest` 覆盖同名 int/long 重载、null MIME、userId 17/23/29/41/43、`ParceledListSlice`-like `getList()`、正常 `EMPTY`、调用异常、不可表示的 API32 flags。
- `GuestIntentResolverSelfTest`、`GuestContextBoundarySelfTest`、`GuestBrokerContentProviderSelfTest` 均通过。后者确保 Provider owner/普通空结果不回退到任意 Host provider。
- `python tools/static_android_compile.py`：13 个模块编译通过，166/166 self-tests PASS。收据：`build/static-android-compile/verification/static-android-module-compilation.json`、`build/static-android-compile/verification/static-android-test-execution.json`。
- `./gradlew.bat :app:assembleDebug :fixture-basic:assembleDebug :fixture-compat32:assembleDebug`：`BUILD SUCCESSFUL`，202 actionable tasks。

## AVD 定向验收

| API | 设备 | 运行证据 | 结果 |
|---|---|---|---|
| 32 | `emulator-5558`, x86_64, 4 KiB, Android 12 | `p1-07-api32-service-intflags-20260908`；`p1-07-api32-provider-campaign-20260908` | PASS；日志选中 int Service/query 签名。Provider campaign 完成 user0/user1 各 5 次 CRUD/Cursor/FD/grant/observer。API32 generic unknown `getType` 未进入 raw Host provider 分支，因此 raw int Provider 形态由精确静态重载测试覆盖，不伪称已在该路径实测。 |
| 35 | `emulator-5556`, x86_64, 4 KiB, Android 15 | `p1-07-api35-finalapk-20260908` | PASS；日志选中 long Service/query/Provider 三个签名。 |
| 36 | `emulator-5554`, x86_64, 4 KiB, Android 16 | `p1-07-api36-finalapk-20260908` | PASS；日志选中 long Service/query/Provider 三个签名。 |

最终 APK SHA-256：Host `555c9c3b93909570c09fb27ec4a24ad93f058b9b0c21fa27a64d94dac99f35a7`；fixture `cf8f27e49f989c8cb723bcf7e237155220ddd8402fb63509a317eb2392e207f8`；peer `0969477e2d295b17158662812efc8d990261193294be903968235040bc410b70`。

## 保留首错

1. `p1-07-api32-service-20260908`：最初把 API32 当作 long-flags，真实 PMS 返回 `UNSUPPORTED_SIGNATURE`；已据此加入显式 int 签名，不覆盖该记录。
2. `p1-07-api36-service-longflags-20260908`：原生 P1-05 fixture 在远端 Service death callback 前失败，未进入 CAS 适配；与 P1-07 无关，保留为 P1-05 生命周期后续证据。
3. `p1-07-api32-provider-intflags-final-20260908`：ADB daemon 日志采集失败；另以独立 `...complete...` 坐标采集，后者显示 API32 generic `getType` 未走 raw provider，故不将该结果标为 raw Provider PASS。

下一项：P1-08。
