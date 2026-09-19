# agent-durable-core

> 一个 Java 实现的 **Agent 工具调用持久化执行内核**。
>
> 解决的问题：**当 LLM 是非确定性的、而工具调用有真实副作用时，如何保证一次 Agent 任务崩溃后正确恢复，且副作用恰好执行一次。**

---

## 为什么做这个

正常测试碰不到「崩溃恰好发生在副作用之后、日志写入之前」这个极窄窗口。但真实世界里它意味着**重复退款、重复发短信、重复创建服务器**。

这个项目的做法是：**先用故障注入把这个窗口稳定复现出来，再逐个消灭它。**

当前进度（计划 2 完成）：

```
[真实输出] 崩溃后 host 行数 = 1
[真实输出] 崩溃后日志行数 = 0          ← 崩溃窗口确实存在
[真实输出] 恢复后 host 行数 = 1        ← EO：副作用没有重复触发
[真实输出] 重启后副作用总次数 = 1      ← 计划 1 时这里是 2
```

`Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`

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

预期：`Tests run: 61, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`

### 3. 现场演示（面试时可直接跑）

```powershell
mvn -q compile exec:java
```

会依次演示三个场景：崩溃窗口下的副作用幂等、并发审批只放行一次、审批后偷改参数被拒绝。

---

## 项目结构

```
src/main/java/com/durable/
├── db/          连接池与表结构管理
├── journal/     决策日志：只追加、不可修改
│   └── mysql/   基于 MySQL 唯一约束的实现
├── fault/       故障注入：确定性复现崩溃窗口
└── engine/      步骤执行器（当前刻意不含恢复逻辑）

src/test/java/com/durable/
├── db/          连通性
├── journal/     日志读写与唯一约束
├── fault/       注入器行为
└── engine/      崩溃行为与缺陷复现

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

以论文 [arXiv:2608.03836](https://arxiv.org/abs/2608.03836) 的 **RESUME CONTRACT 六个性质**为准：

| # | 性质 | 状态 |
|---|---|---|
| 1 | prefix continuation | ✅ 计划 2 |
| 2 | effect exactly-once | ✅ 计划 2 |
| 3 | fork determinism | ✅ 计划 3 |
| 4 | checkpoint validity | ✅ 计划 2 |
| 5 | consume-once（含并发） | ✅ 计划 3 |
| 6 | recovery determinism | ✅ 计划 2 |
| 7 | fork-intent expressibility | ✅ 计划 3 |

> 选这篇论文作为基线的原因：它实测发现 **LangGraph 1.2.9 在 SIGKILL 后是 at-least-once 而不是 exactly-once**，
> CrewAI 和 pydantic-graph 也各有不符。主流框架都没做对这件事。

### 最能打的一个对照实验

论文实测：并发恢复一个挂起的中断，主流框架会让被门控的操作执行 **k 次**
（40 格中 36 格饱和度为 1.0，且故障跨主机）。本项目同场景实测（连跑 5 次稳定）：

```
[真实输出] 并发数 = 8
[真实输出] CONSUMED = 1，INERT = 7
[真实输出] 被门控操作执行次数 = 1
```

---

## 进度

- [x] **计划 1**：工程骨架、决策日志、故障注入器、缺陷复现（副作用执行 2 次）
- [x] **计划 2**：恢复语义内核 —— PC / EO / CV / RD，缺陷修复（2 次 → 1 次）
- [x] **计划 3**：中断与审批闸门 —— FD / CO-c / CO-e / FI，含并发 consume-once
- [x] **计划 4**：审批参数快照（TOCTOU 防护）、故障矩阵、演示脚本、复盘文档

**61 个测试全部通过。** 测试分布：

| 类别 | 测试类 |
|---|---|
| 契约（七条性质） | `PrefixContinuationTest` `EffectExactlyOnceTest` `ForkDeterminismTest` `CheckpointValidityTest` `ConsumeOnceTest` `RecoveryDeterminismTest` `ApprovalBindingTest` |
| 故障矩阵与并发 | `FaultMatrixTest` `ConcurrentResumeTest` |
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
| **D9** | **恢复决策必须是纯函数** | RD 性质要求同一份日志得出相同决策 |

> ⚠️ **诚实边界**：D7 只在副作用位于**同一个数据库**时成立。
> 外部副作用（支付网关、短信）跨网络没有原子提交，只能退化为
> **at-least-once + 幂等键 + 对方幂等接收**。
> Temporal 官方文档也是这个立场 —— 它不保证副作用 exactly-once，
> 只提供 at-least-once 或 at-most-once，要求活动本身幂等。
