# 实施计划 3：中断与审批闸门（FD / CO / FI）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现人工审批中断（interrupt）与恢复（resume），让高风险的 Agent 操作在真正执行前必须经过人工放行；并满足论文的 **FD / CO-c / CO-e / FI** 四条性质 —— 其中包括**论文实测主流框架全部失败**的并发恢复场景。

**Architecture:** 中断本身就是一次「持久化的挂起」：到达需审批的步骤时，把上下文写进 `interrupt` 表并停下；审批人调用 `resume` 时，用**数据库条件更新（CAS）抢占**该中断 —— 只有一个 resume 能成功，其余要么惰性化（无分叉意图），要么开新分支（有分叉意图）。

**Tech Stack:** 沿用（Java 17 / Maven / MySQL 8 / JUnit 5），不新增依赖。

## Global Constraints

- Java 17；Maven；不使用 Spring Boot；不使用 Docker
- 数据库：`.\scripts\devdb-start.ps1` 启动（端口 3307）
- 单文件 < 500 行，单函数 < 50 行
- 每个 Task 结束 `git commit`；测试必须展示真实执行输出

### 本计划覆盖的性质（论文原文）

| 性质 | 论文原文要点 |
|---|---|
| **FD** fork determinism | 携带**分叉意图**的 resume 以不同值到达同一中断点 ⇒ 各分支产出 `o_k = f(v_k)`；`v_j ≠ v_1 ⇒ o_j ≠ o_1`（f 单射时） |
| **CO-c** consumption count | 「An interrupt is consumed by **at most one resume**」 |
| **CO-e** effect inertness | 「A resume **without fork intent** addressed to a completed run or an already-consumed interrupt — **including byte-identical re-delivery of a prior resume** — is **inert with respect to effects**」 |
| **FI** fork-intent expressibility | resume API 必须能在**线缆上**表达分叉判别符 |

> **FD 与 CO 的张力（论文原话）**：
> 「FD demands the new value be honored on a new branch, CO demands a stray re-delivery be inert.
> **Without a discriminator the two are jointly unsatisfiable on identical traffic.**」
> 所以**分叉意图就是那个判别符** —— 有它走 FD，没它走 CO。这是本计划的核心。

### 关键设计决策

**D10. 中断消费必须用数据库条件更新（CAS），不能用悲观锁或应用层判断。**
论文实测：k 个进程同时恢复一个挂起中断，会让被门控的副作用执行 k 次（40 格中 36 格饱和，且故障跨主机）。
修法就是论文说的「an opt-in gate claims consumption in the shared store」：
```sql
UPDATE interrupt SET status='CONSUMED', consumed_by=? 
WHERE workflow_id=? AND step_no=? AND status='PARKED'
```
影响行数为 1 即抢占成功；为 0 说明已被别人消费。**跨进程、跨主机都成立，因为判断和执行是同一条 SQL。**

**D11. CO-c 与 CO-e 必须分开实现、分开测试。**
论文点出一个精妙陷阱：只做幂等的话，**副作用计数是对的，但审批记录被消费了两次** ——
「a gate that serves its effect idempotently from the durable record can consume one human approval twice while the effect count stays at one, which leaves the **approval trail wrong** and the effect ledger right.」
所以 CO-c 观测的是**消费计数**，CO-e 观测的是**副作用次数**，两个指标都要测。

**D12. 分叉结果也按 (workflow_id, step_no, branch_id) 唯一约束去重。**
同一 branchId 重复投递 ⇒ 复用同一产出（保证 FD 的确定性），而不是重复执行分支决策。

---

## File Structure

```
src/main/
├── resources/schema.sql                       [改] 新增 interrupt / resume_attempt / branch
├── java/com/durable/
│   ├── db/Db.java                             [改] TABLES 增加三张表
│   └── interrupt/
│       ├── InterruptStatus.java               [新] PARKED / CONSUMED
│       ├── Interrupt.java                     [新] 一个挂起的中断
│       ├── ResumeKind.java                    [新] CONSUMED / INERT / FORKED / REPLAYED
│       ├── ResumeCommand.java                 [新] 一次 resume 请求（含分叉意图）
│       ├── ResumeOutcome.java                 [新] 结果
│       └── ApprovalGate.java                  [新] 核心：park / resume / CAS 抢占

src/test/java/com/durable/
├── interrupt/ApprovalGateTest.java            [新] 基本生命周期
└── contract/
    ├── ForkDeterminismTest.java               [新] FD
    ├── ConsumeOnceTest.java                   [新] CO-c / CO-e
    └── ConcurrentResumeTest.java              [新] ⭐ 论文实测失败的那个场景
```

