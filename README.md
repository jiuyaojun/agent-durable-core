# agent-durable-core

> 一个 Java 实现的 **Agent 工具调用持久化执行内核**。
>
> 解决的问题：**当 LLM 是非确定性的、而工具调用有真实副作用时，如何保证一次 Agent 任务崩溃后正确恢复，且副作用恰好执行一次。**

---

## 为什么做这个

正常测试碰不到「崩溃恰好发生在副作用之后、日志写入之前」这个极窄窗口。但真实世界里它意味着**重复退款、重复发短信、重复创建服务器**。

这个项目的做法是：**先用故障注入把这个窗口稳定复现出来，再逐个消灭它。**

计划 2 完成时的真实输出（当时 34 个测试）：

```
[真实输出] 崩溃后 host 行数 = 1
[真实输出] 崩溃后日志行数 = 0          ← 崩溃窗口确实存在
[真实输出] 恢复后 host 行数 = 1        ← EO：副作用没有重复触发
[真实输出] 重启后副作用总次数 = 1      ← 计划 1 时这里是 2
```

> 这组数字是**计划 2 阶段**的记录，不是最终状态。最终版本共 62 个测试，见下文「进度」。

---

## 快速开始

### 1. 启动项目专属的 MySQL 开发实例

```powershell
.\scripts\devdb-start.ps1     # 首次运行会自动初始化数据目录
```

> 用独立实例（端口 **3307**）而不是系统那个 3306 的 MySQL，原因是不依赖未知的 root 密码，
> 且数据目录独立、可随时删除重建。数据目录：`C:\Users\xuchenxiang\agent-durable-devdb`
> 停止：`.\scripts\devdb-stop.ps1`

### 2. 跑测试

```powershell
mvn test
```

预期：`Tests run: 62, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`

### 3. 现场演示（面试时可直接跑）

```powershell
mvn -q compile exec:java
```

会依次演示三个场景：崩溃窗口下的副作用幂等、并发审批只放行一次、审批参数指纹不匹配时被拒绝。

> 第三个场景是**演示脚本自己调用了校验函数** —— 生产链路里 `ApprovalGate.resume()` 并没有调用它。
> 详见下文「完成定义」。

---

## 项目结构

```
src/main/java/com/durable/
├── db/          连接池与表结构管理
├── journal/     决策日志：只追加、不可修改
│   └── mysql/   基于 MySQL 唯一约束的实现
├── effect/      效果账本：与副作用同事务
├── engine/      步骤执行器与恢复语义
├── interrupt/   中断与审批闸门（CAS 独占消费）
├── checkpoint/  检查点校验（组件级完成，尚未接入执行链路）
├── fault/       故障注入：确定性复现崩溃窗口
├── shell/       被测的"宿主工具"（演示用）
├── json/ util/  序列化与哈希工具
└── demo/        三个可现场运行的教学场景

src/test/java/com/durable/
├── contract/    契约测试（对性质的断言）
├── engine/      崩溃行为与缺陷复现
└── effect/ checkpoint/ interrupt/ journal/ fault/ db/

scripts/         开发数据库的启动/停止
docs/
├── research/    选题调研（含竞品、时效性核查、常见错误）
├── superpowers/
│   ├── specs/   设计文档
│   └── plans/   实施计划
└── interview/   每步的面试讲解与追问清单
```

---

## 技术栈

Java 17 · Maven · MySQL 8 · JUnit 5 · Jackson · HikariCP

**故意不使用 Spring Boot** —— 核心没有任何 Web 或依赖注入代码，框架只带来魔法不带来价值，
而且会稀释「这是自己实现的」这个看点。

---

## 完成定义

