# 第 2 步讲解：恢复语义与副作用 exactly-once

> 对应代码：`effect/`、`checkpoint/`、`engine/`、`shell/`
> 覆盖性质：**PC / EO / CV / RD**（论文 arXiv:2608.03836 的四条）
> 面试定位：**这一步是全项目技术含量最高的部分**。前面是地基，这里才是真正解决问题。

---

## 1. 一句话

> 我引入了一个**效果账本**，用「**抢占 + 同事务执行**」把非幂等副作用保护起来 ——
> 计划 1 复现出来的「崩溃后副作用执行 2 次」现在变成了 **1 次**。

**这个 2 → 1 就是本步的全部价值**，也是你能写在简历上的量化数字。

---

## 2. 故障场景

延续第 1 步的退款例子：

1. Agent 决定创建一台主机（**花钱、不可逆**）
2. 主机创建成功
3. 就在这一刻进程被 `kill -9`
4. 重启 → 重跑 → **又创建一台**

**现在的问题不是「日志写没写」，而是「日志根本没来得及写，系统无从知道那台主机已经创建过了」。**

所以修复方向不是「更小心地写日志」—— 那永远是死路，因为崩溃窗口天然存在于「做完了」和「记下来了」之间。真正要改的是：**让副作用的发生本身是可查的。**

---

## 3. 核心设计：效果账本

### 3.1 claim-then-execute

```
BEGIN TRANSACTION
  ① INSERT effect_ledger (workflow_id, step_no, status='CLAIMED')
     ← 主键唯一索引保证同一位置只有一个赢家
  ② 在【同一个连接】上执行副作用（创建主机）
  ③ UPDATE effect_ledger SET status='DONE', result=?
COMMIT
```

**关键在于 ② 和 ③ 在同一个事务里。**

- 如果在 ② 之后、③ 之前崩溃 → 整个事务回滚 → 位置重新可用 → 重跑时正常抢占。**没有重复副作用。**
- 如果 ③ 提交后崩溃 → 重启时 ① 撞唯一键 → 读到 `DONE` 和结果 → **直接复用，不再触发副作用。**

两种崩溃都被堵死了。

### 3.2 为什么必须同事务（这是最重要的一句话）

**如果先执行副作用、再单独写账本**，那么崩溃落在两者之间时：
副作用已发生，账本没记录 → 重启时账本说"没做过" → **重复执行**。

这就是计划 1 那个缺陷的本质。所以 `TransactionalEffect` 接口的签名是 `String run(Connection conn)` —— **它被迫使用调用方给的事务**，而不是自己开连接。类型系统在这里帮我们守住了正确性。

> 面试时这句话可以直接说：**"我把连接作为参数传进副作用，这样它就不可能脱离事务。"**

### 3.3 ⚠️ 诚实的边界：外部副作用做不到 exactly-once

**这一点必须主动讲，否则被追问会很被动。**

上面这套成立的前提是：**副作用发生在同一个数据库里**，所以能和账本同事务。

如果副作用在**外部系统**（调支付网关、发短信、创建云主机）：
- 同事务**不可能** —— 跨网络的原子提交不存在
- 只能退化为 **at-least-once + 幂等键 + 对方幂等接收**

我专门去核对过 **Temporal 官方架构文档**，原文是：

> "Workflow code **must be deterministic and have no side effects**... and **activity code must either be idempotent or non-retryable (i.e. at least once or at most once)**."

也就是说 **Temporal 自己也不保证副作用的 exactly-once**，它只提供 at-least-once（可重试）或 at-most-once（不可重试），要求你把活动做成幂等。

**所以正确的表述是**：exactly-once 不是一个能单方面保证的性质，它是**「至少一次投递」+「接收方幂等」的组合结果**。我项目里能做到真正的 exactly-once，是因为副作用和账本共享同一个事务。

> 能主动说出这个边界，比声称"我实现了 exactly-once"要可信得多。

---

## 4. 四条性质各自怎么落地的

### PC（prefix continuation）—— 允许重跑前缀

论文原文：
> "**Memoized replay conforms**: prefix code **may re-run** during recovery provided every prefix effect is served from the durable record (so EO is preserved) and the re-derived state is a pure function of the log."

**这条让我改掉了原本的设计。** 我一开始以为需要精确计算断点、跳过已完成的步骤；论文明确说可以重跑前缀，只要副作用从持久记录取。

于是实现简单了很多：**每次恢复都从头走一遍，副作用由账本拦住。**

**这里我踩了一个真实的坑**：恢复时重跑第 0 步 → 又往日志里写一条 `STEP_RESULT` → **撞主键，抛异常**。

修法不是"先查再写"（那是 TOCTOU 竞态），而是**直接写、捕获重复键**：

```java
try {
    journal.append(...);
} catch (DuplicateJournalEntryException ignored) {
    // 恢复时重跑前缀的正常情况
}
```

而且**重复时保留先写入的那条** —— 日志是权威来源，重跑得到的新值不应覆盖历史。这正是「状态是日志的纯函数」的含义。

### EO（effect exactly-once）—— 本质是 at-most-once

论文原文：
> "As a safety invariant **EO is at-most-once**; the 'exactly' is supplied by pairing with the **liveness obligation**."
> "**an effect that commits while its acknowledgment is lost counts as fired**"

后半句就是我们那个崩溃窗口的学术表述 —— 副作用已提交、确认丢失。论文说它的补救手段是**幂等键**，而我们的账本就是幂等键的具体实现。