---

## Task 1: 中断模型与审批闸门

**Interfaces（后续任务依赖，必须一致）**

```java
enum InterruptStatus { PARKED, CONSUMED }

record Interrupt(String workflowId, int stepNo, InterruptStatus status,
                 String consumedBy, String question, Instant createdAt)

enum ResumeKind { CONSUMED, INERT, FORKED, REPLAYED }

record ResumeCommand(String workflowId, int stepNo, String resumeId,
                     boolean forkIntent, String branchId, String value)

record ResumeOutcome(ResumeKind kind, String branchId, String value)

class ApprovalGate {
    ApprovalGate(DataSource dataSource);
    Interrupt park(String workflowId, int stepNo, String question);
    Optional<Interrupt> find(String workflowId, int stepNo);
    ResumeOutcome resume(ResumeCommand command, Function<String, String> branchDecision);
    long forkCount(String workflowId, int stepNo);
    long consumedCount(String workflowId, int stepNo);
}
```

**语义规则（必须严格遵守）**

| 情形 | 结果 |
|---|---|
| 中断为 PARKED，resume **无**分叉意图 | CAS 抢占 → 成功则 `CONSUMED`；失败（被抢）→ `INERT` |
| 中断已 CONSUMED，resume **无**分叉意图 | `INERT`（副作用不触发，CO-e） |
| resume **有**分叉意图（带 branchId，首次） | 走 `f(value)` → `FORKED`，结果按 branchId 落库 |
| resume **有**分叉意图（同 branchId 重复） | 复用已有产出 → `REPLAYED`（FD 的确定性） |
| resume **无**分叉意图但带 branchId | 视为无分叉意图（branchId 被忽略）→ 走 CO 分支 |

- [ ] **Step 1: 更新 `schema.sql`，追加三张表**

```sql
-- 人工审批的中断点。一个 (workflow_id, step_no) 只能有一个中断。
-- status='PARKED' 表示等待审批；status='CONSUMED' 表示已被某次 resume 消费。
-- CO-c 的落点：抢占用的是条件更新 UPDATE ... WHERE status='PARKED'。
CREATE TABLE IF NOT EXISTS interrupt (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    consumed_by VARCHAR(64)  NULL,
    question    JSON         NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每一次 resume 尝试都留痕（含被惰性化拒绝的）。
-- CO 要求「approval trail」正确，所以被拒绝的投递也必须记录。
CREATE TABLE IF NOT EXISTS resume_attempt (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    resume_id   VARCHAR(64)  NOT NULL,
    fork_intent TINYINT(1)   NOT NULL,
    branch_id   VARCHAR(64)  NULL,
    value       JSON         NOT NULL,
    outcome     VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no, resume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 分叉分支的产出。同一 branchId 重复投递复用同一产出（FD 的确定性）。
CREATE TABLE IF NOT EXISTS branch (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    branch_id   VARCHAR(64)  NOT NULL,
    outcome     JSON         NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 更新 `Db.TABLES`**

```java
    private static final List<String> TABLES =
            List.of("journal", "effect_ledger", "checkpoint", "host",
                    "branch", "resume_attempt", "interrupt");
```

- [ ] **Step 3: 模型类**

`InterruptStatus.java`
```java
package com.durable.interrupt;

/** 中断的生命周期。 */
public enum InterruptStatus {
    /** 已挂起，等待审批。 */
    PARKED,
    /** 已被某次 resume 消费（至多一次）。 */
    CONSUMED
}
```

`ResumeKind.java`
```java
package com.durable.interrupt;

/** 一次 resume 的结果类别。 */
public enum ResumeKind {
    /** 无分叉意图，抢到了中断 —— 本次消费有效。 */
    CONSUMED,
    /** 无分叉意图，但中断已被消费 —— 惰性拒绝，副作用不得触发（CO-e）。 */
    INERT,
    /** 有分叉意图，开辟了新分支（FD）。 */
    FORKED,
    /** 有分叉意图，但该分支已存在 —— 复用已有产出（FD 的确定性）。 */
    REPLAYED
}
```

`Interrupt.java`
```java
package com.durable.interrupt;

import java.time.Instant;
import java.util.Objects;

