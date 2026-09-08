# P1-04 启动期 Application / LoadedApk / Context 回执

状态：**已完成（2026-09-08；FIXTURE_PASS）**。本任务不宣称 Chrome 或小米设备通过。

```text
TASK_ID / STATUS / SCOPE
P1-04 / FIXTURE_PASS / API36 AVD 上的 AppComponentFactory、LoadedApk、Application、Provider bootstrap；不包含 Chrome ARM64/OEM。

CAS_HEAD / DIRTY_DIFF_HASH / HOST_APK_SHA256 / FIXTURE_APK_SHA256
40f629d03bbb0927f171a0e318c242398d8a141d + P1-01..P1-04 未提交工作区 / 运行前以 git diff --check 验证 /
2c1aef39f6b0e8780d5ea9c5e9ca214d6793e69bd00b6d425087b1f89018ff94 /
v1 a09be69e3327cb50de8e5f2a08701c99a610748cced4cb06fe24f2a607023374；
恢复 v3 910c4c97d49e7c2dbc343578d96eb31d38c723964477cc414343f82ef165db5a。

REFERENCE_PROJECT / REFERENCE_COMMIT / FILE:LINE / METHOD
NewBlackbox / local reference / BActivityThread.java:358 / handleBindApplication；
VirtualApp OSS / local reference / VClientImpl.java:316 / bindApplicationNoCheck。

REFERENCE_BEHAVIOR / CAS_BEFORE / CHOSEN_CONTRACT / REJECTED_ALTERNATIVES
两参考均按 ApplicationInfo/LoadedApk → Application → Provider → Application.onCreate 收敛；
CAS 已具备同一路径但失败仅有摘要、没有 bootstrap 首错阶段或 cause 链；
现在每个边界输出 request/session/generation 绑定的 GUEST_BOOTSTRAP_STAGE，并在失败返回
launchStage 和最多 8 层因果栈。未采用 null guard、第二个 Application 或 host Context 兜底。

REPRO_CASE / NATIVE_BASELINE / EXPECTED / ACTUAL_BEFORE / ACTUAL_AFTER
Lifecycle v1 factory/Application/Provider fixture / PASS /
factory application 调用、attach、Provider onCreate、Application onCreate 各一次，Provider 早于 onCreate /
历史 Chrome 仅有 Object.getClass() NPE 文本，无首错栈 /
原生和 CAS marker 均为 P1_04_BOOTSTRAP_PASS；CAS 3 cold + 3 hot 全部 LAUNCH_PASS。

DEVICE_FINGERPRINT / API / ABI / PAGE_SIZE / SETTINGS
google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys /
36 / x86_64 / 4096 / emulator-5554，attempt=1，diagnostic retry=false。

APP_PACKAGE / VERSION / BASE_SPLITS_SHA256 / DEPENDENCY_PROVIDER_VERSIONS
com.warden.controlledsandbox.fixture.lifecycle / v1 1.0、v2 expected-failure、v3 3.0 recovery /
single base APK / none。

REQUEST_ID / SESSION_ID / GENERATION / FIRST_FAILURE
p1-04-failure-v2-launch / dfd0af5a-2115-42cf-871e-bb536375937e / 1 /
APPLICATION_ONCREATE: IllegalStateException P1_04_EXPECTED_APPLICATION_ONCREATE_FAILURE。

REQUIRED_CASES_PASS_FAIL / RAW_FAILURES / RECOVERY_RESULTS / NOT_TESTED
v1 normal: native 1/1 PASS；CAS 3 cold + 3 hot = 6/6 LAUNCH_PASS /
v2 expected first failure retained; no retry /
v1 downgrade was correctly rejected (1 < 2); v3 normal revision then CAS 3 cold + 3 hot = 6/6 PASS /
Chrome current launch and ARM64/OEM stack not run; Chrome handoff is P2-02.

REGRESSIONS / EVIDENCE_PATHS / KI_MAPPING / NEXT_TASK
Gradle fixture/app builds, static_android_compile.py and git diff --check PASS /
out/verification/p1-04-bootstrap-api36-3cold3hot-final-20260908;
out/verification/p1-04-bootstrap-api36-recovery-v3-final-20260908;
P1-04_REFERENCE_DECISION.md; historical Chrome E02 retained /
REALAPP-COMPAT-02, C1-T01/ClassLoader / P1-05.
```

## 证据边界

`LifecycleComponentFactory` 显示框架 `LoadedApk` 可独立构造一个 factory；这不是第二个
Application。验收断言的是 factory **创建 Application 一次**、Application attach/onCreate 各一次、
Provider 一次及正确顺序。v2 的故障用于证明原始 cause/阶段与清理；v3 是版本递增恢复，避免以
降级或清空数据伪造成功。Chrome 的历史 NPE 仍为根因未知，P2-02 需要在目标 ARM64 环境采集新栈。
