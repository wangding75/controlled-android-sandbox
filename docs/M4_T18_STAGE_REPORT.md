# M4-T18 阶段报告：设备测试前源码总收口

## 基线与范围

- 正式起点：`2b97734f6a86e947ec2e2b2bfad0b528e6658f55`（M4-T17）。
- B1：`7d45947`。
- B2：`41b2f62`。
- B3：最终文档与门禁通过后冻结。
- 本阶段未执行模拟器、真机或第三方 APK 测试。

M4-T18 没有新增新的业务兼容目标。工作集中在结构、契约、状态安全、证据一致性和设备测试前冻结。

## B1：结构、持久化与契约收口

- 将 `VirtualSystemServiceStore` 的 JSON 编解码和原子文件持久化拆为独立组件。
- 增加 8 MiB 载荷、12 MiB 文件、CRC32、临时文件原子替换和损坏隔离。
- 保留 schema 1～5 读取兼容。
- 将 Activity/Task 可变状态模型从 `ActivityTaskLedger` 拆出。
- 冻结 13 个遗留 AIDL `Bundle` 业务方法；新增未批准 `Bundle` 方法会直接失败。
- 对生产 Java/Kotlin 和 Native 单文件建立强制行数阈值。

## B2：所有权、回滚与清理收口

- 建立覆盖 12 个资源域、3 个 Guest 查询面的机器可读生命周期审计。
- 为 Broker transient、Session、Activity/Task、Service、Provider、Capability、PendingIntent 等状态补齐容量上限。
- `RecoverableFileStore` 增加读写大小上限并保留 backup-first 回滚。
- 修复 Job 启动和 Binder death 失败时的精确持久化补偿。
- PackageManager、Activity/Task 和虚拟系统服务查询失败时不回落宿主数据。
- 将 Binder/process death、实例删除和 APK Revision 清理写入自动化门禁。

## B3：最终证据与冻结

- 对最大生产类、重复 helper、AIDL、Host fallback、身份绑定和持久状态再次全仓审查。
- 新增最终冻结清单和门禁，确保计划、报告、能力矩阵和设备状态一致。
- 生成设备测试前未完成能力清单和建议验证顺序。
- 重算仓库规模和 VA/NBB 对比矩阵。

## 验证结果

最终门禁覆盖：

- 架构和模块边界；
- Typed AIDL、遗留 Bundle 冻结；
- 资源容量、回滚、死亡清理、Revision 清理；
- Guest 查询禁止宿主回落；
- M4-T14 Service、M4-T15 Activity/Task、M4-T16 系统调度、M4-T17 Native/ABI 回归；
- 静态 Android 编译与全部 Host self-test；
- Native/JNI tests；
- M3 严格证据门禁；
- 双次可复现源码 ZIP；
- Shell、Python、PowerShell 静态检查。

## 仓库指标

<!-- M4_T18_STATS_START -->
| 项目 | 数量 |
|---|---:|
| Git 跟踪文件 | 714 |
| Java 文件 | 431 |
| AIDL 文件 | 45 |
| Java + AIDL 行数 | 51,999 |
| M4-T17 → M4-T18 变更文件 | 49 |
| 新增／删除行 | 2,904／703 |
| 能力条目 | 113 |
| 源码 complete／partial | 109／4 |
| 源码加权完成度 | 98.2% |
| 生产 wired／partial | 103／8 |
| 生产 blocked／n/a | 1／1 |
| 生产加权完成度 | 95.5% |
| 设备 verified | 0 |
| 设备证据完成度 | 0.0% |
<!-- M4_T18_STATS_END -->

以上统计是源码和证据矩阵结果，不代表第三方 APK 启动率。

## 质量判断

M4-T18 的有效结果是将此前分散的质量约束转成可执行门禁：

1. 大型类已完成风险可控的职责拆分，并有阈值防止重新膨胀。
2. 遗留 Bundle 契约数量被精确冻结，不能继续增加无类型业务载荷。
3. 资源所有权、容量、回滚、死亡清理和 Revision 清理拥有机器可读证据。
4. Guest 查询面采用 fail-closed，不把宿主数据作为失败兜底。
5. 设备未验证项与生产未接线项由能力矩阵和 preflight manifest 双向校验。

## 仍未完成

- Ordered Broadcast 完整平台语义。
- isolated/remote process 的真实运行路径。
- 完整 Foreground Service 限制。
- Native Network/Loader 版本矩阵。
- 32 位 Companion 完整 Guest 生命周期。
- Android 工具链构建、四 ABI APK/ELF 证据。
- 模拟器/真机 API 与 OEM 适配。
- 第三方 APK 样本兼容率和 20 分钟稳定性。

详细清单见 `docs/M4_T18_DEVICE_PREFLIGHT_GAPS.md`。

## 阶段结论

M4-T18 可在全部本地门禁通过后认定为“设备测试前源码基线 PASS”。该结论只说明源码可追溯、可复现、约束可执行，不能说明 Android 设备运行已经通过。