/** 一个挂起的中断点。 */
public record Interrupt(String workflowId, int stepNo, InterruptStatus status,
                        String consumedBy, String question, Instant createdAt) {

    public Interrupt {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isParked() {
        return status == InterruptStatus.PARKED;
    }
}
```

`ResumeCommand.java`
```java
package com.durable.interrupt;

import java.util.Objects;

/**
 * 一次 resume 请求。
 *
 * @param resumeId  本次请求的唯一标识，用于识别「字节相同的重复投递」
 * @param forkIntent 是否携带分叉意图 —— 这是 FD 与 CO 的判别符（论文 Definition 2）
 * @param branchId   分叉判别符，forkIntent=true 时必填
 * @param value      审批人给出的值
 */
public record ResumeCommand(String workflowId, int stepNo, String resumeId,
                            boolean forkIntent, String branchId, String value) {

    public ResumeCommand {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(resumeId, "resumeId");
        Objects.requireNonNull(value, "value");
        if (forkIntent && (branchId == null || branchId.isBlank())) {
            throw new IllegalArgumentException("带分叉意图的 resume 必须提供 branchId（FI：判别符要能在 API 上表达）");
        }
    }

    /** 无分叉意图的普通审批。 */
    public static ResumeCommand approve(String workflowId, int stepNo, String resumeId, String value) {
        return new ResumeCommand(workflowId, stepNo, resumeId, false, null, value);
    }

    /** 带分叉意图的审批：同一中断点用不同值走另一条分支。 */
    public static ResumeCommand fork(String workflowId, int stepNo, String resumeId,
                                     String branchId, String value) {
        return new ResumeCommand(workflowId, stepNo, resumeId, true, branchId, value);
    }
}
```

`ResumeOutcome.java`
```java
package com.durable.interrupt;

import java.util.Objects;
import java.util.Optional;

/** 一次 resume 的结果。 */
public record ResumeOutcome(ResumeKind kind, String branchId, String value) {

    public ResumeOutcome {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
    }

    public static ResumeOutcome consumed(String value) {
        return new ResumeOutcome(ResumeKind.CONSUMED, null, value);
    }

    public static ResumeOutcome inert() {
        return new ResumeOutcome(ResumeKind.INERT, null, "");
    }

    public static ResumeOutcome forked(String branchId, String value) {
        return new ResumeOutcome(ResumeKind.FORKED, branchId, value);
    }

    public static ResumeOutcome replayed(String branchId, String value) {
        return new ResumeOutcome(ResumeKind.REPLAYED, branchId, value);
    }

    public boolean isEffectBearing() {
        return kind == ResumeKind.CONSUMED || kind == ResumeKind.FORKED;
    }

    public Optional<String> branch() {
        return Optional.ofNullable(branchId);
    }
}
```

- [ ] **Step 4: 失败测试 `ApprovalGateTest`**

```java
package com.durable.interrupt;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("审批闸门：基本生命周期")
class ApprovalGateTest {

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("挂起后可以被一次审批消费")
    void parkThenConsume() {
        gate.park("wf-1", 0, "{\"action\":\"deleteHost\",\"target\":\"prod-1\"}");

        Interrupt parked = gate.find("wf-1", 0).orElseThrow();
        System.out.println("[真实输出] 挂起状态 = " + parked.status() + ", 问题 = " + parked.question());
        assertTrue(parked.isParked());

        ResumeOutcome outcome = gate.resume(
                ResumeCommand.approve("wf-1", 0, "r-1", "yes"), v -> "decision:" + v);

        System.out.println("[真实输出] 审批结果 = " + outcome.kind());
        assertEquals(ResumeKind.CONSUMED, outcome.kind());
        assertEquals("yes", outcome.value());

        Interrupt after = gate.find("wf-1", 0).orElseThrow();
        assertEquals(InterruptStatus.CONSUMED, after.status());
        assertEquals("r-1", after.consumedBy());
    }

    @Test
    @DisplayName("同一中断点重复 park 会被拒绝")
    void parkIsUnique() {
        gate.park("wf-1", 0, "{}");
        assertThrows(IllegalStateException.class, () -> gate.park("wf-1", 0, "{}"));
        System.out.println("[真实输出] 重复 park 被拒绝");
    }

    @Test
    @DisplayName("未挂起的中断收到 resume 视为惰性")
    void resumeOnUnknownInterruptIsInert() {
        ResumeOutcome outcome = gate.resume(
                ResumeCommand.approve("wf-none", 0, "r-1", "yes"), v -> "decision:" + v);

        System.out.println("[真实输出] 未知中断的 resume 结果 = " + outcome.kind());
        assertEquals(ResumeKind.INERT, outcome.kind());
    }

    @Test
    @DisplayName("带分叉意图但缺 branchId 在构造时就被拒绝（FI）")
    void forkWithoutBranchIdRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new ResumeCommand("wf-1", 0, "r-1", true, null, "yes"));
        System.out.println("[真实输出] 拒绝原因: " + ex.getMessage());
    }
}
```

- [ ] **Step 5: 运行确认失败**

Run: `mvn -q test -Dtest=ApprovalGateTest`
Expected: 编译失败，`cannot find symbol: class ApprovalGate`

- [ ] **Step 6: 实现 `ApprovalGate`**

```java
package com.durable.interrupt;

