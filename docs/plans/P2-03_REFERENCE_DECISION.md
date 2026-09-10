# P2-03 reference decision — Quark Service/Provider and revision re-entry

状态：`BLOCKED_ENVIRONMENT`（2026-09-11，Asia/Shanghai；CAS Native 修复已通过定向回归，真机 UI 闭环被 Xiaomi App Lock 阻断）

## 固定执行坐标

- 当前源码基线：`f84adb488342fe52ac8c09a575de64982855ff7e`（本次变更待提交）
- 设备：Xiaomi `25019PNF3C` / `xuanyuan`，Android 16 / API 36，`arm64-v8a`，4096 bytes
- 夸克：`com.quark.browser` `10.15.5.1130` / `1130`，base-only，APK SHA-256
  `81bddb678f3918b683cdc48c0ccf8682137e0c1cbf23930df17b36120446206a`
- Host APK：`0.5.19.1-source-debug` / `19`，SHA-256
  `1e321d60a93fbad6d3388659ae88f51f40b0c4111d6c0882f619507cdeef3791`；设备已安装同 hash。
- 当前任务首错坐标：首帧后 Guest 原生路径；`fstat64` 与公开 ashmem FD ledger 缺口已修复，业务路径现在进入 Service/Provider，但真实窗口交互被设备 App Lock 阻断。

## CAS、NBB、VA 方法对照

### Service / Provider 业务路径

| 参考 | 已核对方法与平台语义 | CAS 决策 |
|---|---|---|
| NBB | `IActivityManagerProxy.GetContentProvider` 先走系统 authority；普通 authority 由虚拟 PMS 解析，owner 进程/Binder/holder 缺失时返回 `null`。`StartService`/`BindServiceCommon` 先查虚拟 PMS，虚拟未命中才交给原始 AMS。 | 保持虚拟 PMS 优先；仅实际解析出的 Guest 或满足 system/updated-system、enabled、exported 和显式/包约束的 Host owner 可路由。合法缺失使用 Android API 的 absence 返回值，不制造 Binder、权限或回调。 |
| VA OSS | `am.MethodProxies.BindService`/`StartService` 先调用 `VirtualCore.resolveServiceInfo`，命中后进入 `VActivityManager`；未命中才调用真实方法。`GetContentProvider` 命中虚拟 Provider 时先初始化 owner，再替换 holder 的 provider/info；holder 或 owner 缺失返回 `null`。 | 不采用 VA 的宽 Host fallback；CAS `GuestIntentResolver`、`GuestContextComponentRouter` 和 `GuestContentProviderFrameworkInterceptor` 延续虚拟优先、受控 Host owner、fail-closed。 |
| CAS | 当前 Service 链为 `GuestContext.bindService` → `GuestContextComponentRouter` → `GuestIntentResolver.resolveOptionalService`；Provider 链为 `ContentResolver` → `GuestContentProviderFrameworkInterceptor`。 | 只有在 revision 可复用且 Guest 启动成功后，才执行 `NO_GUEST_SERVICE_MATCH`、`CONTENT_PROVIDER_AUTHORITY_NOT_VIRTUALIZED` 的 P2-03 业务回归；导入失败不能被首帧或原生直装覆盖。 |

### 当前首错的 CAS 原生边界分析

| 参考 | 已核对方法与平台语义 | 结论 |
|---|---|---|
| CAS Native | `GuestRuntimeEnvironment` 在同 ABI (`arm64-v8a`) Guest 上启用 CAS PLT/IO 与进程生命周期边界；`NativeHookRuntime` 当前将 Guest ELF 的 `fstat` 重定位到 `controlled_fstat`，但原先没有 `fstat64` 目标。`controlled_fstat` 对未登记 FD 按不可见处理并返回 `EACCES`。 | 保持未知 FD/HostInternal 的拒绝；补齐与 `fstat` 同语义的 `fstat64` 重定位和台账检查，不放宽所有未知 FD。 |
| 真机 Android 16 | 首个 `memfd_create` 登记修复后的定向回归仍复现 `u4ashmem ... fstat failed ... Permission denied`。设备 `libwebviewuc.so` 的动态符号表实测同时导入 `fstat` 与 `fstat64`；Guest 运行期 FD 实测为 `/dev/ashmem<uuid>`。 | `fstat64` 覆盖是必要缺口，但补齐后仍在 `fd_operation_current` 的 unknown-FD 分支被拒绝；平台创建/继承的公开 ashmem FD 未进入 ledger。 |
| NBB / VA | NBB/VA 的 Service/Provider 方法只负责虚拟 PMS/AMS 与 Provider owner/Binder 路由，不触碰 Guest-native FD 创建或 `fstat`；本次 `GUEST_PROVIDER_PREPARE=PROVIDER_READY`、`GUEST_SERVICE_FRAMEWORK_STARTED` 均已出现。 | 不修改 NBB/VA 路由，不添加 Quark 特判；修复限定在 CAS Native FD ownership bookkeeping。 |

