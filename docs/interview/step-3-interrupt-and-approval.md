# 第 3 步讲解：中断与审批闸门

> 对应代码：`interrupt/`
> 覆盖性质：**FD / CO-c / CO-e / FI**（论文 arXiv:2608.03836 的四条）
> 面试定位：**这是全项目最能打的部分**。第 2 步的 EO 需要你解释"同事务"，这一步直接有一个可复现的、主流框架都失败的对照实验。

---

## 1. 一句话

> 我给高风险的 Agent 操作加了一道人工审批闸门，用**数据库条件更新**保证一次审批只能被消费一次 ——
> 并发 8 个请求同时审批，只有 1 个生效，而论文实测主流框架在这种场景下会**执行 k 次**。

---

## 2. 故障场景

Agent 要删一台生产主机。这个操作不可逆，所以：

1. Agent 走到这一步 → **挂起**，写入中断记录，等人审批
2. 审批人在界面上点了「同意」
3. 但因为网络重试 / 用户双击 / 消息重复投递，**同一个审批请求到达了 3 次**
4. 如果没有保护 → **删了 3 次**（或者更准确地说：删了 1 次，因为第二次就找不到主机了 —— 但如果换成「扣款 100 元」「发短信通知客户」，后果就是实打实的重复）

更糟的是**并发场景**：服务是多实例部署的，3 个实例同时收到这条审批消息 → 3 个实例同时执行 → 重复操作。

**这不是理论问题。** 论文的作者拿真实框架实测过：

> "**k processes resuming one parked interrupt fire the gated effect k times**, saturation 1.0 in **36 of 40 cells**, and the failure **crosses hosts**."

40 个测试格里 36 格是"完全饱和"（k 个进程全部执行），而且**跨主机也能复现**。

---

## 3. 核心设计：把「消费」变成一条原子 SQL

### 3.1 CAS 抢占

```sql
UPDATE interrupt SET status='CONSUMED', consumed_by=?
WHERE workflow_id=? AND step_no=? AND status='PARKED'
```

**影响行数为 1 ⇒ 抢到了；为 0 ⇒ 已被别人消费。**

关键在于：**判断和执行是同一条 SQL**。数据库的行锁保证了这两个动作不可分割。

**为什么不能用「先 SELECT 查状态，再 UPDATE」？** 那是经典的 **TOCTOU** 竞态：

```
线程 A: SELECT status → PARKED ✓
线程 B: SELECT status → PARKED ✓      ← 两个都通过了检查
线程 A: UPDATE → 执行副作用
线程 B: UPDATE → 执行副作用            ← 执行了两次
```

**这就是论文里那 k 次执行的成因。** 判断和动作之间有窗口，窗口里有别人。

> 面试时这句话可以直说：**"我没有用任何应用层的判断，抢占完全交给数据库的一条条件更新。"**

### 3.2 FD 与 CO 的张力 —— 这是全篇最精妙的设计点

论文指出一个矛盾：

> "FD and CO constrain behavior at the **same wire point** — a second resume addressed to a consumed interrupt — in **opposite directions**: FD demands the new value be honored on a new branch, CO demands a stray re-delivery be inert. **Without a discriminator the two are jointly unsatisfiable on identical traffic.**"

翻译一下：**同一个线上的字节序列，两种性质要求相反的结果。**

- 如果收到第二次审批，FD 说"这可能是一个新的分支决策，要尊重它"
- 而 CO 说"这可能是重复投递，必须惰性"

**光看流量，这两种情况长得一模一样。** 所以必须引入一个**判别符（branch discriminator）**：

| 有没有分叉意图 | 走哪条路 | 结果 |
|---|---|---|
| **无**（普通审批） | CO | 抢占成功 → `CONSUMED`；被抢 → `INERT` |
| **有**（带 branchId） | FD | 首次 → `FORKED`（产出 `f(v)`）；同 branchId → `REPLAYED` |

论文把这叫做 **FI（Fork-intent expressibility）**：**resume API 必须能在线缆上表达这个判别符**。这是协议义务，不是可选项。

我的实现里，`ResumeCommand` 在**构造时**就强制：带分叉意图就必须提供 `branchId`，否则抛异常。

> 面试时这是个很好的点：**"FB 和 CO 不可能同时满足，除非 API 能区分'重试'和'分叉'。所以这个判别符必须放在协议层，不能靠猜。"**

### 3.3 CO 有两个子条款，必须分开实现

论文原文：

> "**(CO-c, consumption count)** An interrupt is consumed by **at most one resume**."
> "**(CO-e, effect inertness)** A resume **without fork intent** ... is **inert with respect to effects**."

**为什么不能只看副作用次数？** 论文点出了一个陷阱：

> "A gate that serves its effect idempotently from the durable record can consume one human approval twice while the effect count stays at one, which leaves the **approval trail wrong** and the effect ledger right."

