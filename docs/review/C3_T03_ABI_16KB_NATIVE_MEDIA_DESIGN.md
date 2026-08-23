# C3-T03 四 ABI、16 KB page 与 native Camera/Media 设计

## 1. 范围与证据边界

C3-T03 只关闭四 ABI 产物、ELF 装载约束、Companion32 身份和 package-neutral native
buffer/Surface/codec 证据缺口。静态产物能力、RD 测试能力和 ARM/16 KB 环境能力分开
记录：RD 测试即使通过，也不能升级为 ARM、16 KB、API33+、OEM 或 VA PRO 等价证明。

本任务接受的动态基线是由 `scripts/mumu_instance.py` 根据实例名动态解析的 `RD测试`。
执行器不得包含历史 ADB serial；每次回执保存解析到的 model、API、ABI、boot id、Android
id 和 page size，便于发现设备漂移。

## 2. DISCOVER / CLASSIFY 结论

已有代码包含 Host 的 arm64-v8a/x86_64、fixture64、fixture32 和 Companion32 路由，
也已有 Camera2 ImageReader 和 `ANativeWindow` JPEG 适配。但在任务开始时缺少：

* 对四类 APK 中每个 packaged `.so` 的 ELF class/machine、`DT_NEEDED` 和 `PT_LOAD`
  对齐的机器可读报告；
* 将 late `dlopen`、native buffer/Surface、native codec 和清理结果相关联的独立
  C3-T03 fixture/runner；
* Companion32 revision/identity 与 cross-width native load 的同一份回执；
* 对 ARM32、ARM64 和 16 KB page runtime 的显式环境阻断记录。

因此记录：

* `KI-R03-042`：`TEST_EVIDENCE_GAP`，由本任务的静态报告、动态 fixture 和回执关闭；
* `KI-R03-043`：`ENVIRONMENT_BLOCKED`，保留为已记录环境缺口，不得因 x86/4 KB RD
  通过而关闭。

## 3. 静态产物门

`scripts/check-c3-t03-abi-elf.py` 读取锁定的 `deviceLabBuild` 四 APK，验证：

1. APK ABI 集合和必需 native library 与 `build-environment.lock.json` 一致；
2. ELF magic/class/machine 与 ABI 一致；
3. 所有 packaged ELF 的 `PT_LOAD.p_align` 至少为 `0x4000`，且满足
   `p_offset % 0x4000 == p_vaddr % 0x4000`；
4. `DT_NEEDED` 只引用 linker 可解析的 soname，不包含绝对路径或目录穿越；
5. fixture 的 C3-T03 native media activity、late-load 目标和四 ABI CMake link option
   均存在；
6. 执行器通过 `RD测试` 实例名解析设备，不出现历史 serial 常量。

各 native CMake target 使用 `-Wl,-z,max-page-size=16384`，让 4 KB runtime 上可运行的
产物同时满足 16 KB linker alignment 静态要求。该静态事实不等同于 16 KB kernel runtime
可用；动态报告仍以设备实际 `getconf PAGE_SIZE` 为准。

## 4. 动态 fixture 与路径

`C3T03NativeMediaActivity` 在 fixture64 和 fixture32 APK 中复用同一份 Java/native
源码，依次执行并将结果写入应用 files：

* 编译 ABI、runtime page size 和 late `dlopen` marker；
* 用 `ImageReader` 创建真实 `Surface`，调用 native `ANativeWindow` buffer lock/write/
  unlock/post，再由 ImageReader acquire/close 一帧；
* 用 NDK `AMediaCodec` 创建 AVC encoder，完成 format/configure/start、input queue、
  output dequeue/release、stop/delete；不可用的 codec 只标成环境/能力缺口并保留错误，
  不伪造 PASS；
* finally 路径关闭 Image、Surface、ImageReader、codec 和线程资源，记录 cleanup。

runner 依次验证：

* fixture64 direct native path；
* fixture32 direct native path；
* Host → Guest sandbox native path；
* Companion32 的动态解析、identity/revision、native load 和 process-generation
  recovery（调用已有的实例名解析 cross-ABI probe）。

每个动态结果必须包含 expected/observed ABI、Surface/image/codec/late-load 状态、
page size、context 和 log trace。RD API32 的 x86/x86_64 PASS 只表示该可用设备路径。

## 5. 环境阻断与验收映射

若设备 page size 不是 `16384`，报告写入
`verification/catch-up/C3-T03/c3-t03-environment-block.json`，状态为
`ENVIRONMENT_NOT_AVAILABLE`；若没有 ARM runtime，则 ARM32/ARM64 写入
`UNVERIFIED_RUNTIME`。两者都不能作为 dynamic PASS，也不阻断四 ABI 静态门和可用 RD
路径的验收。

C3-T03 只有在四 APK 静态门、可用 RD direct/sandbox native media 路径、late dlopen、
Companion32 cross-width identity/load/recovery 均有 PASS，并且环境缺口明确记录后，才可
回执为 DONE。回执必须引用：

* `verification/catch-up/C3-T03/c3-t03-abi-report.json`；
* `verification/catch-up/C3-T03/c3-t03-rd-summary.json`；
* `verification/catch-up/C3-T03/c3-t03-local-verification.json`；
* `verification/catch-up/C3-T03/c3-t03-environment-block.json`；
* `artifacts/capability-audit/catch-up-c3-t03/<timestamp>/` 下的原始 log/trace。

失败应先修复并重跑；只有必须增加外部设备或人工授权时，才在回执中记录 BLOCKED。