### Content-addressed revision / Native 路径

| 参考 | 已核对方法与平台语义 | CAS 决策 |
|---|---|---|
| NBB | `BActivityThread.handleBindApplication` 从虚拟 PMS 得到 `ApplicationInfo`，随后设置 `native`/IO 重定向；`IOCore.enableRedirect` 将 Guest native/data/profile 路径映射到虚拟根。 | CAS 的 revision 内容只应包含 APK 与提取出的 ABI native payload；ART 运行时 profile/odex/vdex 不能成为 content identity。 |
| VA OSS | `PackageParserEx.initApplicationInfoBase` 把 `nativeLibraryDir`/APK 路径放入虚拟 `ApplicationInfo`；`VClientImpl` 在 bind 前设置 runtime/IO redirect。 | 不把运行时生成物写回不可变 revision；保留 `nativeLibraryDir` 的 ABI 目录映射，并让运行时副产物在独立 profile/cache 边界内处理。 |
| CAS | `ApkImportManager.finishImport` 对已存在 revision 调用 `removeKnownRuntimeProfileSidecars` 后比较 `treeDigests`。现有实现只检查 `revision/lib/oat`。 | 设备实测的 ABI 路径 `lib/arm64-v8a/oat/arm64/` 也必须识别；仅删除已知 ART sidecar 后再比较，未知文件、符号链接和 payload 差异继续 fail-closed。 |

## 首错与平台证据

1. 无 trust admission 的 `import-prepare` 首错：
   `UNTRUSTED_NATIVE_GUEST_DENIED`。这是既定 Native 安全门禁，不是兼容失败；重放使用显式 `EXPLICITLY_TRUSTED`。
2. 显式 trust 后首错：`IMMUTABLE_REVISION_CONTENT_MISMATCH`，位置为
   `ApkImportManager.requireMatchingPublishedRevision`。设备 catalog 仍指向正确的
   `81bddb...46206a` revision；该 revision 下实际发现：
   `lib/arm64-v8a/oat/arm64/libsgmain.vdex`、`libsgmain.odex`、
   `lib/arm64-v8a/oat/libsgmain.so.cur.prof`。
3. Android 16/API36/ARM64 设备行为表明 ART 会在已发布 native 目录下生成这些 sidecar；它们不来自
   Quark APK（APK 本身的 native library count/hash 已冻结），因此不能参与不可变 APK/native tree digest。
4. 有效复现 `hold-02`：`GUEST_PROVIDER_PREPARE=PROVIDER_READY`、`GUEST_SERVICE_FRAMEWORK_STARTED` 已成立；随后
   `u4ashmem: fstat failed, fd: 464/465, err: 13, Permission denied`，紧接 `libc++abi: __cxa_guard_acquire detected recursive initialization`
   与 `CS_GUEST_LIFETIME: guest abort() ignored`，最终 `Process ...guest60 ... signal 6 (Aborted)`。
   首次有效复现还保留了同一 UC native 路径的 `SIGBUS` 版本；两次均不是 Service/Provider 缺失。
5. 物理夸克对照未形成有效样本：真机的 Xiaomi App Lock 将直接启动拦截到
   `com.miui.securitycenter/.applicationlock.AppLockActivity`，因此不能用该次结果推断物理夸克行为，且未修改系统锁配置。
