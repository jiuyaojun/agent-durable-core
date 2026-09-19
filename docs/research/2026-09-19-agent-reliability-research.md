# Agent 可靠执行层 — 前期调研报告

> 调研日期：2026-09-19
> 目的：在动手写代码之前，验证选题方向是否成立、竞品在哪、业界标准做法是什么、面试会怎么问。
> 原则：本文中每一条结论都标注来源。无法验证的内容单独标注为「我的推断」。

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| 「Agent 持久化执行 / 可靠性」是真方向还是我编的？ | **真方向，且被顶级行业雷达定性为 anti-pattern 的反面** |
| 业界有没有标准做法可参考？ | **有，已有成型的方法论（6 个模式 + 8 根支柱）** |
| 有没有人已经做过？ | **有，但都在其他语言/其他层；Java 的「持久化执行 + 副作用护栏」层接近空白** |
| 最大风险 | **范围失控** —— 这个话题容易膨胀成「造一个 Temporal」，2-4 周做不完 |
| 真正该聚焦的难点 | **「非确定性 LLM 的 replay-safe 持久化」**，而不是泛泛的「幂等 + 检查点」 |

---

## 1. 方向验证：这个方向是真实存在的

### 1.1 最硬的证据：Thoughtworks Technology Radar

[Thoughtworks Technology Radar](https://www.thoughtworks.com/radar/techniques/ignoring-durability-in-agent-workflows)（2026 年 4 月，评级 **Caution**）把「**Ignoring durability in agent workflows**」直接列为反模式。原文：

> "Ignoring durability in agent workflows is an anti-pattern we've seen across many teams, resulting in **systems that work in development but fail in production**. The challenges facing distributed systems are even more pronounced when building with agents. A mindset that expects failures and recovers gracefully outpaces a reactive approach."
>
> "LLM and tool calls can fail due to network interruptions and server crashes, halting an agent's progress and leading to poor user experience and increased operational costs."
>
> "For workflows that involve a human in the loop, **durable execution can suspend progress while awaiting input**. Durable computing platforms such as **Temporal, Restate and Golem** also provide support for agents."

**为什么这条证据最关键**：Thoughtworks Radar 是行业级的「该不该做」判断，不是营销材料。它把这件事定性为**反模式**（即「不做会出问题」），这比任何技术博客都有分量。而且它同时列出了 Temporal / Restate / Golem 三个商业平台 —— 说明这是一个**已被产业界承认的问题域**。

### 1.2 产业界的共识表述

[Inngest: Durable Execution: The Key to Harnessing AI Agents in Production](https://www.inngest.com/blog/durable-execution-key-to-harnessing-ai-agents)（2026-02-19）：

> "**Durable Execution is the AI Agent Harness**"
>
> "AI Agents introduce multiple points of failure (orchestration, probabilistic LLM behavior, tool calling, human-in-the-loop) that **traditional retry logic cannot handle**."
>
> "First, agents are **probabilistic**. The same prompt can produce different responses across calls. **A retry might not produce equivalent results, making idempotency more complex than a simple cache lookup.**"
>
> "Second, agents are **compositional**. If you have five steps with 99% reliability each, your overall success rate drops to 95%. With ten steps, you're at 90%."
>
> "Third, agents are **stateful**. Losing that state mid-execution means losing the reasoning chain, the intermediate results, and the plan the agent was following."
>
> "They need **exactly-once semantics** for operations that cost money or have side effects."
>
> 对传统方案的判词："Queue-based architectures decompose the workflow into separate jobs, but coordinating state across those jobs becomes its own infrastructure project. **You end up building half of a durable execution engine yourself.**"

**第 3 条引文极其重要**，见 §3.1 —— 它指出了这个项目真正难的地方。

### 1.3 2026 的框架表述：Agent Harness 的 8 根支柱

[cubxxw: Agent Engineering Harness: The Eight Pillars](https://github.com/cubxxw/blog/blob/main/content/en/ai-agent/posts/agent-engineering-the-98-percent-harness.md)（2026-06-17，更新至 2026-07-31）：

> "An agent demo can be a loop around an API call. **A production agent is the surrounding discipline: state, tools, permissions, recovery, evaluation, and cost.**"

它把 Harness 拆成 8 根支柱：**orchestration / context / memory / tools / reliability / evaluation / cost / governance**。

该文同时引用了两个一手来源：
- arXiv `2604.14228`《Dive into Claude Code》—— 分析 Claude Code v2.1.88 的 TypeScript 源码，结论是核心只是一个简单循环，**绝大部分代码在它周围：permissions、compaction、extensibility、delegation、session storage**
- OpenAI《[Unrolling the Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/)》（2026-01-23）—— 把这层叫做「**Codex harness**」

**对我们的意义**：项目定位在 8 根支柱里的 **reliability + cost + governance** 三根上，这是有公开框架背书的，不是我拍脑袋分的。

> ⚠️ 该文作者自己声明：标题里的「98.4%」只是**叙事锚点，不是论文测量值**，不应该出现在 benchmark 表格里。所以**不要**在简历上写「Agent 的 98% 是基础设施」这种话，站不住。

---

## 2. 竞品与撞车分析

这一节是我上一版设计**完全没做**的，也是你必须先知道的。

### 2.1 已经存在的东西

| 项目 | Star | 语言 | 它是什么 | 创建 / 最后更新 |
|---|---|---|---|---|
| [conductor-oss/conductor](https://github.com/conductor-oss/conductor) | 32,210 | Java | Netflix 开源的工作流编排引擎 | 2023-12 / 活跃 |
| [alibaba/spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba) | 10,880 | Java | Spring AI 的阿里实现 | 2024-09 / 活跃 |
| [langgraph4j/langgraph4j](https://github.com/langgraph4j/langgraph4j) | 2,009 | Java | Java 版 LangGraph（含图 + 检查点） | 2024-03 / 活跃 |
| [ThousandBirdsInc/chidori](https://github.com/ThousandBirdsInc/chidori) | 1,364 | Rust | 「每次运行都持久、可重放、可恢复」的 Agent 框架 | 2023-07 / 2026-09 |
| [cordum-io/cordum](https://github.com/cordum-io/cordum) | 507 | Go | **「AI Agent 的 action firewall」**：策略 + 人工审批 + 可审计证据 | 2026-01 / 2026-09 |
| [aws/aws-durable-execution-sdk-java](https://github.com/aws/aws-durable-execution-sdk-java) | 28 | Java | AWS 的 Java 持久化执行 SDK | 2025-12 |
| [auraguardhq/aura-guard](https://github.com/auraguardhq/aura-guard) | 7 | Python | **「exactly-once + circuit breaker for agent tool calls」** | 2026-02 / 2026-03（疑似停更） |
| [bcefghj/multi-agent-aiops](https://github.com/bcefghj/multi-agent-aiops) | 360 | 多语言 | ⚠️ 智能运维，描述自称「**面试级全套项目**」，附 `resume-template.md`。创建与最后 push **同一天**，典型批量上传 | 2026-04 |

### 2.2 关键判断：缺口在哪

**cordum 是最接近的竞品**，但它和我们做的事**不在同一层**：

| | cordum | 我们要做的 |
|---|---|---|
| 定位 | 控制平面 / 治理产品 | 执行层的持久化内核 |
| 做什么 | 策略、人工审批、审计证据 | **持久化状态、崩溃恢复、重放、补偿** |
| 不做 | 崩溃后的断点续跑、replay、Saga 补偿 | — |
| 形态 | Go + NATS + Redis + k8s + Helm，企业级部署 | 单机可跑，教学可实现 |

**aura-guard 更接近「我上一版的设计」**，但它：
- 是 **Python**，且只有 7★，2026-03 之后没再更新
- 做的是**进程内**的运行时护栏（循环检测、成本上限、副作用门控）
- **不做持久化** —— 进程一挂，全部状态消失

> 这正是关键分界：**aura-guard 是"内存里的护栏"，Thoughtworks 说的是"durability（持久化）"**。持久化意味着崩溃后还在。这是两件不同的事。

**Java 生态的缺口**：`langgraph4j` 有检查点，但它是**框架**（你得用它写 agent）；`conductor` 是**通用工作流引擎**（不是给 agent 的）；`aws-durable-execution-sdk-java` 只有 28★ 且绑定 AWS。**没有一个是「面向 Agent 工具调用的、独立的、Java 的持久化执行 + 副作用护栏层」。**

### 2.3 组件级空白（GitHub 仓库数）

用 GitHub Search API 实测（2026-09-19）：

| 关键词 | 仓库总数 | 最高星 |
|---|---|---|
| `agent checkpoint resume llm` | **33** | 6★ |
| `idempotency llm tool` | **22** | 7★ |
| `saga pattern compensation workflow` | **17** | 7★ |
| `human-in-the-loop agent approval` | 1,895 | 695★ |
| `durable execution agent` | 440 | 32,210★（conductor） |

**读法**：类别层（durable execution、HITL）已有人做大；但**具体到「给 agent 工具调用做检查点 / 幂等 / 补偿」的实现，几乎是空的**（每个都只有二三十个仓库、个位数 star）。这既是机会，也说明**没有现成参照，得自己趟**。

### 2.4 撞车风险的诚实评估

- **话题层**：durable execution 是 2026 热点，正在快速变热 → **撞车风险中等偏高且会持续上升**
- **实现层**：Java 的 agent 持久化执行层几近空白 → **撞车风险低**
- **中文技术圈**：腾讯云社区 2026-08 和 2026-09 已出现专门文章讨论 HITL 审批设计（见 §3.3）→ **说明这个话题正在被中文社区消化，窗口期有限**

**结论**：能做，但**必须靠"具体难题做透"而不是"概念新"来差异化**。

---

## 3. 业界标准做法（设计依据）

### 3.1 ⭐ 最重要的发现：replay-safe LLM 调用

[Diagrid: AI Agent Patterns](https://docs.diagrid.io/concepts/agent-patterns/)（最后更新 2026-07-28）给出了生产级 Agent 的**标准模式清单**。原文开篇：

> "Production AI agents on Catalyst combine a **non-deterministic component (the LLM)** with a **deterministic durable runtime** (Dapr workflows). The patterns below are how teams **reconcile the two**."

六个模式：

| # | 模式 | 原文要点 |
|---|---|---|
| 1 | **Replay-safe LLM calls** | "**The LLM response must be persisted on first call so replay returns the same value.**" |
| 2 | **Tool-call durability** | "Tools must be activities, not direct calls. A tool call that times out **resumes on the same agent step, not from scratch**." |
| 3 | **Idempotency for external side effects** | "If an activity is retried, side effects must not duplicate. Cover: **idempotency keys derived from the workflow instance ID**, conditional writes, and the '**at-least-once with idempotent receiver**' pattern." |
| 4 | **Multi-agent coordination** | agent-as-activity、handoff via pub/sub、supervisor/子 agent |
| 5 | **Long-running sessions and HITL** | "**wait_for_external_event** for human approval, **durable timers** for SLAs, and how session state is persisted across crashes." |
| 6 | **Cost implications of replay** | "Each replay re-walks the history but **does not re-call activities that already completed** — so persisted LLM responses are not re-billed." |

**模式 1 是这个项目真正的技术核心。**对应 Inngest 的判断：「agent 是概率性的，同样的 prompt 会产出不同结果，**重试不一定产出等价结果，这让幂等比简单的缓存查询复杂得多**」。

普通后端系统的幂等问题：请求重发 → 用幂等键查表 → 返回缓存结果。**逻辑是确定的。**

Agent 系统的幂等问题：重放时 **LLM 会给出不同的决策** —— 它可能决定调另一个工具、给不同的参数、或者干脆不调。所以你**不能只记录"工具执行结果"，必须记录"LLM 的决策本身"**，否则重放根本不是同一个执行流。

这就是 §2.3 里那三个关键词仓库数只有二三十的原因 —— **这是真难点，不是有人忘了做。**

模式 6 是配套的取舍：重放会重新走一遍历史，但**已完成的 activity 不重新调用**，所以持久化过的 LLM 响应不会被重复计费。反过来说，如果你**没**持久化 LLM 响应，重放就会重复烧钱。

### 3.2 TLS 原文与业界对「durability」的定义

Temporal（[LangGraph 插件](https://temporal.io/blog/temporal-langgraph-plugin-durable-execution)）、Restate、Golem、Dapr/Diagrid 都在往 agent 上接。Inngest 给的定义：

> "Durable execution platforms share a core abstraction: **code that automatically persists its state at defined checkpoints and can resume from those checkpoints after any failure.**"

三个要素：**检查点持久化 + 崩溃后可恢复 + 恢复时不重复副作用**。

### 3.3 HITL 审批的两个具体设计点（中文社区，2026-08/09）

这两篇文章标题就是设计问题的答案，说明这些坑是真实存在的：

**[《审批通过后 Agent 改了参数怎么办？Human-in-the-loop 为什么必须绑定参数快照》](https://cloud.tencent.com.cn/developer/article/2720498)（2026-08-04）**

> 摘要原文：「人工审批真正批准的，**不应该是一个模糊的"可以执行"，而应该是某个可信主体在某个任务中提出的那一次精确行动**。」

搜索摘录中出现的关键机制：**同一工具出现「已批准但参数不一致」的调用时，记录 `tool_args_drift` 并拒绝执行。**

> 这是一个 **TOCTOU（Time-of-Check to Time-of-Use）漏洞**：审批通过后、真正执行前，参数被改掉了。审批的是"删掉测试机"，实际执行的是"删掉生产机"。**绑定参数快照 = 给审批内容算哈希，执行前校验哈希一致。**
>
> 我上一版设计里**完全没有这一条**。这是调研补上的。

**[《Agent渡劫48关-第26关-Human-in-the-loop到底应该放在哪里？》](https://cloud.tencent.com.cn/developer/article/2743415)（2026-09-15）**

> 摘要原文：「以**退款、赔付、删数据**为例，讲清高风险 Tool 的审批闸门**为什么必须放在副作用之前**：Approve/Edit/Reject 如何与 Checkpoint、超时……」
>
> 三个要点：① 闸门必须在副作用**之前** ② 审批动作是 **Approve / Edit / Reject** 三种而不是两种（Edit 意味着审批人可以改参数，这又回到参数快照问题）③ 必须与 Checkpoint 和**超时**配合。
>
> 注意这几个词：「退款、赔付、删数据」—— 和我们的外壳设计（退款 / 删主机）高度重合，说明场景选得对。

### 3.4 前端/领域无关的相关工作

- **DelAct: A Replayable Boundary Runtime for Auditable and Governed LLM Agent Workflows**（[IEEE](https://ieeexplore.ieee.org/document/11661202)）—— 学术界也在做「可重放的边界运行时」，标题几乎就是本项目。
- **微软 AIOpsLab**（986★）—— 故障注入 + 评测的学术基准，如果选运维外壳可以参考。
- **Oracle MicroTx** —— 「workflow 级别的幂等」的企业级文档。

---

## 4. 面试考察点（分两层）

### 4.1 项目层面的追问（针对这个项目本身）

| 追问 | 考什么 |
|---|---|
| 快照和幂等表谁先写？中间崩了怎么办？ | **写序与事务边界** —— 这是整个设计最容易错的地方 |
| 挂起等审批时，线程怎么办？等一小时也占着吗？ | 是否理解**长时间挂起不能占线程**，必须持久化 + 异步唤醒 |
| **补偿本身失败了怎么办？** | Saga 的最终难题：补偿要幂等、要重试、要有死信和人工兜底 |
| 幂等记录存多久？永久存吗？ | TTL 与一致性窗口的真实取舍 |
| **重放时 LLM 给出不同结果怎么办？** | ⭐ 本项目的核心技术点（§3.1） |
| 重放会不会重复烧 token？ | 成本取舍（Diagrid 模式 6） |
| **审批通过后参数被改了怎么办？** | ⭐ TOCTOU / `tool_args_drift`（§3.3） |
| Redis 挂了幂等还有效吗？ | 快路径 vs 唯一索引兜底的分工 |
| 怎么证明你的护栏真的有效？ | 需要评测集和可复现的故障注入 |

### 4.1.5 ⭐ 关键验证：这些机制就是大厂 Java 面经的原题

这一条是本报告最有价值的发现之一 —— 它证明**这个项目教的东西，正好是大厂 Java 面试要问的东西**。

[腾讯 Java 秋招面经](https://notes.kamacoder.com/interview/java/tencent-java-autumn-3-7.html)的题目原文：

> **项目中大事务和幂等性是怎么实现的？**

标准回答（面经原文，逐条对应到我们的设计）：

| 面经原话 | 我们的设计模块 |
|---|---|
| 「把事务边界尽量收缩，只把必须原子提交的数据库操作放在同一个本地事务里」 | 检查点的**事务边界设计** |
| 「像调用第三方接口、发 MQ、刷新缓存这类操作，通常会拆出去做成异步化或者**补偿机制**」 | **Saga 补偿（模块 5）** |
| 「对于跨服务一致性场景……考虑使用消息最终一致性、**TCC、Saga** 这类方案」 | **Saga 补偿（模块 5）** |
| 「**数据库唯一索引**：例如订单表对 orderNo 建唯一索引，重复请求直接插入失败」 | **幂等（模块 3）的兜底路径** |
| 「利用**去重表/幂等表**先记录请求唯一标识，处理过的请求直接返回」 | **幂等（模块 3）的快路径** |
| 「**Redis SETNX** 适合在短时去重、防重复提交」 | **幂等（模块 3）的 Redis 层** |
| 「**状态机控制**可以实现订单只能从"待支付"流转到"已支付"，不能重复扣款」 | **状态机 + 检查点（模块 2）** |
| 「如果是 **MQ 消费幂等**，我会结合消息 ID 或业务 ID 落库去重」 | **幂等（模块 3）的 at-least-once 接收方模式** |

**为什么这条重要**：普通候选人答这些题只能背八股；你答的时候可以**指着一个真实的、崩溃可复现的系统说"我这里是这么实现的，还踩过这个坑"**。这是「有项目」和「只有八股」的分水岭。

同时注意：**面经里这一整套（幂等表 + 唯一索引 + Redis SETNX + 状态机 + Saga + 补偿失败处理）恰好就是这个项目的模块 2/3/4/5。**换句话说，**做这个项目 ≈ 把大厂 Java 后端的核心场景题手写一遍**，而且是在一个比"电商订单"更有新意的载体上。

### 4.2 来自简历调研的通用规则（上一轮已查，保留）

- **牛客高赞帖**[《现在的AI后端项目是否还是太鸡肋了》](https://www.nowcoder.com/feed/main/detail/cbc53a384c01431c954f49d0f65d2127)：90% 以上的 AI 后端项目是「LangChain4j/Spring AI + 调大模型 API + 向量库 RAG + 接点 MCP」，**面后端岗挖不出亮点，面 Agent 岗扛不住拷打，两边都不讨好**。
- **面试官逐句改简历**（[B站](https://www.bilibili.com/opus/1244434132297056272)）：扣分点是「每一段都在说用了什么组件，**真正遇到了什么问题、为什么这样设计、出了异常怎么处理，反而没有讲清楚**」。
- **牛客[《被面试官夸爆的那些 AI 项目》](https://www.nowcoder.com/discuss/858729641748463616)**：被夸的是**工程基础设施 + 鲜明的技术标签 + 可被追问的量化数字**（如「快 100 倍」），不是业务 CRUD。该帖还被提及「**Human-in-the-Loop 和异常管控是淘宝闪购必考 —— 操作分级、熔断机制、审计日志**」。
- **AgentGuide**：Agent 项目合格线 = **有 trace、有 20 条以上 eval case、有工具列表 + 权限分级 + 错误处理**；简历要有 7 类证据：**场景 / 架构 / 数据 / 指标 / 优化 / 取舍 / 复盘**。

---

## 5. 修订后的项目设计（据调研修正）

### 5.1 相对上一版的三处修正

| 上一版 | 调研后修正 | 依据 |
|---|---|---|
| 六个机制平铺（幂等、检查点、审批、补偿、审计、成本） | **以「非确定性 LLM 的 replay-safe 持久化」为核心**，其余围绕它 | Diagrid 模式 1 + Inngest「重试不一定等价」 |
| 审批 = 挂起等人点确认 | **必须绑定参数快照，检测 `tool_args_drift`**；审批动作为 Approve / **Edit** / Reject | 腾讯云 2026-08-04 |
| 外壳：Docker Compose 迷你集群 + 故障注入 | **改小**，护栏层才是价值所在（见下） | cordum 用 NATS+k8s 做企业级，个人项目应主动避开这个复杂度 |

### 5.2 定位

> 一个 **Java 实现的、面向 Agent 工具调用的持久化执行内核**。它解决的核心问题是：
> **当 LLM 是非确定性的、而工具调用有真实副作用时，如何保证一次 Agent 任务崩溃后能正确恢复，且副作用恰好执行一次。**

### 5.3 核心机制（按调研重新排序）

1. **决策日志（Decision Journal）** ⭐ 核心
   - 记录的不只是工具结果，而是**每一步的 LLM 决策**（选了哪个工具、什么参数、为什么）
   - 重放时读日志而不是重新问 LLM → 保证重放确定性 + 不重复烧 token
   - 难点：日志粒度、体积、代码演进导致的日志与代码不匹配（divergence detection）

2. **检查点与恢复（Checkpoint / Resume）**
   - 崩溃后从断点续跑，已完成步骤不重跑

3. **副作用幂等（Idempotency）**
   - 幂等键由 **workflow instance id + step no** 派生（Diagrid 模式 3 的「at-least-once with idempotent receiver」）
   - Redis 快路径 + MySQL 唯一索引兜底

4. **审批闸门 + 参数快照（Approval Gate）**
   - 闸门在副作用**之前**；Approve / Edit / Reject；参数快照哈希；`tool_args_drift` 拒绝执行；超时处理

5. **Saga 补偿（Compensation）**
   - 补偿动作也要幂等 + 可重试 + 有死信兜底

6. **审计日志（Audit）** —— 谁 / 何时 / 为什么 / 参数 / 结果 / 审批人

7. **成本闸门 + 熔断** —— 预算上限；工具连续失败熔断

### 5.4 外壳（试验田）

**保持"云资源操作"**：`listHosts`(只读) / `createHost`(花钱) / `restartHost`(中危) / `deleteHost`(不可逆)。
只要一张资源表 + 一个状态机，1-2 天可完成，**目的是让「花钱」和「不可逆」这两个属性天然成立**，从而使成本闸门和审批闸门不是硬凑。

### 5.5 明确不做（防止范围失控）

- ❌ 不做通用工作流引擎（那是 conductor，32k★，做了必输）
- ❌ 不做多 Agent 协作
- ❌ 不做 RAG / 向量检索
- ❌ 不做根因分析、智能诊断（那是 AIOps 包装项目的卖点）
- ❌ 不做企业级控制平面（那是 cordum，Go + NATS + k8s）
- ❌ 不做图编辑器 / 可视化编排

---

## 6. 风险与取舍（诚实版）

| 风险 | 严重度 | 应对 |
|---|---|---|
| **范围失控** —— 「持久化执行」天然会膨胀成造 Temporal | 🔴 高 | 严格锁定 §5.5 的「不做」清单；只做单机、单进程、单任务 |
| **与 Temporal / cordum 叙事撞车** —— 面试官可能问「这不就是 Temporal 吗」 | 🟡 中 | 准备好回答：不造通用引擎，只做一个垂直切面；并说清与 cordum（治理层）的分层关系 |
| **话题正在变热** —— 中文社区 2026-08/09 已在密集讨论 | 🟡 中 | 靠实现深度差异化，不靠概念新 |
| **2-4 周可能做不完** | 🟡 中 | 按垂直切片推进，每周产出可写进简历的成果（见下） |
| **没有现成参照可抄**（相关仓库都只有个位数 star） | 🟢 低 | 但 Temporal / Diagrid / LangGraph 的文档可作设计参考 |

### 里程碑设计（为「边做边找工作」服务）

每个阶段结束时，都应该已经有一条能写进简历的内容：

| 周 | 产出 | 可写的简历条目 |
|---|---|---|
| 1 | 外壳 + 最小 Agent Loop + 审计日志 | 「实现 Agent 工具调用的审计留痕，记录决策链」 |
| 2 | 决策日志 + 检查点 + 崩溃恢复 | 「实现崩溃后断点续跑，`kill -9` 后恢复且不重复执行副作用」 |
| 3 | 幂等 + 审批闸门 + 参数快照 | 「实现高危操作审批闸门，绑定参数快照防止审批后参数漂移」 |
| 4 | Saga 补偿 + 成本闸门 + 评测集 + 演示脚本 | 「构建故障注入评测集，量化副作用的 exactly-once 保证」 |

---

## 7. 参考资料

**方向验证**
- [Thoughtworks Technology Radar — Ignoring durability in agent workflows](https://www.thoughtworks.com/radar/techniques/ignoring-durability-in-agent-workflows)（2026-04，Caution）
- [Inngest — Durable Execution: The Key to Harnessing AI Agents in Production](https://www.inngest.com/blog/durable-execution-key-to-harnessing-ai-agents)（2026-02-19）
- [Temporal — LangGraph Plugin adds Durable Execution](https://temporal.io/blog/temporal-langgraph-plugin-durable-execution)
- [Databricks — Build durable agents with Temporal and Lakebase](https://www.databricks.com/blog/build-durable-agents-temporal-and-lakebase)

**设计模式（最重要）**
- [Diagrid — AI Agent Patterns](https://docs.diagrid.io/concepts/agent-patterns/)（2026-07-28）
- [cubxxw — Agent Engineering Harness: The Eight Pillars](https://github.com/cubxxw/blog/blob/main/content/en/ai-agent/posts/agent-engineering-the-98-percent-harness.md)（2026-06-17）
- [OpenAI — Unrolling the Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/)（2026-01-23）
- arXiv 2604.14228 《Dive into Claude Code》

**HITL 具体设计**
- [审批通过后 Agent 改了参数怎么办？Human-in-the-loop 为什么必须绑定参数快照](https://cloud.tencent.com.cn/developer/article/2720498)（2026-08-04）
- [Agent渡劫48关-第26关-Human-in-the-loop到底应该放在哪里？](https://cloud.tencent.com.cn/developer/article/2743415)（2026-09-15）
- [DelAct: A Replayable Boundary Runtime for Auditable and Governed LLM Agent Workflows](https://ieeexplore.ieee.org/document/11661202)（IEEE）

**竞品**
- [cordum-io/cordum](https://github.com/cordum-io/cordum)（Go，action firewall，507★）
- [auraguardhq/aura-guard](https://github.com/auraguardhq/aura-guard)（Python，进程内护栏，7★）
- [ThousandBirdsInc/chidori](https://github.com/ThousandBirdsInc/chidori)（Rust，1364★）
- [langgraph4j/langgraph4j](https://github.com/langgraph4j/langgraph4j)（Java，2009★）
- [conductor-oss/conductor](https://github.com/conductor-oss/conductor)（Java，32210★）

**简历 / 面试**
- [牛客 — 现在的AI后端项目是否还是太鸡肋了](https://www.nowcoder.com/feed/main/detail/cbc53a384c01431c954f49d0f65d2127)
- [B站 — 面试官视角，手把手带大家改一份 Agent项目简历](https://www.bilibili.com/opus/1244434132297056272)
- [牛客 — 我做过的，被面试官夸爆的那些Ai项目(二)](https://www.nowcoder.com/discuss/858729641748463616)
- [AgentGuide — 简历编写指南](https://github.com/adongwanai/AgentGuide/blob/main/docs/04-interview/20-resume-guide.md)
- [AgentGuide — 开发岗专项面试题库](https://github.com/adongwanai/AgentGuide/blob/main/docs/04-interview/06-development-specialized.md)

---

---

## 8. 补充与更正（第二轮，2026-09-19 晚）

### 8.1 ⚠️ 更正：Diagrid 那份「标准做法」我给高了

§3.1 引用的 [Diagrid AI Agent Patterns](https://docs.diagrid.io/concepts/agent-patterns/)，**六个模式的正文几乎全部是 `Stub — populate`（占位未写）**。

它给出的**模式清单**仍然有参考价值（说明这六件事是产业界公认要处理的），但**不能当作「成熟标准做法」引用**。上一版报告把它当权威依据，是判断失误，此处更正。

### 8.2 时效性核查结果

| 来源 | 实测状态 | 判断 |
|---|---|---|
| [AgentGuide](https://github.com/adongwanai/AgentGuide) | 9,755★，最后 push **2026-09-15** | ✅ 新鲜 |
| cubxxw Harness 八支柱 | 27★，最后 push **2026-09-18** | ✅ 新鲜但小众 |
| Thoughtworks Radar | 2026-04-15 发布，评级 Caution | ✅ 仍有效 |
| Inngest durable execution | 2026-02-19 | ✅ |
| [12-factor-agentops](https://github.com/boshu2/12-factor-agentops) | 33★，2026-06-16，衍生自 2025 年书 | ⚠️ 二手衍生，不作权威 |
| harness_engineering_book | 4★，2026-04-08 | ❌ 太小，不引用 |

**生态版本现状（2026-09-19 实测）**：

| 框架 | 版本 | 发布日期 |
|---|---|---|
| [spring-projects/spring-ai](https://github.com/spring-projects/spring-ai) | **v2.0.1** | 2026-08-21（v2.0.0 于 2026-06-12） |
| [langchain4j](https://github.com/langchain4j/langchain4j) | **1.19.3** | 2026-09-15 |
| [langgraph4j](https://github.com/langgraph4j/langgraph4j) | **v1.9.0** | 2026-09-17 |
| [cordum](https://github.com/cordum-io/cordum) | v1.1.0 | 2026-06-03（提交仍活跃至 09-17） |

**结论：这个领域 5 个月内变化极快，任何设计都必须对照「LangGraph 是不是已经做了」来检查。**

### 8.3 ⭐⭐ 最重要的发现：主流框架**都没做对**

**《Resume Means Resume: A Machine-Checked Conformance Contract for Checkpoint, Interrupt, and Resume Semantics in Workflow Persistence Layers》**
Sajjad Khan，[arXiv:2608.03836](https://arxiv.org/abs/2608.03836)（v1 2026-08-04，v3 2026-08-08）

**核心判词**：

> "Five widely deployed agent workflow frameworks answer differently, **none exposes a machine-checkable contract**, and **measured behavior violates even the fragments they state**."

**实测结果（pinned releases）**：

| 框架 | 实测行为（论文原文） |
|---|---|
| **LangGraph 1.2.9** | "durably records a second resume value and never consults it, **persists schema-invalid state silently**, and **re-executes durably recorded work after a real SIGKILL**: exactly-once across interrupts, **at-least-once across crashes**, on one API." |
| **CrewAI 1.15.2** | "re-executes completed effect-bearing methods **against its written claim**." |
| **pydantic-graph 1.x** | "**cannot resume after a mid-node crash**." |
| 全部 | "**no two probed frameworks share a conformance profile**." |

**最严重的一条（并发恢复）**：

> "Consume-once holds sequentially and fails under concurrent delivery: **k processes resuming one parked interrupt fire the gated effect k times**, saturation 1.0 in **36 of 40 cells**, and the failure **crosses hosts**."

**RESUME CONTRACT 六个性质**（本项目的完成定义）：

1. **prefix continuation** —— 恢复必须从断点继续，不能跳步或重头
2. **effect exactly-once** —— 有副作用的操作恰好执行一次
3. **fork determinism** —— 从同一检查点分叉，结果确定
4. **checkpoint validity** —— 检查点本身必须是合法状态
5. **consume-once** —— 挂起的事件只能被消费一次（**并发下最难**）
6. **recovery determinism** —— 恢复过程是确定的

配套还有 39 格故障矩阵、TLA+ 穷举验证（7.4M 状态）、TLAPS 无界证明（196 条义务）。

**对本项目的意义**：

- 项目从「我造了个小轮子」升级为「**主流框架都没做对的语义，我实现并验证了**」
- 提供了**有界的完成定义**（六性质 + 故障矩阵），直接解决范围失控
- 提供了**可对标的失败证据**：`LangGraph 1.2.9 崩溃后是 at-least-once 而非 exactly-once` —— 这句话有论文背书

### 8.4 相关的当代工程实践

**[LangChain Delta Channels](https://www.langchain.com/blog/delta-channels-evolving-agent-runtime)（2026-05-12）** —— 量化了检查点的存储问题：

> LangGraph 默认每步写全量快照，而消息历史是**只增不减**的累加器 → 检查点存储 **O(N²)** 增长。
> "For a coding agent running 200 turns, current checkpointing methods serialize **5.3GB** to the checkpointer. **Delta channels bring it to 129 MB, over a 40x reduction.**"
> 做法：`DeltaChannel` 每步只写增量，每 K 步（默认 50）写一次全量快照，以此**界定恢复成本**、让**恢复延迟保持平坦**。

> 这是「**可被追问的量化数字**」的范例（牛客帖说这类数字最容易被夸）。也是我们自己实现检查点时必须面对的真实取舍。

**Temporal 相关**
- [The fallacy of the graph: Why your next agentic workflow should be code, not a diagram](https://temporal.io/blog/the-fallacy-of-the-graph-why-your-next-workflow-should-be-code-not-a-diagram) —— 支持「不做可视化编排」的决策
- [The thread is the Workflow: Durable AI agents without changing Agent code](https://temporal.io/blog/manetu-the-thread-is-the-workflow)
- [Temporal LangGraph Plugin adds Durable Execution](https://temporal.io/blog/temporal-langgraph-plugin-durable-execution)

**Orkes**
- [Late-Bound Sagas: Why Your Agent Is Not an LLM in a Loop](https://orkes.io/blog/late-bound-sagas-why-your-agent-is-not-an-llm-in-a-loop) —— 补偿相关（本项目首轮不做）

**反方观点（面试可能被问）**
- [Why most webhook-triggered agents don't need a workflow engine](https://hookdeck.com/webhooks/platforms/most-webhook-agents-dont-need-a-workflow-engine) —— 「很多场景根本不需要工作流引擎」，需要准备反驳

### 8.5 AI 编程常见失败模式

来源：[12-Factor AgentOps Failure Patterns Catalog](https://github.com/boshu2/12-factor-agentops/blob/main/docs/reference/failure-patterns.md)，整理自 Gene Kim & Steve Yegge《Vibe Coding》(IT Revolution Press, 2025)。

| 失败模式 | 严重度 | 症状 | 预防措施（本项目适用） |
|---|---|---|---|
| **"测试全绿"的谎言** | 🔴 High | AI 声称测试通过，实际未编译 | **必须展示真实测试输出**，绝不接受"测试通过了"的口头声明 |
| Eldritch Horror / Bridge Torching / Repo Deletion | 🔴 Critical | 数月工作量损失 | 每步提交 git；不用破坏性命令 |
| **上下文失忆** | 🟡 Medium | 上下文用量超 **40%** 后性能断崖式下降 | 设计与决策必须**落在文件里**，不能只在对话中 |
| 调试螺旋 | 🟡 Medium | AI 反复加日志而不找根因 | 连续 3 轮未解决 → 停下来做根因分析 |
| **编辑到一半忘了** | 🟡 Medium | 文件 > 500 行时改一半破掉 | **硬约束：文件 < 500 行，函数 < 50 行** |
| Stewnami（代码屎山） | 🟡 Medium | 大量生成代码堆积 | 模块化 + 清晰边界 |

> ⚠️ 最后一条的「文件 < 500 行 / 函数 < 50 行」**不是风格偏好，是让代码能被 AI 稳定修改的硬要求**，直接服务于「后续能快速修改快速落地」。

### 8.6 Agent 项目常见误区（[AgentGuide 原文](https://github.com/adongwanai/AgentGuide/blob/main/docs/03-practice/05-ship-agent-project.md)）

> | 误区 | 更好的做法 |
> |---|---|
> | 先选最强模型 | 先搭好 harness，模型应能快速切换 |
> | **先想清架构再动手** | **Agent 是反馈密集型，先跑通最小闭环** |
> | 框架越大越好 | 轻量基座 + 清晰边界更适合个人项目 |
> | 只看最终输出 | 看 trajectory，失败常发生在工具选择和上下文管理 |
> | 没有测试就加功能 | **先有 eval，再扩工具、加 memory、上 multi-agent** |

同一文档给出的「**可靠性六件套**」（比本报告 §5.3 的七模块**简单得多**，可作为裁剪参考）：

| 机制 | 做什么 | 不做的后果 |
|---|---|---|
| Idempotency | 工具调用带幂等 key | 重启后重复发邮件、重复扣款、重复写入 |
| Timeout + Circuit Breaker | 每个工具有超时，连续失败后熔断 | trajectory 被慢工具拖垮 |
| Rate Limit Aware Retry | 指数退避、jitter、多 provider fallback | 限流后整个系统挂掉 |
| Cost Guard | 每个 trajectory 设 token / 金额上限 | bug 无限循环烧成本 |
| Permission Tier | 读自动、写 dry-run + 人审、不可逆显式确认 | Agent 执行危险动作 |
| Observability | 记录 prompt、tool call、result、token、延迟 | 出问题无法回放和归因 |

**简历级 Agent 项目的最终标准**（同文档）：

> - 有明确用户和任务，不是泛泛的"智能助手"
> - 有可运行入口，别人 clone 后能按 README 跑起来
> - 有清晰 agent loop、工具注册、上下文管理、权限边界
> - 有日志或 trace，失败后能定位问题
> - 有 eval case，能说明通过率、失败类型、成本和延迟
> - 有复盘：为什么这样设计，替代方案是什么，哪些机制真的带来提升

---

## 附：尚未验证的问题

诚实标注，避免把推断当结论：

1. 「Java 版 agent 持久化执行层」是否真的没人做 —— 我只做了关键词搜索，**没有穷尽 GitHub**。可能存在我没搜到的项目。
2. 这类项目在**真实面试**中是否被问到过 —— 我找到的都是「简历怎么写」和「技术怎么做」，**没有找到有人写「我做了这个项目然后面试被问了什么」**。这是本报告最大的缺口。
3. 那篇《HITL 到底应该放在哪里》我只拿到摘要（正文是 JS 渲染），**Approve/Edit/Reject 与 Checkpoint、超时的具体配合方式没有读到全文**。
