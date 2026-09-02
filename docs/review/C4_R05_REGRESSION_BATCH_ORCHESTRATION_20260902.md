# C4-R05 回归批次编排说明

日期：2026-09-02  
任务：`C4-R05`  
范围：MuMu `RD测试`、同一 clean commit、两轮正式矩阵之后的强制回归

## 决策

R05 的总编排器保留一个正式验收入口，但将回归阶段组织为两个连续批次：

1. `c1-c2-c4`
   - C1 Activity：`run_c1_t01_rd.py --loops 50`
   - C2 Window/Audio：`run_c2_t05_rd.py --loops 10`
   - C2 Device Audio：`run_c2_t06_rd.py --loops 20 --clone-loops 10`
   - C4 CAS-only：`run_c4_t04_rd.py`
2. `sx-f1-f5-business`
   - SX F1-F5：`run_c4_t05_rd.py`

“合并”仅表示编排和批次边界合并，不合并验收语义。每个子门仍保留自己的命令记录、stdout、stderr、summary、原始证据和失败分类；任一子门非零返回或摘要非 `PASS` 时，当前批次立即停止，后续批次不执行。

## 为什么不并行

这些回归共享同一个动态解析的 MuMu `RD测试` 设备、安装事务和用户状态。并行运行会让 Window、Audio、CAS 事务和 Activity 的失败边界相互污染，也会破坏首次失败证据。因此按一个批次连续执行，不使用额外重试或固定 sleep 来缩短时间。

## 兼容性和完成条件

- C1、C2、C4 的既定 case 数、deadline 和 fail-closed 门槛不降低。
- SX 仍是独立批次，不能用 C1/C2/C4 的通过替代。
- 历史 C1 矩阵或旧 commit 结果不替代当前 R05 clean-commit 回归。
- 两个批次和 R05 的两轮正式矩阵、双用户短测全部通过后，才允许写入 R05 DONE 回执。
