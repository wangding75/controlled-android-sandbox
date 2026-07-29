# M4-T17 与 VirtualApp／NewBlackbox 对比报告

## 对比范围

本报告只比较 M4-T17 的 Native 文件系统、动态加载、网络、录音和 ABI 架构增量。Controlled Sandbox 的判断来自当前源码、Host-native test、静态 Android 编译和制品门禁。VA/NBB 作为成熟实现参照；不依据 README 直接认定其当前设备兼容性，也不把历史能力等同于所有分支和 Android 版本均可用。

## 本迭代新增能力

- 现代文件 syscall 和 `/proc/self` 虚拟化。
- 受控 `dlopen`／`android_dlopen_ext` 与 Native Library 路径。
- IPv4/IPv6、DNS、Hostname 和 Interface 身份隔离。
- Generation-bound Native Audio Capture Gate 与撤销终止。
- 显式 Native ABI 元数据。
- 64 位 Host + 独立 32 位 Companion + Typed Binder 架构。

## 能力对比

| 能力 | Controlled Sandbox M4-T17 | VirtualApp | NewBlackbox | 当前差距 |
|---|---|---|---|---|
| 常用文件 Hook | 已有 open/openat/stat/readlink 等基础，并补 openat2/statx/renameat2/faccessat2/getdents64/mmap | 具有长期 Native IO Redirect/Hook 积累，具体覆盖随分支和 Android 版本变化 | 具有较广 Bionic/IO Hook，具体覆盖随分支变化 | 当前项目缺少 Android/Bionic 版本和 OEM 设备矩阵 |
| `/proc/self` | maps/cmdline/status 使用 Guest 身份生成有界只读快照 | 成熟方案通常包含 proc 身份和路径处理 | 通常包含 proc/身份重写 | 当前只覆盖冻结清单，没有完整 `/proc` 面 |
| 动态加载 | Guest root + system allowlist；未知 Soname、Host 私有路径和错误 namespace 拒绝 | 对 Native Library、Loader 和版本差异有长期适配 | 现代分支通常有更广 linker/bionic hook | `android_dlopen_ext` 和 Linker Namespace 尚无设备证据 |
| Split APK Native Library | 安装记录、路径和显式 ABI 进入 Runtime | 成熟包模型通常处理 split/native library | 通常支持 split/native extraction | 冲突、压缩方式、直接 APK 加载仍需设备验证 |
| 网络身份 | IPv4/IPv6、DNS、Hostname、Interface 进入 Native policy | 成熟虚拟网络/身份 Hook 覆盖更广 | 通常具备较广 network/system-service hook | VPN、ConnectivityService、socket option 和 OEM 行为不完整 |
| Native 录音 | RECORD_AUDIO/AppOps 控制 AAudio/NDK MediaRecorder gate；撤销清理 token/handle | 长期 Java/Binder/Native 兼容积累更完整 | 通常覆盖 Java/Binder Hook，Native 细节依分支 | OpenSL ES、AudioSystem、真实 Audio Server 回调未验证 |
| 64 位 Native | Host 模块配置 arm64-v8a/x86_64，Host-native 源码测试通过 | 支持情况依历史分支和宿主方案 | 现代分支通常优先 arm64 | Controlled Sandbox 未生成并安装 Android APK 证明 |
| 32 位 Native | 独立 companion APK 配置 armeabi-v7a/x86；typed Binder 和 fail-closed routing | 成熟方案通常使用独立 32 位进程/包或特定双开架构 | 通常提供 32/64 位配套方案，依项目分支 | 当前只完成架构、Probe 和身份契约，完整 Guest 生命周期未接线 |
| 跨位宽安全 | Session/Generation/User/Revision/Nonce/ABI 绑定，签名权限，重放和陈旧拒绝 | 具体实现和安全边界依分支 | 具体实现依分支 | 缺设备 Binder、签名和进程死亡证据 |
| 设备证据 | 0% | 有历史使用积累，但需按具体版本重新核验 | 有社区项目积累，但分支差异较大 | 当前项目差距仍主要在真实设备适配和回归规模 |

## 证据判断

M4-T17 已建立较完整的源码策略层和可测试 Native 边界。新增 syscall、Proc、Loader、Network 和 Audio 路径不再只是声明，均有可执行 Host-native test。ABI 架构也明确避开了单进程混合 32/64 位的错误前提。

VA/NBB 的主要优势仍是多年 Android 版本适配、Bionic/Linker 变体处理、设备问题修复和真实 App 样本积累。Controlled Sandbox 当前的四 ABI 结论仅是源码和构建配置完整，不能表述为四 ABI 已可运行，更不能表述为 Native 兼容水平已达到 VA/NBB。

## 当前项目实际完成度

- 能力条目：110。
- 源码：106 complete、4 partial，权重 98.2%。
- 生产接线：100 wired、8 partial、1 blocked、1 n/a，权重 95.4%。
- 设备：0 verified，权重 0.0%。

生产接线权重较 M4-T16 略有下降，原因是新增了两个明确标记为 partial 的 ABI 能力：四 ABI Android Packaging 和完整 32 位 Guest Runtime Transport。这个下降比把未完成能力标成 wired 更可信。

## 未完成项

1. Companion APK 的 Android SDK/NDK 实际构建和签名。
2. 32 位 Guest Activity、Service、Receiver、Provider 完整生命周期。
3. Cross-width Binder 在进程死亡、重启、APK 更新和实例删除时的恢复/清理。
4. Bionic、Linker、SELinux、OEM 的版本适配矩阵。
5. OpenSL ES、AudioSystem、VPN/Connectivity 和复杂 Socket Option。
6. 四 ABI 模拟器/真机测试、第三方 App 样本和 20 分钟稳定性。

## 下一阶段优先级

进入 M4-T18 源码总收口。优先审计 God Class、AIDL Bundle、Host 回落、Capability 身份、持久化事务、死亡清理和 APK Revision 清理；冻结设备测试前正式基线后，再执行四 ABI 构建和模拟器验证。
