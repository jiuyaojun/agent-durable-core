# Agent 持久化执行内核 — 设计文档

> 日期：2026-09-19
> 状态：待审阅
> 目标读者：本人（面试时讲这个项目）
> 关联调研：[docs/research/2026-09-19-agent-reliability-research.md](../../research/2026-09-19-agent-reliability-research.md)

---

## 1. 一句话定位

> 一个 **Java 实现的 Agent 工具调用持久化执行内核**。解决的问题是：
> **当 LLM 是非确定性的、而工具调用有真实副作用时，如何保证一次 Agent 任务崩溃后正确恢复，且副作用恰好执行一次。**

**面试叙事（一句话）**：

> "我实现并验证了 agent workflow 的 resume 语义契约。arXiv 2608.03836 实测发现 LangGraph 1.2.9 在 SIGKILL 后是 at-least-once 而不是 exactly-once，并发恢复时挂起的副作用会被执行 k 次。我针对这六个性质做了一个 Java 实现，并用故障矩阵验证。"

---

## 2. 完成定义（Definition of Done）

以论文 [arXiv:2608.03836](https://arxiv.org/abs/2608.03836) 的 **RESUME CONTRACT** 为准。
**以下定义是逐字提取自论文正文的**（2026-09-19 从 arXiv HTML 全文核对），不是我的转述 —— 测试断言必须与之一致。

### 2.1 六个性质（论文原文定义）

**Property 1 · PC（Prefix continuation）**
> "Recovery continues from the durably recorded frontier state: execution after recovery begins in the state S_F recorded at frontier F, or in a state re-derived deterministically from the durable log alone that equals S_F."
> "**Memoized replay conforms**: prefix code **may re-run** during recovery provided **every prefix effect is served from the durable record** (so EO is preserved) and the re-derived state is a pure function of the log."

⚠️ **修正**：我原先以为 PC 要求「跳过已完成的步骤、不重跑」。**论文明确允许重跑前缀代码**，只要①每个前缀副作用都从持久记录里取②状态是日志的纯函数。这条修正让实现简单得多，也更接近真实框架的做法。

**Property 2 · EO（Effect exactly-once）**
> "For every task t, effect e_t fires **at most once** on a branch across any sequence of interrupts, crashes, and resumes."
> "As a safety invariant **EO is at-most-once**; the 'exactly' is supplied by pairing with the **liveness obligation**."
> "EO constrains **observable external effects only**, never message delivery: **an effect that commits while its acknowledgment is lost counts as fired**, and the retry discipline for lost acknowledgments is the **idempotency-key composition**."

⚠️ **关键**：EO 的安全不变式是 **at-most-once**，不是 exactly-once。而且论文直接点明了我们那个崩溃窗口 ——「副作用已提交但确认丢失，算作已触发」，其补救手段就是**幂等键**。

**Property 3 · FD（Fork determinism）**
> "If resumes carrying **fork intent** with values v₁,…,v_m are addressed to the same interrupt checkpoint, then each branch outcome satisfies o_k = f(v_k) for the branch semantics f; in particular v_j ≠ v₁ ⇒ o_j ≠ o₁ whenever f is injective."
> "f is the **decision function**: the framework's routing of the supplied value into the gated branch decision, deterministic by construction of the gate."

⚠️ FD 是关于**同一个人工审批点被不同值回答两次**时，应该产生两条不同分支。这是**审批场景**的性质，不是普通的恢复。

**Property 4 · CV（Checkpoint validity）**
> "Every persisted checkpoint record satisfies the state schema: a write that would persist schema-invalid state is **rejected with an error, not stored**."

⚠️ 论文实测：**LangGraph 1.2.9 会静默持久化 schema 非法的状态**。

**Property 5 · CO（Consume-once）—— 有两个子条款**
> "**(CO-c, consumption count)** An interrupt is consumed by **at most one resume**."
> "**(CO-e, effect inertness)** A resume **without** fork intent addressed to a completed run or an already-consumed interrupt — **including byte-identical re-delivery of a prior resume** — is **inert with respect to effects**."
> "A gate that serves its effect idempotently from the durable record **can consume one human approval twice while the effect count stays at one**, which leaves the **approval trail wrong** and the effect ledger right."

⚠️ **这是全篇最精妙的一点**：如果只做幂等，副作用计数是对的，但**审批记录被消费了两次**——审计轨迹已经错了。所以 CO-c 和 CO-e 必须分开实现、分开测试。

**Property 6 · RD（Recovery determinism）**
> "The recovery decision (which tasks to skip versus re-execute) is a **function of durable state**: two recoveries from identical durable logs make identical decisions."

### 2.2 附加义务

**Definition 2 · Explicit fork（显式分叉）**
> "A resume carries fork intent iff it bears a **branch discriminator** distinguishing it from re-delivery of a prior resume: a distinct **resume ordinal**, an explicit **fork flag**, or an address the framework's own documentation designates as branch-creating."

**Property 7 · FI（Fork-intent expressibility）** —— 协议义务，非行为性质
> "The resume API must make the discriminator of Definition 2 **expressible on the wire**."

**为什么 FI 必须有**：论文指出 FD 与 CO 在**同一个线缆点**上朝相反方向拉扯 ——
> "FD demands the new value be honored on a new branch, CO demands a stray re-delivery be inert. **Without a discriminator the two are jointly unsatisfiable on identical traffic.**"

### 2.3 验收矩阵

| # | 性质 | 验收方式 | 计划 |
|---|---|---|---|
| 1 | **PC** | 崩溃后恢复，前缀副作用从持久记录取；状态是日志的纯函数 | 2 |
| 2 | **EO** | 副作用触发次数 ≤ 1（含确认丢失窗口） | 2 |
| 3 | **FD** | 同一审批点用不同值回答，产出不同分支 | 3 |
| 4 | **CV** | 非法状态的检查点写入被**拒绝并报错**，不落库 | 2 |
| 5a | **CO-c** | 并发 k 个 resume 抢同一个挂起中断，只有一个成功消费 | 3 |
| 5b | **CO-e** | 重复投递（字节相同）对副作用无影响 | 3 |
| 6 | **RD** | 同一份日志恢复两次，决策完全一致 | 2 |
| 7 | **FI** | resume API 能表达分叉判别符 | 3 |

> **六性质与中断的关系**：PC / EO / CV / RD 不涉及中断，可以先用普通崩溃场景验证（计划 2）；
> FD / CO / FI 全部围绕**中断（人工审批）**展开，必须等审批闸门建好才能验证（计划 3）。
> 所以顺序是：**先做无中断的恢复内核，再做中断层**。

| # | 性质 | 含义 | 验收方式 |
|---|---|---|---|
| 1 | **prefix continuation** | 恢复必须从断点继续，不跳步、不重头 | 在第 N 步注入崩溃，恢复后验证已执行步骤序列是原序列的前缀 |
| 2 | **effect exactly-once** | 有副作用的操作恰好执行一次 | 对副作用调用计数，崩溃+恢复后计数必须 == 1 |
| 3 | **fork determinism** | 从同一检查点分叉，结果确定 | 同一检查点重放两次，产出状态完全一致 |
| 4 | **checkpoint validity** | 检查点本身是合法状态 | 每个检查点反序列化后通过状态校验 |
| 5 | **consume-once** | 挂起的事件只能被消费一次（**含并发**） | **k 个线程/进程同时恢复一个挂起任务，副作用只执行 1 次** ← 论文实测主流框架在这里失败 |
| 6 | **recovery determinism** | 恢复过程确定 | 同一日志重放多次，结果一致 |

---

## 3. 范围

### 做什么

| 模块 | 说明 |
|---|---|
| **决策日志** | append-only 记录 LLM 决策 + 工具调用 + 结果。**恢复靠重放日志，不重新调 LLM** |
| **检查点与恢复** | 崩溃后从断点续跑 |
| **副作用 exactly-once** | 幂等键 + 数据库唯一索引 |
| **工具层** | 工具声明式注册：效果类型（无副作用 / 幂等 / 非幂等） |
| **审批闸门** | 复用 interrupt/resume；**绑定参数快照**，防 `tool_args_drift` |
| **故障注入 + 契约测试** | 故障矩阵；六性质各自的验收测试 |
| **最小 Agent Loop** | 能调 LLM、能调工具，**故意做薄** |

### 不做什么（范围上界，防止膨胀）

- ❌ 通用工作流引擎（那是 conductor，32k★）
- ❌ 可视化编排 / 图编辑器
- ❌ 多 Agent 协作
- ❌ RAG / 向量检索
- ❌ Saga 补偿（**首轮不做**，六性质不含它）
- ❌ 成本闸门 / 熔断 / 限流（同上）
- ❌ 企业级控制平面（那是 cordum：Go + NATS + k8s）
- ❌ 分布式部署、跨主机

---

## 4. 核心设计决策

只有六条。**每条都要能回答"为什么"，因为这就是面试的问题。**

### D1. 日志即真相：恢复靠重放，不重新问 LLM

- **为什么**：LLM 是概率性的，重放时可能给出不同决策 → 恢复出来的执行流不是原来那条。**这是本项目最核心的决策。**
- **代价**：日志体积增长；需要处理"日志与代码不匹配"（divergence）
- **面试追问**：「重放会不会重复烧 token？」→ 不会，因为不重新调 LLM

### D2. 存储只用 MySQL，不用 Redis

- **为什么**：exactly-once 的最终防线是**数据库唯一索引**（面经原题：「重复请求直接插入失败」）。Redis 只是快路径，**去掉它不损失正确性，只损失性能**；少一个依赖少一类 bug
- **代价**：并发性能不如 Redis 方案
- **面试追问**：「为什么不用 Redis 做幂等？」→ 能答出"快路径 vs 最终防线"的分工即可

### D3. 幂等键由执行位置派生，不让 LLM 生成

- 幂等键 = `workflowId + stepNo + toolName`
- **为什么**：如果让 LLM 生成幂等键，非确定性会导致重放时键变了 → 幂等失效

### D4. 工具必须声明效果类型

- `NONE`（只读）/ `IDEMPOTENT` / `NON_IDEMPOTENT`（**必须走幂等保护**）
- **为什么**：不是所有步骤都需要重放保护。读操作可以安全重放，写操作不行。**区分开来才能既正确又快**

### D5. consume-once 用数据库乐观锁（CAS）

- 状态机 + 版本号，`UPDATE ... WHERE version = ? AND status = 'PARKED'`，影响行数为 0 则说明已被别人消费
- **为什么**：这是论文实测发现的真实 bug —— k 个进程同时恢复一个挂起任务，会让副作用执行 k 次。**悲观锁跨进程不可靠，唯一能信赖的是数据库的条件更新**

### D6. 检查点用增量 + 定期全量（**列为可选，最后做**）

- 借鉴 [LangChain DeltaChannels](https://www.langchain.com/blog/delta-channels-evolving-agent-runtime)：全量快照导致 O(N²) 存储增长（200 轮 = 5.3GB → 增量后 129MB）
- **为什么列为可选**：六性质不要求它。它是"性能优化"，不是"正确性"。**时间不够就砍掉，但要在复盘文档里写清楚这个取舍** —— 面试时"我知道有这个问题但优先保正确性"比"我没想过"好得多

---

## 5. 故障矩阵（评测设计）

评测**先做**（调研明确说「先有 eval，再扩功能」）。

### 5.0 前提：测试必须用「确定性假 LLM」，不能用真模型

这一条是能测六性质的前提，必须说清楚：

- 六性质全部是关于**确定性**的断言（重放一致、fork 一致、恢复一致）。**真实 LLM 每次输出都不同，用它测这些性质永远测不出结论。**
- 所以：定义 `LlmClient` 接口 → 测试用 `ScriptedLlmClient`（按预置脚本返回，可注入"在第 N 次调用时抛异常"）→ 真实模型**只用于最后的演示**。
- **顺带的好处**：这套测试可以离线跑、免费跑、在 CI 里跑 —— 这是简历上讲"我做了 39 格故障矩阵"能站得住的前提。
- **面试追问**：「你怎么在没有真模型的情况下验证正确性？」→ 上面的答案。这题答得好很加分，因为它证明你分得清"业务逻辑"和"模型行为"。

### 5.1 注入的故障类型

| 类别 | 具体故障 |
|---|---|
| 进程级 | 步骤执行前 / 执行中 / 执行后 `kill -9` |
| 存储级 | 写日志成功但写检查点失败（及其反向） |
| 工具级 | 工具超时、工具返回错误、工具成功但响应丢失 |
| 并发级 | k 个恢复请求同时到达同一个挂起任务 |
| 数据级 | 日志被截断、检查点非法、重放中途再次崩溃 |

### 5.2 测试要回答的问题

- 副作用执行了几次？（必须恰好 1 次）
- 已完成的步骤有没有被重跑？
- 恢复出来的最终状态，和「从头跑一遍」是否一致？
- 并发恢复时，consume-once 是否仍然成立？

### 5.3 记录的指标（简历要的数字）

| 指标 | 说明 |
|---|---|
| 六性质通过率 | 每格故障矩阵的结果 |
| 副作用重复次数 | 期望恒为 0 |
| 恢复成功率 | |
| 恢复耗时 | |
| 日志体积 | （若做 D6 则对比优化前后） |

---

## 6. 技术栈

| 用途 | 选型 | 理由 |
|---|---|---|
| 语言 / 框架 | **Java 21 + Spring Boot 3** | 与简历一致；本项目是后端工程问题，不是 ML 问题 |
| 存储 | **MySQL 8** | 事务 + 唯一索引是 exactly-once 的基石 |
| LLM 接入 | **最薄的 HTTP 客户端**（自写，不引框架） | 引入 Spring AI / langgraph4j 会稀释"这是你做的"这个看点 |
| 测试 | JUnit 5 + Testcontainers | 需要真实 MySQL 才能验证唯一索引和并发 |

> 当前生态版本（2026-09-19）：Spring AI 2.0.1 / langchain4j 1.19.3 / langgraph4j 1.9.0 —— **本项目都不需要**。

### 代码规模硬约束

- **单文件 < 500 行，单函数 < 50 行**

> 这不是风格偏好。调研中「编辑到一半忘了」这个失败模式表明：文件超过 500 行时 AI 会改一半破掉。这条约束直接服务于「后续能快速修改快速落地」。

---

## 7. 分步交付计划

**每一步都必须：① 有可运行的验收测试 ② 配一份「为什么这么设计 + 面试会问什么 + 标准答案」讲解 ③ 你确认能讲明白，才进下一步。**

| 步 | 产出 | 讲解重点 | 完成标志 |
|---|---|---|---|
| **0** | 项目骨架 + **故障注入测试框架** | 为什么先把测试框架搭起来 | 能跑一个"假装崩溃"的测试并看到真实输出 |
| **1** | 最小 Agent Loop + 工具层（含效果类型声明） | 工具为什么要声明效果类型 | 能完成一次任务并留下完整日志 |
| **2** | 决策日志 + 检查点 + 恢复 | **为什么恢复靠重放而不是重新问 LLM** | 性质 1 / 3 / 4 / 6 通过 |
| **3** | 副作用 exactly-once | **唯一索引 vs Redis 的分工** | 性质 2 通过 |
| **4** | 并发 consume-once | **论文发现的 k 次执行 bug，怎么用 CAS 解决** | 性质 5 通过（六性质全绿） |
| **5** | 审批闸门 + 参数快照 | **TOCTOU：审批后参数被改怎么办** | 端到端演示 |
| **6** | 故障矩阵全量 + 演示脚本 + 复盘文档 | 量化指标 | 可录屏演示 |
| **7**（可选） | D6 增量检查点 | O(N²) 存储问题 | 有前后对比数字 |

**第 0 步就把测试框架搭起来**，是为了纠正调研里点名的误区「没有测试就加功能」。

---

## 8. 风险

| 风险 | 应对 |
|---|---|
| 🔴 **代码我写，你讲不了 → 项目变负资产** | 分步交付 + 每步配讲解 + 你确认后才继续；不做"先写完再补讲解" |
| 🟡 范围失控，膨胀成造 Temporal | §2 的六性质是上界；§3 的不做清单是硬边界 |
| 🟡 与 LangGraph / Temporal 撞车 | 叙事定位为"验证语义契约"，不是"造框架"；用论文的实测结果做对比 |
| 🟡 面试官问「这不就是 Temporal 吗」 | 准备回答：不造通用引擎，只做一个垂直切面；并说清与 cordum（治理层）的分层 |
| 🟡 「先想清架构再动手」本身是被点名的误区 | §7 的分步计划就是最小闭环优先；设计到此为止，不再加细节 |

---

## 9. 面试问答手册（随开发同步维护）

**暂不在此文档中编写**，而是随 §7 每一步产出一份独立的 `docs/interview/step-N-*.md`。

原因：调研明确指出「上下文失忆」发生在上下文超 40% 时 —— 面试问答必须**沉淀成文件**，而不是留在对话里。

每份讲解包含：
1. 这一步解决了什么问题（用故障场景描述，不用术语）
2. 为什么这样设计（含被否决的替代方案）
3. 面试官会怎么追问（至少 5 问）+ 标准答案
4. 我踩过的坑 / 实测数据