import com.durable.json.Json;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 审批闸门：高风险操作在执行前必须经过人工放行。
 *
 * 核心是 CO-c 的实现 —— 用【数据库条件更新】抢占中断：
 *
 *   UPDATE interrupt SET status='CONSUMED', consumed_by=?
 *   WHERE workflow_id=? AND step_no=? AND status='PARKED'
 *
 * 影响行数为 1 ⇒ 抢到了；为 0 ⇒ 已被别人消费。
 * 判断和执行是同一条 SQL，所以跨线程、跨进程、跨主机都成立。
 *
 * 为什么不用「先 SELECT 查状态，再 UPDATE」：那是 TOCTOU 竞态。
 * 论文实测 k 个进程同时恢复一个挂起中断，会让被门控的副作用执行 k 次。
 */
public class ApprovalGate {

    private static final int ER_DUP_ENTRY = 1062;

    private static final String SQL_PARK =
            "INSERT INTO interrupt (workflow_id, step_no, status, consumed_by, question, created_at) "
                    + "VALUES (?, ?, 'PARKED', NULL, ?, ?)";

    private static final String SQL_SELECT =
            "SELECT workflow_id, step_no, status, consumed_by, question, created_at "
                    + "FROM interrupt WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_CLAIM =
            "UPDATE interrupt SET status = 'CONSUMED', consumed_by = ? "
                    + "WHERE workflow_id = ? AND step_no = ? AND status = 'PARKED'";

    private static final String SQL_INSERT_ATTEMPT =
            "INSERT INTO resume_attempt "
                    + "(workflow_id, step_no, resume_id, fork_intent, branch_id, value, outcome, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_INSERT_BRANCH =
            "INSERT INTO branch (workflow_id, step_no, branch_id, outcome, created_at) VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_FIND_BRANCH =
            "SELECT outcome FROM branch WHERE workflow_id = ? AND step_no = ? AND branch_id = ?";

    private static final String SQL_COUNT_BRANCH =
            "SELECT COUNT(*) FROM branch WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_COUNT_CONSUMED =
            "SELECT COUNT(*) FROM interrupt WHERE workflow_id = ? AND step_no = ? AND status = 'CONSUMED'";

    private static final String SQL_COUNT_ATTEMPT =
            "SELECT COUNT(*) FROM resume_attempt WHERE workflow_id = ? AND step_no = ?";

    private final DataSource dataSource;