### CV（checkpoint validity）—— 非法状态必须写不进去

论文原文：
> "a write that would persist schema-invalid state is **rejected with an error, not stored**"

我的做法是把不变量放在 **record 的紧凑构造器**里：

```java
if (results.size() != frontierStep + 1) {
    throw new InvalidCheckpointException(...);
}
```

**非法检查点根本无法被创建出来**，所以永远走不到持久化那一步。这是 CV 最强的形式。

> 面试可对比：论文实测 **LangGraph 1.2.9 "persists schema-invalid state silently"** —— 它写进去了但不报错。我这里是构造即拒绝。

### RD（recovery determinism）—— 恢复决策是纯函数

论文原文：
> "The recovery decision (which tasks to skip versus re-execute) is a **function of durable state**: two recoveries from identical durable logs make identical decisions."

`RecoveryPlan.from(journal, workflowId, totalSteps)` 是**纯函数**：不读时间、不用随机、不碰外部。
测试直接算两次计划断言相等。

---

## 5. 实测数据（全部为真实输出）

```
[真实输出] 崩溃后 host 行数 = 1
[真实输出] 崩溃后账本已执行数 = 1
[真实输出] 崩溃后日志行数 = 0          ← 崩溃窗口确实存在
[真实输出] 恢复后 host 行数 = 1        ← EO：没有重复创建
[真实输出] 恢复后账本已执行数 = 1
[真实输出] 恢复时 effectsReused = 1    ← 副作用从账本复用
[真实输出] 重启后副作用总次数 = 1      ← 计划 1 时这里是 2

[真实输出] 基线状态   = [{"step": "create-host", ..., "value": "host-1"}, ...]
[真实输出] 恢复后状态 = [{"step": "create-host", ..., "value": "host-1"}, ...]   ← PC：完全一致

[真实输出] 恢复后 journal 表行数（应等于 2，不是 3） = 2   ← 重跑不产生重复日志

[真实输出] 第一次计划 = resumeFrom 2, completed [0, 1]
[真实输出] 第二次计划 = resumeFrom 2, completed [0, 1]     ← RD：两次决策一致

[真实输出] 拒绝原因: results 数量与 frontierStep 不一致: frontierStep=5 期望 results.size()=6 实际=1
[真实输出] 拒绝后 checkpoint 行数 = 0                        ← CV：一个字都没落库
```

汇总：**34 个测试全部通过**。

---

## 6. 面试追问清单

| # | 追问 | 回答要点 |
|---|---|---|
| 1 | 崩溃窗口为什么堵不住？ | 因为「做完了」和「记下来了」之间有天然的物理间隔，任何先做后记的方案都有这个窗口 |
| 2 | 那你怎么解决的？ | 让副作用的发生本身可查：claim + 同事务写账本 |
| 3 | 为什么副作用必须和账本同事务？ | 否则崩溃落在两者之间，账本说"没做过"，重启就重复执行 |
| 4 | 你怎么在代码层面保证同事务？ | `TransactionalEffect.run(Connection)` 把连接作为参数传入，副作用不可能自己开连接 |
| 5 | ⭐ **外部系统（支付网关）也能 exactly-once 吗？** | **不能。** 跨网络没有原子提交，只能 at-least-once + 幂等键 + 对方幂等接收。Temporal 官方文档也是这么说的 |
| 6 | 恢复为什么要重跑前缀，不跳过？ | 论文明确允许重跑（memoized replay），只要副作用从持久记录取。跳过需要精确断点，更复杂且不必要 |
| 7 | 重跑会不会在日志里写重复？ | 会尝试写，但被唯一约束拒绝，我捕获这个异常当作"已记录"。**不用先查再写，那是 TOCTOU 竞态** |
| 8 | 重跑产生的新值会覆盖旧值吗？ | 不会。日志是权威来源，先写入的胜出 |
| 9 | CV 性质你怎么实现的？ | 不变量放在 record 构造器里，非法检查点**创建不出来**。LangGraph 是静默写进去 |
| 10 | RD 怎么测？ | `RecoveryPlan.from()` 是纯函数，同一份日志算两次断言相等 |
| 11 | 账本的 CLAIMED 状态有什么用？ | 标记"已抢占但副作用还没完成"。正常路径下它存在的时间极短；如果它是崩溃残留，事务回滚会清掉 |
| 12 | 并发下两个进程同时抢同一个位置会怎样？ | InnoDB 在唯一索引上阻塞第二个 INSERT，直到第一个提交（第二个拿到重复键，读到 DONE 复用）或回滚（第二个抢占成功）。**这是计划 3 要用测试锁定的** |

---

## 7. 边界（不要声称做了）

**已做到：**
- PC / EO / CV / RD 四条性质的实现与测试
- 效果账本的同事务语义
- 计划 1 缺陷的修复（2 → 1）

**还没做：**
- ❌ **FD / CO / FI 三条**（围绕人工审批中断），计划 3
- ❌ **并发场景**：目前只有单进程验证；论文实测主流框架在并发恢复下会让副作用执行 k 次
- ❌ **真实进程 kill -9**：现在用的是应用内故障注入
- ❌ 增量检查点（O(N²) 存储问题）
- ❌ 外部副作用 + 幂等键的完整实现（只在文档里说明了边界）

> 第 1 条和第 2 条是面试最可能被追问的方向，**主动说出来**比被问出来好。
