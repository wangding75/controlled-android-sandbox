# P2-03 reference decision — Quark Service/Provider and revision re-entry

状态：`BLOCKED_QUARK_POST_CONSENT_SIGKILL`（2026-09-11 检查点；同意页/首帧已恢复，`libwebviewuc.so` 可按逻辑路径加载；点击“同意并继续”后 principal SIGKILL。历史 `BLOCKED_QUARK_WEBVIEW_ANR` 仍保留为更早证据。）

## 固定执行坐标

- 当前源码基线：本检查点待提交（其上为 `d6ddcb10` / 此前 `70738f3c`）
- 设备：Xiaomi `25019PNF3C` / `xuanyuan`，Android 16 / API 36，`arm64-v8a`，4096 bytes
- 夸克：`com.quark.browser` `10.15.5.1130` / `1130`，base-only，APK SHA-256
  `81bddb678f3918b683cdc48c0ccf8682137e0c1cbf23930df17b36120446206a`
- Host APK：`0.5.19.1-source-debug` / `19`，SHA-256
  `9eeb759d9a54ae02012c18209cdcaed612735fdfc63ccbef752a7a3647f713f1`；设备已安装同 hash。
- 当前任务首错坐标：`fstat64` 与公开 ashmem FD ledger 缺口已修复，Service/Provider 已进入真实路由；正常设备解锁后，夸克 U4/Chromium 的 `loadDataWithBaseURL` 后置路径触发输入分发 ANR。

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

## 2026-09-11 续接：CAS/NBB/VA 与平台行为复核

调试 harness 的首错 request 为 `658eab97-1d47-407d-aba3-a478d7a90295`：组件子步骤均
成功，只有 `ALREADY_PREPARED_DEGRADED` 被错误地当作失败。CAS 的生命周期协调器已将
该状态定义为可继续使用的降级准备态；NBB/VA 的对应方法仍是宿主 owner/proxy 持有
系统侧生命周期、Guest 侧投影组件并继续调用。因此没有改 Service/Provider owner、权限
或 fallback，只把该合法状态加入 Debug 验收白名单。

修复后的 request `e5c7c86b-0551-4293-a386-eb496203bfd2` 返回 `PASS`，Service、Receiver、
Provider 分别为 `SERVICE_STARTED`、`BROADCAST_DELIVERED`、`PROVIDER_ALREADY_READY`。

随后使用真实 Host“应用→夸克→启动”入口，不调用 `uiautomator`。CAS 的
`GuestActivityThreadInstrumentation`/`ActivityFieldBridge` 负责客户端 Guest 记录投影，
`WindowManagerHook`/`InteractionObjectRewriter` 负责 WindowManager Binder 边界的 Host
包名投影；WMS 实测为 Host Stub ActivityRecord + Guest 窗口名 + Host LayoutParams package，
与 NBB/VA 的 proxy/stub 行为一致。Android 16 的 TaskInfo 同时为 `mBehindAppLockPkg=null`，
故当前失败不是 App Lock。

首帧之后，Quark 自有 U4/Chromium 日志出现 `WebCoreManager` NPE、
`nativeSetEnabledMremap` 缺失提示和 `loadDataWithBaseURL` 栈，随后进程收到 ANR signal；
系统 ANR 记录的 native 栈落在冻结 revision 的 `libunet.so` 等 Quark native payload。
没有出现 `NO_GUEST_SERVICE_MATCH`、Provider authority 未虚拟化、Guest/Host 身份污染或
CAS Native ashmem 首错。基于现有证据不能安全推出 CAS/NBB/VA 修复点，因此本次不猜测
增加 WebView fallback、禁用 NativePolicy 或加入 Quark 特判；P2-03 保持阻断，后续应先
建立同一 U4/Chromium 首次 `loadDataWithBaseURL` 的平台/原生对照。

## P2-03 验收边界

`QUARK_FIRST_FRAME=PASS`（CAS 内部首帧）；`QUARK_SERVICE_PROVIDER=PASS`（真实 Provider/Service
路由标记）；`QUARK_BASIC_SMOKE=BLOCKED_QUARK_WEBVIEW_ANR`。历史 App Lock 首错仍保留，
但当前复测已不再被 App Lock 拦截；网页导航、搜索/输入、标签、返回、前后台、文件选择
与测试下载尚未取得稳定窗口，因此 P2-03 不能升级为完整 PASS；P2-90/P2-91 未执行。

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

## 2026-09-11 同意页后 SIGKILL

同意页可显示。点击“同意并继续”后 guest60 在约 1.5s 内被 SIGKILL，窗口回到 Host。crash buffer / tombstone / FATAL 均为空。

已核对的执行方法：