    public ApprovalGate(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 挂起一个中断点，等待人工审批。 */
    public Interrupt park(String workflowId, int stepNo, String question) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_PARK)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, question);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY) {
                throw new IllegalStateException(
                        "该中断点已存在: " + workflowId + "/" + stepNo, e);
            }
            throw new IllegalStateException("挂起中断失败", e);
        }
        return find(workflowId, stepNo).orElseThrow();
    }

    public Optional<Interrupt> find(String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Interrupt(
                        rs.getString("workflow_id"),
                        rs.getInt("step_no"),
                        InterruptStatus.valueOf(rs.getString("status")),
                        rs.getString("consumed_by"),
                        rs.getString("question"),
                        rs.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取中断失败", e);
        }
    }

    /**
     * 审批一次中断。
     *
     * @param branchDecision 分支决策函数 f —— 把审批值映射成分支产出。
     *                       论文要求 f 单射时，v 不同则产出不同。
     */
    public ResumeOutcome resume(ResumeCommand command, Function<String, String> branchDecision) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(branchDecision, "branchDecision");

        if (command.forkIntent()) {
            return fork(command, branchDecision);
        }
        return consume(command);
    }

    /** 无分叉意图：走 CO 路径。 */
    private ResumeOutcome consume(ResumeCommand command) {
        boolean claimed = claimInterrupt(command.workflowId(), command.stepNo(), command.resumeId());
        ResumeOutcome outcome = claimed
                ? ResumeOutcome.consumed(command.value())
                : ResumeOutcome.inert();
        recordAttempt(command, outcome.kind());
        return outcome;
    }

    /** 有分叉意图：走 FD 路径。同一 branchId 重复投递复用已有产出。 */
    private ResumeOutcome fork(ResumeCommand command, Function<String, String> branchDecision) {
        Optional<String> existing = findBranchOutcome(
                command.workflowId(), command.stepNo(), command.branchId());
        if (existing.isPresent()) {
            ResumeOutcome outcome = ResumeOutcome.replayed(command.branchId(), existing.get());
            recordAttempt(command, outcome.kind());
            return outcome;
        }
        String produced = branchDecision.apply(command.value());
        if (!insertBranch(command, produced)) {
            String winner = findBranchOutcome(
                    command.workflowId(), command.stepNo(), command.branchId()).orElse(produced);
            ResumeOutcome outcome = ResumeOutcome.replayed(command.branchId(), winner);
            recordAttempt(command, outcome.kind());
            return outcome;
        }
        ResumeOutcome outcome = ResumeOutcome.forked(command.branchId(), produced);
        recordAttempt(command, outcome.kind());
        return outcome;
    }

    /** CAS 抢占。返回 true 表示本次抢到了。 */
    private boolean claimInterrupt(String workflowId, int stepNo, String resumeId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_CLAIM)) {
            ps.setString(1, resumeId);
            ps.setString(2, workflowId);
            ps.setInt(3, stepNo);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("抢占中断失败", e);
        }
    }

    private boolean insertBranch(ResumeCommand command, String outcome) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_BRANCH)) {
            ps.setString(1, command.workflowId());
            ps.setInt(2, command.stepNo());
            ps.setString(3, command.branchId());
            ps.setString(4, Json.write(outcome));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_DUP_ENTRY) {
                return false;
            }
            throw new IllegalStateException("写入分支失败", e);
        }
    }

    private Optional<String> findBranchOutcome(String workflowId, int stepNo, String branchId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_BRANCH)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(Json.read(rs.getString(1), String.class))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取分支失败", e);
        }
    }

    private void recordAttempt(ResumeCommand command, ResumeKind kind) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_ATTEMPT)) {
            ps.setString(1, command.workflowId());
            ps.setInt(2, command.stepNo());
            ps.setString(3, command.resumeId());
            ps.setBoolean(4, command.forkIntent());
            ps.setString(5, command.branchId());
            ps.setString(6, Json.write(command.value()));
            ps.setString(7, kind.name());
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() != ER_DUP_ENTRY) {
                throw new IllegalStateException("记录审批尝试失败", e);
            }
        }
    }

    /** 已开出的分支数（FD 的观测点）。 */
    public long forkCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_BRANCH, workflowId, stepNo);
    }

    /** 该中断被消费的次数。正常恒为 0 或 1（CO-c 的观测点）。 */
    public long consumedCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_CONSUMED, workflowId, stepNo);
    }

    /** 收到的审批投递次数（含被拒绝的）。 */
    public long attemptCount(String workflowId, int stepNo) {
        return count(SQL_COUNT_ATTEMPT, workflowId, stepNo);
    }

    private long count(String sql, String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计失败", e);
        }
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `mvn -q test -Dtest=ApprovalGateTest`
Expected: `Tests run: 4, Failures: 0` / `BUILD SUCCESS`

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat(interrupt): approval gate with CAS-based consumption"
```

---

## Task 2: FD —— 分叉确定性

**Files:** Create `src/test/java/com/durable/contract/ForkDeterminismTest.java`

**论文原文**
> "If resumes carrying fork intent with values v₁,…,v_m are addressed to the same interrupt checkpoint, then each branch outcome satisfies o_k = f(v_k); in particular **v_j ≠ v₁ ⇒ o_j ≠ o₁** whenever f is injective."

- [ ] **Step 1: 写测试**

```java
package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("契约 · FD：分叉确定性")
class ForkDeterminismTest {

    private ApprovalGate gate;

    /** 单射的分支决策函数：不同的审批值必然产生不同的产出。 */
    private static final Function<String, String> INJECTIVE = v -> "decision:" + v;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
        gate.park("wf-fd", 0, "{\"action\":\"deleteHost\"}");
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("同一中断点用不同值分叉 ⇒ 产出不同（v_j ≠ v_1 ⇒ o_j ≠ o_1）")
    void differentValuesProduceDifferentBranches() {
        ResumeOutcome a = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-a", "branch-a", "approve"), INJECTIVE);
        ResumeOutcome b = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-b", "branch-b", "reject"), INJECTIVE);

        System.out.println("[真实输出] 分支 A 产出 = " + a.value() + " (kind=" + a.kind() + ")");
        System.out.println("[真实输出] 分支 B 产出 = " + b.value() + " (kind=" + b.kind() + ")");
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-fd", 0));

        assertEquals(ResumeKind.FORKED, a.kind());
        assertEquals(ResumeKind.FORKED, b.kind());
        assertEquals("decision:approve", a.value(), "产出必须等于 f(v)");
        assertEquals("decision:reject", b.value());
        assertNotEquals(a.value(), b.value(), "f 单射时 v 不同 ⇒ 产出不同");
        assertEquals(2L, gate.forkCount("wf-fd", 0));
    }

