# T57-M10 本地全量 Gate 扫描已发现问题与分类记录

> **说明与溯源（Provenance）**：
> 本文档为 T57-M10 本地 collect-all 全量 Gate 扫描过程中的**问题发现与状态分类记录**，并非修复完成报告。
> T57-M10 期间产生的所有临时代码修复、宏压缩、构建配置与 Gate 脚本修改均已在 T57-M10-R01 中**主动、彻底回滚**。
> 当前 main 分支生产代码严格保持在基线 `2d870252f25aeecfc08123f22dd5b6f1081d9a7b` 状态，未保留任何 M10 生产修复。

---

## 状态分类图例

- `CONFIRMED`: 确认存在且已复现的代码/配置偏离或超限，目前保持未修复状态（RECORDED / NOT FIXED）。
- `CONFIRMED_EXPECTED`: 经诊断确认属于既定架构设计的预期表现，无需修复（KEEP_AS_IS）。
- `NEEDS_REPRODUCTION_AND_CLASSIFICATION`: 仅在部分本地环境下暴露或涉及跨里程碑语义变更，后续需独立复现并定级。
- `NEEDS_GATE_AND_CODE_REVIEW`: 涉及 Gate 脚本自身平台适配性与生产代码边界的冲突，需统一审视。
- `NOT COMPLETED / NOT PROVEN`: 属于 T57 未完成能力与未证明项，继续保持未完成状态。

---

# 1. M4-T18 Source Closure (VirtualPackageMetadata.java)

* **分类**: `CONFIRMED`
* **目标文件**: `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/VirtualPackageMetadata.java`
* **规模/阈值**: 约 1908 行（当前 `check-m4-t18-source-closure.py` Gate 阈值为 1800 行）
* **状态**: `RECORDED / NOT FIXED`
* **详细分析与后续要求**:
  - 该类承载了 Virtual Package 元数据定义、PMS Info 对象映射以及 IntentFilter 匹配等功能，在 T57 演进后行数超出 1800 行上限。
  - 后续处置必须判定属于历史遗留还是 T57 增长导致；
  - 评估 Gate 阈值与当前框架架构契合度；
  - 若后续进行重构，必须按真实职责清晰拆分，**严禁使用代码格式压缩、删除必要注释或混淆来掩盖行数超限**。

---

# 2. native_interceptors.cpp Source Closure

* **分类**: `CONFIRMED`
* **目标文件**: `sandbox-native/src/main/cpp/native_interceptors.cpp`
* **规模/阈值**: 约 1838/1839 行（当前 `check-m4-t18-source-closure.py` Gate 阈值为 1600 行）
* **状态**: `RECORDED / NOT FIXED`
* **详细分析与后续要求**:
  - 此前 M10 曾使用宏压缩 `controlled_syscall` 派发分支以满足行数要求，**该方案已被全量回滚且不作为正式解决方案**。
  - 若后续处理，必须结合 T57 Native Boundary 架构设计进行真实职责 Review（例如按 syscall、文件、网络、进程生命周期等职责进行模块化解耦）；
  - **严禁通过宏化、格式折叠或删除 Native 拦截能力来规避行数 Gate**。

---

# 3. Gradle unsafe cross-project configuration resolution

* **分类**: `CONFIRMED` / `BUILD_DEBT` / `FUTURE_GRADLE_BLOCKER`
* **目标位置**: root `build.gradle` 中的 `resolveAndLockAll` 任务配置
* **现象**:
  - 在 Gradle 8.13 下，root 任务遍历子项目 configurations 触发警告：
    `Resolution of the configuration :app:androidApis was attempted from a context different than the project context`
    （涉及 `:app:androidApis`, `:app:androidJdkImage`, `:app:androidTestUtil` 等配置）。
  - 日志明确提示该行为在 Gradle 8.13 中已 Deprecated，将在 **Gradle 9.0 变为 Error**。
* **状态**: `RECORDED / NOT FIXED`
* **后续要求**:
  - 作为构建债务与未来 Gradle 9 升级阻塞项记录；
  - 后续应根据 Gradle 规范将配置解析调度至各子项目自身上下文，但本次回滚保持基线配置不变。

---

# 4. CXX5202 32-bit Native Architecture Warning

* **分类**: `CONFIRMED_EXPECTED`
* **涉及模块**:
  - `:fixture-compat32`
  - `:sandbox-companion32`
* **现象**:
  - NDK / AGP 输出 `WARNING: [CXX5202] This app only has 32-bit [armeabi-v7a,x86] native libraries.`