6. 首个 CAS Native 尝试在 `2026-09-11 00:06` 以 `memfd_create` libc/`syscall` 登记为 Guest-owned；编译、安装、`import-prepare` 均通过，但 `hold-fix-t25` 仍出现
   `u4ashmem` 的 `fstat EACCES`，因此该假设被针对性回归否定并已撤回。
7. 同设备 `readelf -Ws libwebviewuc.so` 记录了未定义符号 `fstat`、`fstat64`、`fstatat`；当前 `fstat64` 不在 CAS Native target/replacement 列表，构成可定位的首错覆盖缺口。
8. `fstat64` 补丁回归中 `CS_NATIVE_HOOK` 已从 `patched=204` 变为 `patched=206`，但 `u4ashmem` 仍报告 EACCES；同一设备 Guest `/proc/<pid>/fd` 实测列出 `/dev/ashmem<uuid>`。因此最终缺口是公开 ashmem FD 的受限 ledger admission，而非继续扩大符号列表。

## 修复后的定向回归

- 同一 Quark revision、同一设备和同一 Host APK 重新 `import-prepare`：`PREPARED_DEGRADED`，providers ready。
- `hold-prepare` 新进程：`GUEST_PROVIDER_PREPARE=PROVIDER_READY`、`GUEST_PROVIDERS_BOUND`、
  `GUEST_SERVICE_FRAMEWORK_STARTED`，native hook `patched=206`、`patchFailures=0`；新 pid 未出现
  `u4ashmem fstat EACCES`。
- 3 冷 + 3 热短回归：6/6 到达 `GUEST_ACTIVITY_FIRST_FRAME_DRAWN`，6/6 出现 Provider/Service
  标记，6/6 没有新的 ashmem EACCES、FATAL 或 ANR。窗口随后受 Xiaomi
  `com.miui.securitycenter/.applicationlock.AppLockActivity` 控制，不能作为业务 smoke PASS。
- 设备 UI hierarchy 明确显示“请用指纹解锁 / 使用密码解锁”；未修改 App Lock 配置，也没有猜测或绕过凭据。

## P2-03 验收边界

`QUARK_FIRST_FRAME=PASS`（CAS 内部首帧）；`QUARK_SERVICE_PROVIDER=PASS`（真实 Provider/Service
路由标记）；`QUARK_BASIC_SMOKE=BLOCKED_ENVIRONMENT`。网页导航、搜索/输入、标签、返回、前后台、
文件选择与测试下载尚未取得可交互窗口，因此 P2-03 不能升级为完整 PASS；P2-90/P2-91 未执行。

## 最小修复决定

扩展 CAS 的 runtime-sidecar 清理边界：从 `revision/lib` 递归识别 ABI 子目录下名为 `oat` 的目录，仅删除已知
`.prof`、`.odex`、`.vdex`、`.art` 运行时 sidecar；保留未知条目以继续触发 immutable tree mismatch。不得改变
Service/Provider owner、权限、Host fallback 或 retry policy，不加入 Quark 包名/版本分支。

针对当前首错，按 CAS 原生 FD 语义将 Guest ELF 的 `fstat64` relocation 接入与 `fstat` 相同的
`fd_operation_current`、`guest_visible` 和 HostInternal 拒绝规则；不改变 FD 创建策略，不放宽未登记 FD 的 `fstat`，
不修改 NBB/VA Service/Provider 方法，不加入 Quark 包名/版本分支。

在上述共同规则之前，仅对未登记 FD 做一次原始 `/proc/self/fd/<n>` 目标判定：目标必须精确为公开
`/dev/ashmem` 设备及其内核命名实例，才登记为 `GuestOwned`；已登记 `HostInternal`、`BrokerTransport`、过期 revision
或任何其他未知目标继续 fail-closed。该判断使用 CAS-owned trusted syscall，避免经 Guest PLT/IO 递归，也不把任意
`/dev` 或任意未知 FD 变成 Guest 可见。

验证顺序：源码编译 → 同设备、同样本、同 hash 的 import 定向回归 → `fstat64`/ashmem FD 定向回归 → Quark 首帧与首帧后 Service/Provider
首错重放 → 业务闭环（网页导航、搜索/输入、标签、返回、前后台、一次文件选择/测试下载）。