    @Test
    @DisplayName("同一分支重复投递 ⇒ 复用产出，不重复执行分支决策")
    void sameBranchIsDeterministic() {
        ResumeOutcome first = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-1", "branch-x", "approve"), INJECTIVE);
        ResumeOutcome second = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-2", "branch-x", "approve"), INJECTIVE);

        System.out.println("[真实输出] 第一次 = " + first.kind() + " / " + first.value());
        System.out.println("[真实输出] 第二次 = " + second.kind() + " / " + second.value());
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-fd", 0));

        assertEquals(ResumeKind.FORKED, first.kind());
        assertEquals(ResumeKind.REPLAYED, second.kind(), "同一分支重复投递应复用");
        assertEquals(first.value(), second.value());
        assertEquals(1L, gate.forkCount("wf-fd", 0), "不应开出第二个分支");
    }

    @Test
    @DisplayName("分叉不会消费中断：CO-c 的消费计数仍为 0")
    void forkingDoesNotConsumeInterrupt() {
        gate.resume(ResumeCommand.fork("wf-fd", 0, "r-a", "branch-a", "approve"), INJECTIVE);
        gate.resume(ResumeCommand.fork("wf-fd", 0, "r-b", "branch-b", "reject"), INJECTIVE);

        System.out.println("[真实输出] 分叉两次后，中断消费计数 = " + gate.consumedCount("wf-fd", 0));
        assertEquals(0L, gate.consumedCount("wf-fd", 0),
                "分叉是开新分支，不是消费中断");
    }

    @Test
    @DisplayName("先分叉再普通审批：分叉不占用消费名额")
    void forkThenConsumeBothSucceed() {
        ResumeOutcome forked = gate.resume(
                ResumeCommand.fork("wf-fd", 0, "r-fork", "branch-a", "approve"), INJECTIVE);
        ResumeOutcome consumed = gate.resume(
                ResumeCommand.approve("wf-fd", 0, "r-consume", "approve"), INJECTIVE);

        System.out.println("[真实输出] 分叉 = " + forked.kind() + "，随后普通审批 = " + consumed.kind());

        assertEquals(ResumeKind.FORKED, forked.kind());
        assertEquals(ResumeKind.CONSUMED, consumed.kind(), "中断仍处于 PARKED，应能被消费");
        assertEquals(1L, gate.consumedCount("wf-fd", 0));
    }
}
```

- [ ] **Step 2: 运行** — `mvn -q test -Dtest=ForkDeterminismTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 3: 提交** — `git commit -m "test(interrupt): fork determinism (FD property)"`

---

## Task 3: CO —— 消费至多一次 + 重复投递惰性

**Files:** Create `src/test/java/com/durable/contract/ConsumeOnceTest.java`

**论文原文**
> "**(CO-c)** An interrupt is consumed by at most one resume."
> "**(CO-e)** A resume without fork intent addressed to a completed run or an already-consumed interrupt — including byte-identical re-delivery of a prior resume — is inert with respect to effects."
> "a gate that serves its effect idempotently from the durable record can consume one human approval twice while the effect count stays at one, which leaves the **approval trail wrong** and the effect ledger right."

- [ ] **Step 1: 写测试**

