# M4-T18 与 VirtualApp／NewBlackbox 对比报告

## 对比口径

本报告比较设备测试前源码基线，不把 README 声明、Host self-test 或静态编译等同于真实 Android 兼容性。Controlled Sandbox 的结论分为源码能力、生产接线和设备证据。VA/NBB 的判断只作为成熟方案的能力参照，具体分支、Android 版本和 OEM 仍需重新核验。

## 本迭代新增结果

M4-T18 没有扩展新的 Android 功能面，主要新增：

- 大型状态类职责拆分；
- 有界、校验、可隔离的持久化层；
- AIDL Bundle 冻结门禁；
- 12 个资源域的容量、回滚、死亡清理和 Revision 清理审计；
- Package/Task/System Service 查询禁止宿主回落；
- 设备测试前机器可读清单和最终冻结门禁。

## 能力对比

| 维度 | Controlled Sandbox M4-T18 | VirtualApp | NewBlackbox | 判断 |
|---|---|---|---|---|
| 源码结构 | Domain/Contract/Framework/Runtime/Native/App/Companion 分层；大型类受阈值约束 | 历史代码规模大，分支间结构和维护质量差异明显 | 较现代但不同 fork 的模块边界差异明显 | 当前项目在可审计性上较强，成熟度仍需设备证据支撑 |
| AIDL/IPC 类型安全 | 新业务契约默认 typed Parcelable；13 个遗留 Bundle 精确冻结 | 历史实现常包含较多 Bundle/反射兼容路径 | 依分支存在 Bundle 与内部对象混用 | 当前项目的契约治理更严格，但覆盖面和版本适配积累更少 |
| 状态持久化 | 原子写、CRC、容量、损坏隔离、回滚门禁 | 成熟实现有长期运行修复，具体持久化策略依分支 | 通常具备包/虚拟状态持久化 | 当前项目源码边界清晰，设备掉电/磁盘故障尚未验证 |
| 资源所有权 | Session/Generation/User/Package/Revision 绑定，资源域有死亡和清理审计 | 具有多年 AMS/PMS/组件资源生命周期积累 | 现代分支通常覆盖更多系统服务生命周期 | 当前项目可审计，但真实 Binder/系统进程行为差距仍大 |
| Host 数据隔离 | Package、Task、虚拟系统服务查询失败时 fail-closed | 成熟方案通常做广泛身份替换和查询过滤 | 通常有较广系统服务 Hook | 当前项目避免兜底泄露，尚缺 Android Binder 签名矩阵 |
| Activity/Task | Broker 状态、恢复、查询和基础 Framework 投影已接线 | 长期 AMS/ATMS、Window/Task 兼容积累更强 | 通常具有更广 Android 版本适配 | Window/Transition/System Recents/OEM 差距明显 |
| Service/Receiver/Provider | 主要源码生命周期和资源清理已接线 | 成熟组件兼容覆盖广 | 现代 fork 通常覆盖较广 | Ordered Broadcast、FGS、isolated process 和设备回调仍有差距 |
| 调度与通知 | PendingIntent、Alarm、Notification、Job typed 状态与恢复已接线 | 多年系统服务代理经验 | 通常覆盖现代系统服务 Hook | Doze、配额、SystemUI、OEM 仍未验证 |
| Native/ABI | 文件/Proc/Loader/Network/Audio 策略；64 位 Host + 32 位 Companion 架构 | 多年 Bionic/Linker/ABI 问题积累 | 通常具有较广 Native Hook 和双架构方案 | 当前最大差距是四 ABI 真实构建、运行和版本矩阵 |
| 自动化证据 | 强制源码/Host/Native/可复现门禁，设备状态不能伪装为 PASS | 历史项目通常缺少统一现代证据矩阵 | 依分支测试质量差异较大 | 当前项目证据纪律较强，设备证据仍为 0 |

## 当前实际完成度

<!-- M4_T18_MATRIX_STATS_START -->
- 能力条目：113。
- 源码：109 complete、4 partial，权重 98.2%。
- 生产接线：103 wired、8 partial、1 blocked、1 n/a，权重 95.5%。
- 设备：0 verified、109 not-tested、1 blocked、3 n/a，权重 0.0%。
<!-- M4_T18_MATRIX_STATS_END -->

## 哪一方证据更强

- **源码边界、契约治理和证据可追溯性**：Controlled Sandbox 当前仓库证据更集中、规则更明确。
- **Android 版本适配、真实 App 样本和长期设备问题处理**：VA/NBB 的历史积累明显更强。
- **是否已经达到 VA/NBB 兼容水平**：现有证据不支持。设备证据为 0，不能从接近 98% 的源码矩阵推导真实兼容率。

## 主要差距

1. Android 12～16 及 OEM 的 AMS/ATMS/PMS/System Service Binder 签名。
2. Window、Transition、Recents、FGS、后台限制和广播队列。
3. isolated/remote process 和完整 32 位 Guest 运行。
4. Bionic、Linker Namespace、RELRO、SELinux 和四 ABI Hook。
5. WebView 多进程、音频服务、VPN/Connectivity。
6. 第三方 APK 样本、长期稳定性和回归基础设施。

## 下一阶段优先级

下一阶段应进入设备测试，不再继续用源码功能堆叠替代真实证据：

1. 锁定 AGP/NDK 构建并生成 Host/Fixture/Companion APK。
2. 官方模拟器先验证 x86_64 Host 与 x86 Companion。
3. 再验证 ARM64/ARM32 设备或对应模拟环境。
4. 按组件链路逐项建立失败清单和修复迭代。
5. 最后执行 20 分钟零 Crash/ANR 门禁。