以论文 [arXiv:2608.03836](https://arxiv.org/abs/2608.03836) 的 **RESUME CONTRACT** 为验收基线 ——
它不是我自己编的题目，而是一组外部定义的、可逐条断言的性质：

| # | 性质 | 状态 | 说明 |
|---|---|---|---|
| 1 | prefix continuation | ✅ 端到端已实现 | 恢复时从头重跑前缀，副作用从账本取 |
| 2 | effect exactly-once | ✅ 端到端已实现 | 效果账本与副作用在**同一个事务**里 |
| 3 | fork determinism | ✅ 端到端已实现 | 分支按 `(wf, step, branch_id)` 主键去重 |
| 4 | consume-once（含并发） | ✅ 端到端已实现 | 一条条件更新完成 CAS 独占消费 |
| 5 | fork-intent expressibility | ✅ 端到端已实现 | 协议层强制：声明分叉必须带 `branch_id` |
| 6 | checkpoint validity | ⚠️ **组件级完成** | 校验逻辑写好且单测通过，但**执行内核不读写检查点** |
| 7 | recovery determinism | ⚠️ **组件级完成** | `RecoveryPlan` 是纯函数且单测通过，但**执行内核不调用它** |

> **第 6、7 条必须说清楚**：这两项的组件都写完并单独测过了，但我**没有把它们接进执行主链路**。
> 也就是说，「内核依赖检查点恢复」「恢复计划影响执行行为」这两件事，在当前代码里**并不成立**。
> 我保留它们是因为组件本身是对的，但**不应该被读成"端到端已交付"**。
>
> 同理，**审批参数指纹绑定**（`ApprovalGate.fingerprint` / `verifyBinding`）也是
> 实现完整、单测通过，但**生产路径零调用**：`DurableExecutor` 里没有任何对 `ApprovalGate` 的引用，
> 两个包互不相通。这是本项目最明确的一处「写了但没接线」。

> 选这篇论文作为基线的原因：它实测发现 **LangGraph 1.2.9 在 SIGKILL 后是 at-least-once 而不是 exactly-once**，
> CrewAI 和 pydantic-graph 也各有不符。主流框架都没做对这件事。

### 最能打的一个对照实验

论文实测：并发恢复一个挂起的中断，主流框架会让被门控的操作执行 **k 次**
（40 格中 36 格饱和度为 1.0，且故障跨主机）。本项目同场景实测（连跑 5 次稳定）：

```
[真实输出] 并发数 = 8
[真实输出] CONSUMED = 1，INERT = 7
```

**这里能声称的**：8 路并发抢占同一个挂起中断，**只有 1 路抢占成功**。
原因是把「判断是否已被消费」和「消费」合并成了同一条条件更新
（`UPDATE ... WHERE status='PARKED'`），两者不可分割。

**这里不能声称的**：我**没有**测量「被门控的真实副作用执行了几次」。
`ConcurrentResumeTest` 里那个计数器是由闸门返回值派生的，不是对副作用的观测；
而且 `engine` 与 `interrupt` 两个包**互不引用** —— 当前代码里不存在
「消费一个中断 → 执行一个受保护的副作用」这条端到端路径。
把这两件事混为一谈是不诚实的，所以我把它拆开写。

---

## 进度

- [x] **计划 1**：工程骨架、决策日志、故障注入器、缺陷复现（副作用执行 2 次）
- [x] **计划 2**：恢复语义内核 —— PC / EO / CV / RD，缺陷修复（2 次 → 1 次）
- [x] **计划 3**：中断与审批闸门 —— FD / CO-c / CO-e / FI，含并发 consume-once
- [x] **计划 4**：审批参数指纹绑定（⚠️ 组件与单测完成，**未接入执行链路**）、故障矩阵、演示脚本、复盘文档

**62 个测试全部通过。** 测试分布：

| 类别 | 测试类 |
|---|---|
| 契约（对性质的断言） | `PrefixContinuationTest` `EffectExactlyOnceTest` `ForkDeterminismTest` `CheckpointValidityTest` `ConsumeOnceTest` `RecoveryDeterminismTest` `ApprovalBindingTest` |
| 故障矩阵 / 并发 / 真实强杀 | `FaultMatrixTest` `ConcurrentResumeTest` `RealProcessKillTest` |
| 组件 | `EffectLedgerTest` `DurableExecutorCrashTest` `MySqlJournalStoreTest` `ApprovalGateTest` `CrashInjectorTest` `DatabaseConnectivityTest` |

## 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | 日志即真相，恢复靠重放 | LLM 非确定性，重放时可能给出不同决策 |
| D2 | 只用 MySQL，不用 Redis | 唯一索引是 exactly-once 的最终防线；Redis 只是快路径 |
| D3 | 幂等键由执行位置派生 | 让 LLM 生成幂等键会因非确定性而失效 |
| D4 | 工具声明效果类型 | 只读可随便重放，非幂等必须保护 |
| D5 | consume-once 用数据库 CAS | 论文实测并发恢复会让副作用执行 k 次 |
| **D7** | **效果账本与副作用同事务** | **唯一能做到真正 exactly-once 的方式** |
| **D8** | **恢复时重跑前缀，副作用从账本取** | 论文明确允许（memoized replay），实现更简单 |
| **D9** | **恢复决策必须是纯函数** | RD 性质要求同一份日志得出相同决策。⚠️ `RecoveryPlan` 已实现且单测通过，但内核不调用它 |

> 说明：决策编号从 D5 跳到 D7，是因为文档是从一份提纲补写的，D6 条目缺失 —— 保留原编号不做重排。

> ⚠️ **诚实边界**：D7 只在副作用位于**同一个数据库**时成立。
> 外部副作用（支付网关、短信）跨网络没有原子提交，只能退化为
> **at-least-once + 幂等键 + 对方幂等接收**。
> Temporal 官方文档也是这个立场 —— 它不保证副作用 exactly-once，
> 只提供 at-least-once 或 at-most-once，要求活动本身幂等。

> ⚠️ **关于「LLM」**：本项目**不含任何真实 LLM 调用，也没有 LLM 抽象层**。
> 测试用确定性步骤序列驱动，变量与分支都是硬编码的。
> 这么设计是有意的 —— 恢复语义的断言必须可复现，真实模型带进来的非确定性会把结论冲掉
> （论文本身也采用 LLM-free harness）。
> 但因此**不要**把本项目读成「我做过模型接入」：它做的是**执行语义层**，不是模型层。