**意思是**：如果你只做了幂等，副作用次数看起来是对的（1 次），但**审批记录已经被消费了两次** —— 审计轨迹已经错了。

所以我的实现里，**被拒绝的投递也要留痕**：

```
[真实输出] 消费次数 = 1（副作用只会触发 1 次）
[真实输出] 投递次数 = 3（含 2 次被惰性拒绝）
```

两个指标分开观测。这个细节能主动讲出来，很能体现你真的读过契约。

### 3.4 闸门只裁决，不干活

一个设计上的选择：`resume()` **不执行**被门控的业务操作，它只返回裁决结果（`CONSUMED` / `INERT` / `FORKED` / `REPLAYED`），由调用方根据 `isEffectBearing()` 决定要不要执行。

好处是职责干净，而且对应论文的说法：闸门要 **"refusing the rest before any node executes"** —— 被拒绝的线程根本走不到执行那一步。

---

## 4. 实测数据（真实输出，连跑 5 次稳定）

```
[真实输出] 并发数 = 8
[真实输出] CONSUMED = 1，INERT = 7
[真实输出] 中断被消费次数 = 1
[真实输出] 被门控操作执行次数 = 1   （论文实测主流框架这里是 k 次）
```

FD（分叉确定性）：
```
[真实输出] 分支 A = decision:approve (kind=FORKED)
[真实输出] 分支 B = decision:reject (kind=FORKED)
[真实输出] 分支总数 = 2
[真实输出] 分叉两次后，中断消费计数 = 0        ← 分叉不消费中断
```

同分支重复投递：
```
[真实输出] 第一次 = FORKED / decision:approve
[真实输出] 第二次 = REPLAYED / decision:approve
[真实输出] 分支总数 = 1
```

FI（协议层强制）：
```
[真实输出] 拒绝原因: 带分叉意图的 resume 必须提供 branchId（FI：判别符必须能在 API 上表达）
```

汇总：**49 个测试全部通过**。

---

## 5. 面试追问清单

| # | 追问 | 回答要点 |
|---|---|---|
| 1 | 审批为什么要做成闸门？ | 高风险操作不可逆，Agent 不能自己决定。挂起 → 持久化 → 人工放行 → 恢复 |
| 2 | ⭐ **并发审批怎么保证只执行一次？** | 数据库条件更新 CAS：`UPDATE ... WHERE status='PARKED'`，影响行数为 1 才是赢家。**判断和执行在同一条 SQL 里，没有窗口** |
| 3 | 为什么不能先查再改？ | TOCTOU 竞态，两个线程会同时通过检查。论文实测就是这么导致 k 次执行的 |
| 4 | ⭐ **FD 和 CO 是不是冲突的？** | **在同样的流量上确实冲突**：FD 要求新值走新分支，CO 要求重复投递惰性。所以必须引入分叉判别符，这是论文的 FI 性质 |
| 5 | 判别符放哪？ | 放在 API 上（`forkIntent` + `branchId`），且在构造时强制校验。不能靠猜 |
| 6 | 分叉会消耗掉中断吗？ | 不会。分叉是开新分支，中断仍可被正常消费 |
| 7 | 同一分支重复投递会怎样？ | `REPLAYED` —— 复用已有产出，不重新执行分支决策 |
| 8 | ⭐ **只看副作用次数够不够？** | **不够。** 只做幂等的话副作用是 1 次，但审批记录可能被消费了两次，**审计轨迹是错的**。所以 CO-c 和 CO-e 要分开观测 |
| 9 | 被拒绝的审批要记录吗？ | 要。否则审批轨迹不完整，审计不成立 |
| 10 | 闸门为什么不直接执行业务操作？ | 职责分离。而且论文要求"在任何一个节点执行之前就拒绝掉其余请求"，闸门先裁决、调用方再执行才能做到 |
| 11 | 跨主机还成立吗？ | 成立，因为仲裁点在共享数据库，不在进程内存里。论文实测的失败恰恰是跨主机的 |
| 12 | 这个测试稳吗？ | 连跑 5 次结果一致。用 `CountDownLatch` 让 8 个线程尽可能同时发起，最大化竞争窗口 |

---

## 6. 边界（不要声称做了）

**已做到：**
- FD / CO-c / CO-e / FI 四条性质
- 并发 8 路下的 consume-once，5 次稳定
- 完整审批轨迹（含被拒绝的投递）
- ✅ **参数快照 / `tool_args_drift` 检测**（计划 4 已补，见 `ApprovalBindingTest`）
- ✅ **真实进程强杀测试**（计划 4 已补，`RealProcessKillTest` 真的起子 JVM 并 `destroyForcibly`）

**还没做：**
- ❌ 审批超时处理（论文提到 durable timers for SLAs）
- ❌ 跨主机实测（架构上成立，仲裁点在共享数据库，但没有真的跑两台机器）
- ❌ 增量检查点（O(N²) 存储问题，见复盘文档）
