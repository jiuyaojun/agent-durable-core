# agent-durable-core

> 一个 Java 实现的 **Agent 工具调用持久化执行内核**。
>
> 解决的问题：**当 LLM 是非确定性的、而工具调用有真实副作用时，如何保证一次 Agent 任务崩溃后正确恢复，且副作用恰好执行一次。**

---

## 为什么做这个

正常测试碰不到「崩溃恰好发生在副作用之后、日志写入之前」这个极窄窗口。但真实世界里它意味着**重复退款、重复发短信、重复创建服务器**。

这个项目的做法是：**先用故障注入把这个窗口稳定复现出来，再逐个消灭它。**

当前已复现的缺陷（`DurableExecutorCrashTest` 实测输出）：

```
[真实输出] 崩溃后副作用次数 = 1
[真实输出] 崩溃后日志行数 = 0
[真实输出] 重启后副作用总次数 = 2      ← 缺陷：应该是 1
```

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

预期：`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`

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
| 1 | prefix continuation | ⬜ 计划 2 |
| 2 | effect exactly-once | ⬜ 计划 2 |
| 3 | fork determinism | ⬜ 计划 2 |
| 4 | checkpoint validity | ⬜ 计划 2 |
| 5 | consume-once（并发） | ⬜ 计划 3 |
| 6 | recovery determinism | ⬜ 计划 2 |

> 选这篇论文作为基线的原因：它实测发现 **LangGraph 1.2.9 在 SIGKILL 后是 at-least-once 而不是 exactly-once**，
> CrewAI 和 pydantic-graph 也各有不符。主流框架都没做对这件事。

---

## 进度

- [x] **计划 1**：工程骨架、决策日志、故障注入器、缺陷复现
- [ ] 计划 2：恢复语义（性质 1/3/4/6）+ 副作用 exactly-once（性质 2）
- [ ] 计划 3：并发 consume-once（性质 5）+ 完整故障矩阵
- [ ] 计划 4：审批闸门 + 参数快照
- [ ] 计划 5：演示脚本 + 复盘文档