| 参考 | 方法 | 结论 |
|---|---|---|
| NBB | OSS 不 Hook `_exit`/`exit`/`abort`。`IOCore.proc` 只重定向 `cmdline`，不改 `/proc/self/stat` 的 PID。`getpid` 为内核 PID。 | 子进程 `_exit` 按 Linux 合同结束。 |
| VA OSS | `IOUniformer` 的 `kill` 只记录并 `syscall(__NR_kill)`；`vfork` 转到 `fork`。不拦截 `_exit`。 | 同上。 |
| CAS | `controlled_fork` 在 policy 配置后拒绝 libc `fork`；`SYS_clone` 为 Project，U4/Chromium 可用 raw clone 造出子进程。随后 `controlled_underscore_exit` 在 `process_exit_allowed=false` 时对**所有** PID 忽略 `_exit`。 | 保护 slot 进程是 CAS 约束；把它套到 fork 子进程违反 NBB/VA 和 Linux `_exit` 合同。 |

设备日志（pid 17935，15:28:45）：

1. U4 `ServiceSetting type:Render desire_proc_mode:1 real_proc_mode:-1`，renderer `pid: 0/0`。
2. 四个 helper（18725/18739/18743/18756）记录 `CS_GUEST_LIFETIME: guest _exit(0) ignored`。
3. HyperSentinel 记录 RSS `0kb -> 735972kb`。
4. `libc: kill: send 9 to pid -17935`，随后 Zygote `signal 9`。

`WebCoreManager` NPE 与 `nativeSetEnabledMremap` UnsatisfiedLinkError 在物理夸克 `quark-direct.log` 同样出现，不能当作 SIGKILL 根因。

最小修复：

1. `NativePolicy.configure` 记录 principal kernel pid；仅该进程继续拦截 `exit`/`_exit`/`abort`/`SYS_exit(_group)` 与自杀信号。fork 子进程按 NBB/VA 允许退出。
2. `/proc/self/stat` 与 `/proc/self/status` 的 Pid/Tgid 改为与 `getpid()` 相同的内核 pid。NBB 只改 cmdline，不改 stat PID；CAS 原先把 stat 写成 virtual pid，U4 native 读到 pid=0/`real_proc_mode=-1`。cmdline 名称与 maps 脱敏保持不变。

不放宽 Host fallback，不加 Quark 包名分支，不禁用 NativePolicy。

## 2026-09-11 检查点（同意页已恢复，浏览闭环未关）

状态改为：`BLOCKED_QUARK_POST_CONSENT_SIGKILL`。同意页 / `BrowserActivity` / 首帧已恢复；
点击“同意并继续”后 principal guest SIGKILL。`QUARK_BASIC_SMOKE` 仍未关闭。

| 参考 | 已核对方法 | CAS 落地 |
|---|---|---|
| NBB | `PackageManagerCompat` / `BPackageManager` 投影 `nativeLibraryDir`；`IOCore.enableRedirect` + `OsStub` 把 Java `Os` 路径改写到虚拟根；不把 Host 包名嵌进 Guest 可见 native 路径。 | `GuestApplicationInfoFactory` 发布 `/data/app/<pkg>/lib/<abi>`。`installSystemIoHooks` 覆盖 libjavacore/openjdk（普通 Guest，不再要求 isolated file capabilities）。`installNativeLoadRedirect` 把 `System.load(逻辑路径)` 映到 CAS revision。 |
| VA OSS | `PackageParserEx.initApplicationInfoBase` 写入虚拟 `nativeLibraryDir`；`VClientImpl` bind 前 IO redirect。 | 同上，不把 CAS `files/packages/<host+guest>/...` 写进 `ApplicationInfo.nativeLibraryDir`。 |
| CAS 实测 | 发布 Host-files alias 或 packaged CAS 目录 → U4 `why:11` / `real_proc_mode:-1` → SIGKILL。只发逻辑路径且不装 system IO → 首页活着但 `libwebviewuc.so` 不 load，百度白屏。逻辑路径 + system IO + nativeLoad → so 加载成功，同意页可进。 | 保持逻辑 `nativeLibraryDir`。`GuestNativeLibraryAlias` 只在 `prepareNativeBootstrap` 创建一次；禁止在 `GuestContext` 构造 / `createConfigurationContext` 再 `Files.createDirectories(Host files)`。`GuestStorageNameCodec` 在 hooked realpath 反映射后仍用实例词法父路径。 |

同意后仍开放的首错：U4 `real_proc_mode:-1`、`pid: 0/0`，`bindServiceAsUser` 走
`Context` 默认 stub，`sNormalHandler` ImageLoader `memoryCacheSize` 非法。不在本次提交关闭。