```java
package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("契约 · CO：中断消费至多一次")
class ConsumeOnceTest {

    private ApprovalGate gate;

    private static final Function<String, String> F = v -> "decision:" + v;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
        gate.park("wf-co", 0, "{\"action\":\"deleteHost\"}");
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("CO-c：第二次审批是惰性的，不会再次消费中断")
    void secondResumeIsInert() {
        ResumeOutcome first = gate.resume(
                ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);
        ResumeOutcome second = gate.resume(
                ResumeCommand.approve("wf-co", 0, "r-2", "yes"), F);

        System.out.println("[真实输出] 第一次 = " + first.kind());
        System.out.println("[真实输出] 第二次 = " + second.kind());
        System.out.println("[真实输出] 中断消费计数 = " + gate.consumedCount("wf-co", 0));

        assertEquals(ResumeKind.CONSUMED, first.kind());
        assertEquals(ResumeKind.INERT, second.kind(), "CO-e：重复投递必须惰性");
        assertFalse(second.isEffectBearing(), "惰性结果不得携带副作用");
        assertEquals(1L, gate.consumedCount("wf-co", 0), "CO-c：至多被消费一次");
    }

    @Test
    @DisplayName("CO-c：连续 10 次审批，中断只被消费 1 次")
    void tenResumesConsumeOnce() {
        int consumed = 0;
        for (int i = 0; i < 10; i++) {
            ResumeOutcome o = gate.resume(
                    ResumeCommand.approve("wf-co", 0, "r-" + i, "yes"), F);
            if (o.kind() == ResumeKind.CONSUMED) {
                consumed++;
            }
        }

        System.out.println("[真实输出] 10 次投递中被消费的次数 = " + consumed);
        System.out.println("[真实输出] 中断消费计数 = " + gate.consumedCount("wf-co", 0));
        System.out.println("[真实输出] 投递总次数（含被拒） = " + gate.attemptCount("wf-co", 0));

        assertEquals(1, consumed, "CO-c：只有一次能真正消费");
        assertEquals(1L, gate.consumedCount("wf-co", 0));
        assertEquals(10L, gate.attemptCount("wf-co", 0),
                "被拒绝的投递也要留痕 —— 否则「审批轨迹」是错的");
    }

    @Test
    @DisplayName("CO-c：已消费的中断，再审一次仍然惰性（模拟 completed run 收到迟到审批）")
    void lateResumeOnConsumedInterruptIsInert() {
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);

        ResumeOutcome late = gate.resume(
                ResumeCommand.approve("wf-co", 0, "r-late", "yes"), F);

        System.out.println("[真实输出] 迟到审批结果 = " + late.kind());
        assertEquals(ResumeKind.INERT, late.kind());
        assertEquals(1L, gate.consumedCount("wf-co", 0));
    }

    @Test
    @DisplayName("⭐ 幂等 masking 陷阱：副作用计数为 1，但审批轨迹必须能看出被拒了几次")
    void inertResumeStillRecordedInApprovalTrail() {
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-1", "yes"), F);
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-2", "yes"), F);
        gate.resume(ResumeCommand.approve("wf-co", 0, "r-3", "no"), F);

        long consumed = gate.consumedCount("wf-co", 0);
        long attempts = gate.attemptCount("wf-co", 0);

        System.out.println("[真实输出] 消费次数 = " + consumed + "（副作用只会触发 1 次）");
        System.out.println("[真实输出] 投递次数 = " + attempts + "（含 2 次被惰性拒绝）");

        assertEquals(1L, consumed);
        assertEquals(3L, attempts, "被拒绝的投递如果没留痕，审计轨迹就是错的");
    }
}
```

- [ ] **Step 2: 运行** — `mvn -q test -Dtest=ConsumeOnceTest`
- [ ] **Step 3: 提交** — `git commit -m "test(interrupt): consume-once CO-c and CO-e"`

---

## Task 4: ⭐ 并发恢复 —— 论文实测主流框架全部失败的场景

**Files:** Create `src/test/java/com/durable/contract/ConcurrentResumeTest.java`

**论文原文**
> "Consume-once holds sequentially and fails under concurrent delivery: **k processes resuming one parked interrupt fire the gated effect k times**, saturation 1.0 in **36 of 40 cells**, and the failure crosses hosts."
> 修法：「an opt-in gate **claims consumption in the shared store**, serving one racer and refusing the rest **before any node executes**」

**这是整个项目最能打的一个测试。**

- [ ] **Step 1: 写测试**

