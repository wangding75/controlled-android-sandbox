# P1-03 参考实现与复现决策

日期：2026-09-08；前置：P1-02 原生 provider/consumer 基准已通过。

## 参考方法与 CAS 对应点

| 参考实现 | 已核对的方法 | 可迁移的执行约束 | CAS 对应实现/结论 |
|---|---|---|---|
| VA | `PackageParserEx.initApplicationInfoBase`（约 212 行） | 当 `dependSystem` 时，使用未 hook 的宿主 PMS `GET_SHARED_LIBRARY_FILES`，投影同一包版本已被 PMS 接受的 `sharedLibraryFiles`。 | `VirtualPackageStateBuilder.appendHostSharedLibraries`、`appendHostSharedLibraryFiles` 与 `hostSharedLibraryProjections`；`GuestSharedLibraryPathResolver` 将权威 provider APK 集同时用于 dex path 与 `ApplicationInfo.sharedLibraryFiles`。 |
| NBB | `BActivityThread.handleBindApplication`（约 358 行） | 虚拟 `ApplicationInfo`、`Context`、`LoadedApk`、IO/native/application 初始化必须使用同一受控包投影。 | `GuestApplicationInfoFactory`、`GuestLoadedApkBridge` 与 `GuestSharedLibraryPathResolver` 已分别承载信息、资源和类路径；本任务只验证它们的 shared-library 一致性。 |
| NBB | `PackageManagerCompat.fixJar`（约 346 行） | 仅对 Apache legacy jar 有定向兼容，不是把任意宿主路径塞入 `sharedLibraryFiles` 的通用方案。 | 不复刻 `fixJar` 的通用化猜测；CAS 仅接受 PMS 已投影的 Host provider 路径，或已导入 virtual provider 的不可变 APK 投影。 |

## 可复现策略

普通 debug APK 无法成为 Android PMS 的特权 static/SDK shared-library provider，因此不能把它伪称为 Chrome/Trichrome。P1-03 使用 CAS 已解析的普通 Java-library 图：

1. provider 声明 `library`，consumer 在 `application` 下声明 `uses-library required=false`；`required=false` 保持 P1-02 的原生直接安装基准合法，且不会声称 PMS 已将该 APK 放入原生 `sharedLibraryFiles`。
2. CAS 先导入 provider、再导入 consumer。`VirtualPackageStateBuilder.availableLibraries` 只从同一 CAS catalog 的 immutable provider revision 解析该 dependency；`GuestSharedLibraryPathResolver` 只从同一 virtual universe 投影 provider base/split APK。
3. consumer 在虚拟 Activity 内读取 provider 类、字符串资源和 asset。若 CAS 未把 dependency 解析为相同 revision、缺 provider、旧路径失效、重复路径，结果必须是明确的依赖/投影错误，不能降级到宿主私有目录。

真实 Chrome 的 `uses-static-library com.google.android.trichromelibrary` 在本 API36 x86_64 AVD 可由 PMS 观察到，但该任务书已把 Chrome ARM64 验收放入 P2-02；P1-03 不把它计为通过或失败。

## 修改边界

只修改 fixture manifest 以声明上述可复现图，并添加针对性执行器/证据。若 CAS 复现失败，先以失败签名定位到 `VirtualPackageStateBuilder` 或 `GuestSharedLibraryPathResolver` 的单一分支；禁止增加 Chrome/包名特判、禁止读取普通宿主私有目录、禁止伪造成功标记。

## API35 阻断补充决策（2026-09-08，修改前）

P1-05 在 API35 的 CAS import 记录到首错：`VirtualPackageStateBuilder.appendHostSharedLibraries` 对 `SharedLibraryInfo.getCertDigests()` 的静态调用触发 `NoSuchMethodError`。该 getter 不可作为 API26+ 的稳定合同；直接调用与 VA 的“由 PMS 给出已接受库图”约束无关，反而使读取 catalog 的适配层崩溃。

采用：仅通过反射读取可选 digest getter。方法存在且返回 `List<String>` 时，继续做当前 64-hex 规范化；方法不存在、访问拒绝或返回非列表时返回空 digest，让现有 `ApplicationInfo.sharedLibraryFiles`/manifest certificate 分支决定依赖是否可解析。不得伪造证书、版本、provider 或成功结果，且不得吞掉 `getSharedLibraries()` / PMS 的实际运行时错误。