* **状态**: `KEEP_AS_IS`
* **详细分析**:
  - 经 T57-M08 详细诊断，此警告源于 Controlled Android Sandbox 刻意设计的 32-bit-only cross-ABI companion / fixture 架构，用于支撑 64 位主进程与 32 位 Guest App 之间的跨架构 IPC 协同。
  - 属于正常预期的架构警告，不属于待修复缺陷。

---

# 5. GuestNativeRuntimeProjection durability Gate

* **分类**: `NEEDS_REPRODUCTION_AND_CLASSIFICATION`
* **来源**: T57-M10 全量扫描 `scripts/check-m5-t19-1-s-durable-atomic-persistence.py`
* **目标文件**: `sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestNativeRuntimeProjection.java`
* **状态**: `RECORDED / NOT FIXED`
* **详细分析与后续要求**:
  - 扫描记录曾发现类中包含 `AtomicMoveNotSupportedException` 与 `Files.move` 局部 fallback，触发原子持久化 Gate 检查。
  - M10 期间引入 `DurableAtomicFile` 的修改已被回滚。
  - 后续必须结合运行时 `dlibs` 与 overlay 数据生命周期语义进行严格审视，重新复现并确定正确处理方向，禁止直接盲目套用 M10 修改。

---

# 6. HOST_PACKAGE_HIDDEN / ownership Gate

* **分类**: `NEEDS_REPRODUCTION_AND_CLASSIFICATION`
* **来源**: T57-M10 全量扫描 `scripts/check-m4-t18-ownership-cleanup.py`
* **目标文件**: `sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/packagemanager/PackageManagerInvocationHandler.java`
* **状态**: `RECORDED / NOT FIXED`
* **详细分析与后续要求**:
  - 扫描记录显示 Gate 依赖的旧标记 `HOST_PACKAGE_HIDDEN` 与 T57 演进后的 `HiddenPackageResultMapper` 存在标记不一致。
  - M10 添加的注释标记已被回滚。
  - 后续必须首先核对 T57 已确立的：
    1. Virtual Package Universe 包可见性模型；
    2. Hidden package fail-closed 策略；
    3. Android 系统 API 返回类型映射（如 `NameNotFoundException` / `null`）；
  - **严禁重新引入旧的异常抛出语义破坏现有行为**。

---

# 7. Internal Bundle Boundary Gate

* **分类**: `NEEDS_GATE_AND_CODE_REVIEW`
* **来源**: T57-M10 全量扫描 `scripts/check-m5-t19-1-p-internal-bundle-boundary.py`
* **状态**: `RECORDED / NOT FIXED`
* **详细分析与后续要求**:
  - M10 期间曾调整该 Gate 中的子进程编码参数，该修改已被回滚。
  - 后续必须首先明确区分：生产代码是否存在真实的 Bundle 跨边界泄漏，还是仅为 Gate 脚本在特定宿主环境下的编码/探测逻辑冲突；
  - **严禁为了让生产代码通过测试而弱化 Gate 的安全检测能力**。

---

# 8. T57 核心未完成能力与未证明项

* **分类**: `NOT COMPLETED / NOT PROVEN`
* **状态**: `UNCHANGED / NOT PROVEN`
* **详细说明**:
  - T57-M10 及其回滚操作**绝不改变** `docs/runtime/T57_R02_ARCHITECTURE_HANDOFF.md` 中记录的未完成状态与待证明项。
  - 核心未完成/未证明项依然包括：
    1. Ordinary 64-slot 高位槽位、耗尽、高并发与生命周期 recycle 真实验证；
    2. Native direct syscall / raw SVC / custom ELF loader 对抗性恶意代码边界；
    3. SystemService 完整 Android Framework 语义覆盖；
    4. PendingIntent 在 SystemUI / AlarmManager / NotificationManagerService 跨进程多端矩阵的真实行为；
    5. Framework fallback 全路径端到端证明；
    6. ContentProvider 高并发压力、FD 泄漏、UriGrant 撤销与 ANR 防护；
    7. ClassLoader split / dynamic feature plugin / linker namespace 隔离证明；
    8. SIGSEGV / Native abort / ANR / LMK / Process death 异常恢复证明；
    9. Package upgrade / rollback / clone / identity reset 一致性证明；
    10. Android API 33–36 跨版本行为矩阵；
    11. OEM 厂商深度定制（MIUI / ColorOS / OriginOS 等）兼容性；
    12. VirtualApp / NewBlackbox 同设备 A/B 对比证据。
  - 以上所有项目均继续保持：**NOT COMPLETED / NOT PROVEN**。