```java
package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发投递下 CO-c 是否成立。
 *
 * 论文实测：LangGraph / CrewAI 等框架在这种情况下会让被门控的副作用执行 k 次
 * （40 格中 36 格饱和度为 1.0，且故障跨主机）。
 * 我们的做法是把「消费」变成一条原子的 SQL 条件更新，所以判断与执行不可分割。
 */
@DisplayName("契约 · CO-c：并发恢复下只消费一次")
class ConcurrentResumeTest {

    private static final int RACERS = 8;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("8 个并发 resume 抢同一个挂起中断：恰好 1 个成功，副作用恰好触发 1 次")
    void concurrentResumesYieldExactlyOneConsumption() throws Exception {
        gate.park("wf-race", 0, "{\"action\":\"deleteHost\"}");

        AtomicInteger effectsFired = new AtomicInteger();
        Function<String, String> effectBearingDecision = v -> {
            effectsFired.incrementAndGet();
            return "decision:" + v;
        };

        List<ResumeOutcome> outcomes = race(RACERS, i ->
                gate.resume(ResumeCommand.approve("wf-race", 0, "r-" + i, "yes"),
                        effectBearingDecision));

        long consumed = outcomes.stream()
                .filter(o -> o.kind() == ResumeKind.CONSUMED).count();
        long inert = outcomes.stream()
                .filter(o -> o.kind() == ResumeKind.INERT).count();

        System.out.println("[真实输出] 并发数 = " + RACERS);
        System.out.println("[真实输出] CONSUMED = " + consumed + "，INERT = " + inert);
        System.out.println("[真实输出] 中断被消费次数 = " + gate.consumedCount("wf-race", 0));
        System.out.println("[真实输出] 副作用触发次数 = " + effectsFired.get());

        assertEquals(1, consumed, "CO-c：只能有一个赢家");
        assertEquals(RACERS - 1, inert, "其余全部惰性拒绝");
        assertEquals(1L, gate.consumedCount("wf-race", 0));
        assertEquals(1, effectsFired.get(),
                "论文里这里是 k 次；我们这里是 1 次 —— 这就是本项目的核心卖点");
    }

    @Test
    @DisplayName("并发分叉：8 个不同 branchId 并发，各自产出独立")
    void concurrentForksProduceIndependentBranches() throws Exception {
        gate.park("wf-race-fork", 0, "{}");

        List<ResumeOutcome> outcomes = race(RACERS, i ->
                gate.resume(ResumeCommand.fork("wf-race-fork", 0, "r-" + i, "branch-" + i, "v" + i),
                        v -> "decision:" + v));

        long forked = outcomes.stream().filter(o -> o.kind() == ResumeKind.FORKED).count();

        System.out.println("[真实输出] 分叉成功数 = " + forked);
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-race-fork", 0));

        assertEquals(RACERS, forked, "不同分支判别符都应成功");
        assertEquals(RACERS, gate.forkCount("wf-race-fork", 0));
        assertEquals(0L, gate.consumedCount("wf-race-fork", 0), "分叉不消费中断");
    }

    @Test
    @DisplayName("并发重复投递同一个 branchId：只开出一个分支")
    void concurrentSameBranchYieldsOneBranch() throws Exception {
        gate.park("wf-race-same", 0, "{}");

        List<ResumeOutcome> outcomes = race(RACERS, i ->
                gate.resume(ResumeCommand.fork("wf-race-same", 0, "r-" + i, "same-branch", "yes"),
                        v -> "decision:" + v));

        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-race-same", 0));
        System.out.println("[真实输出] FORKED = " + outcomes.stream()
                .filter(o -> o.kind() == ResumeKind.FORKED).count()
                + "，REPLAYED = " + outcomes.stream()
                .filter(o -> o.kind() == ResumeKind.REPLAYED).count());

        assertEquals(1L, gate.forkCount("wf-race-same", 0), "同一分支只应存在一个");
    }

    /** 用 CountDownLatch 让所有线程尽可能同时发起，最大化竞争窗口。 */
    private static List<ResumeOutcome> race(int n, RaceAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ResumeOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int index = i;
            tasks.add(() -> {
                start.await();
                return action.run(index);
            });
        }
        List<Future<ResumeOutcome>> futures = new ArrayList<>();
        for (Callable<ResumeOutcome> t : tasks) {
            futures.add(pool.submit(t));
        }
        start.countDown();

        List<ResumeOutcome> results = new ArrayList<>();
        for (Future<ResumeOutcome> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return results;
    }

    @FunctionalInterface
    private interface RaceAction {
        ResumeOutcome run(int index) throws Exception;
    }
}
```

- [ ] **Step 2: 运行** — `mvn -q test -Dtest=ConcurrentResumeTest`

Expected（**请核对真实输出**）：
```
[真实输出] 并发数 = 8
[真实输出] CONSUMED = 1，INERT = 7
[真实输出] 中断被消费次数 = 1
[真实输出] 副作用触发次数 = 1
```

> ⚠️ 如果不通过，**不要改断言去迁就实现** —— 先搞清楚是 CAS 没生效还是测试有竞态。
> 这个数字（1 而不是 k）是本项目最重要的卖点之一。

- [ ] **Step 3: 提交** — `git commit -m "test(interrupt): concurrent consume-once, the cell where mainstream frameworks fail"`

---

## 完成标志

- [ ] `mvn -q test` 全绿
- [ ] `ConcurrentResumeTest` 打印「CONSUMED = 1，INERT = 7」与「副作用触发次数 = 1」
- [ ] `ForkDeterminismTest` 打印两个不同分支产出不同
- [ ] `ConsumeOnceTest` 打印「消费 = 1 / 投递 = 3」（验证审计轨迹正确）
- [ ] `docs/interview/step-3-interrupt-and-approval.md` 已产出

## 后续

- **计划 4**：完整故障矩阵（39 格思路）、演示脚本、复盘文档、README 收尾
