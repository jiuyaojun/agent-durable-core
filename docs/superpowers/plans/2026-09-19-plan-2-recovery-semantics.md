# 实施计划 2：恢复语义内核（PC / EO / CV / RD）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `DurableExecutor` 在崩溃后恢复时，**副作用至多触发一次**（EO），且恢复决策是持久状态的函数（RD）；同时修复计划 1 里被刻画测试固化下来的缺陷（副作用重复执行 2 次 → 1 次）。

**Architecture:** 在日志之外引入**效果账本（effect ledger）**。对非幂等副作用，采用 **claim-then-execute**：先在账本里抢占 `(workflow_id, step_no)` 这个位置（唯一索引保证只有一个赢家），**并在同一个数据库事务内执行副作用本身** —— 这就是真正的 exactly-once。恢复时每一步都先查账本，命中则直接复用记录的结果、不再触发副作用。

**Tech Stack:** 沿用计划 1（Java 17 / Maven / MySQL 8 / JUnit 5），不新增依赖。

## Global Constraints

- Java 17；Maven；不使用 Spring Boot；不使用 Docker
- 数据库：项目专属 MySQL 实例（端口 3307），`.\scripts\devdb-start.ps1` 启动
- 单文件 < 500 行，单函数 < 50 行
- 每个 Task 结束必须 `git commit`
- 测试必须展示真实执行输出
- **测试全程使用确定性假 LLM / 确定性动作，不调用真实模型** —— 论文也是用 LLM-free harness 测的这六条性质

### 本计划覆盖的性质（论文原义）

| 性质 | 本计划怎么验 |
|---|---|
| **PC** prefix continuation | 崩溃后恢复；前缀可以重跑，但**每个前缀副作用都从账本取**；恢复后状态 == 干净跑一遍的状态 |
| **EO** effect exactly-once | 副作用触发次数 **≤ 1**；特别覆盖「副作用已提交但确认丢失」窗口 |
| **CV** checkpoint validity | 写入 schema 非法的检查点必须**抛错且不落库** |
| **RD** recovery determinism | 同一份日志恢复两次，**决策完全一致** |

> FD / CO / FI 三条围绕**中断（人工审批）**，本计划不做，留到计划 3。

### 关键设计决策

**D7（新）. 效果账本与副作用必须同事务。**
非幂等副作用通过 `TransactionalEffect` 接口拿到 `Connection`，副作用本身（写 `host` 表）与账本写入在**同一个事务**里提交。这是唯一能做到真正 exactly-once 的方式。
**诚实的边界**：如果副作用在**外部系统**（调支付网关），同事务不可能，只能退化为 **at-least-once + 幂等键 + 对方幂等接收**。这条必须写进讲解文档，不能含糊。

**D8（新）. 恢复时重跑前缀代码，但副作用从账本取。**
论文明确允许（"prefix code may re-run... provided every prefix effect is served from the durable record"）。
好处：实现简单，不需要精确计算断点；且天然满足 PC。

**D9（新）. 恢复决策必须可导出、可比较。**
`RecoveryPlan` 是一个从日志纯计算出来的值对象。RD 的测试就是：同一份日志算两次 `RecoveryPlan`，断言相等。

---

## File Structure（本计划新增/修改）

```
src/main/
├── resources/
│   ├── schema.sql                      [改] 新增 effect_ledger / checkpoint / host 三张表
├── java/com/durable/
│   ├── db/Db.java                      [改] resetSchema 支持多表，按依赖顺序 DROP
│   ├── effect/
│   │   ├── EffectType.java             [新] NONE / IDEMPOTENT / NON_IDEMPOTENT
│   │   ├── TransactionalEffect.java    [新] 拿到 Connection 执行副作用
│   │   ├── EffectOutcome.java          [新] 已执行 / 已存在，附结果
│   │   └── EffectLedger.java           [新] claim-then-execute，同事务
│   ├── checkpoint/
│   │   ├── Checkpoint.java             [新] 不可变检查点，构造即校验
│   │   ├── InvalidCheckpointException.java [新]
│   │   └── MySqlCheckpointStore.java   [新] 校验后再写，非法则抛错不落库
│   ├── engine/
│   │   ├── Step.java                   [改] 增加 toolName + effectType
│   │   ├── RecoveryPlan.java           [新] 从日志纯计算出的恢复决策
│   │   ├── ExecutionResult.java        [新] 本次执行结果
│   │   └── DurableExecutor.java        [改] 引入账本与恢复
│   └── shell/
│       ├── Host.java                   [新] 被操作的资源
│       └── HostTools.java              [新] createHost / restartHost / listHosts

src/test/java/com/durable/
├── effect/EffectLedgerTest.java        [新]
├── checkpoint/CheckpointValidityTest.java [新]
├── engine/RecoveryDeterminismTest.java [新]
└── contract/
    ├── PrefixContinuationTest.java     [新]
    └── EffectExactlyOnceTest.java      [新]
```

---

## 前置验证

- [ ] **Step 0: 确认环境可用**

Run: `.\scripts\devdb-start.ps1` 然后 `mvn -q test`
Expected: `Tests run: 13, Failures: 0` / `BUILD SUCCESS`

---

## Task 1: 效果类型与效果账本

**Files:**
- Create: `src/main/java/com/durable/effect/EffectType.java`
- Create: `src/main/java/com/durable/effect/TransactionalEffect.java`
- Create: `src/main/java/com/durable/effect/EffectOutcome.java`
- Create: `src/main/java/com/durable/effect/EffectLedger.java`
- Modify: `src/main/resources/schema.sql`
- Modify: `src/main/java/com/durable/db/Db.java`
- Test: `src/test/java/com/durable/effect/EffectLedgerTest.java`

**Interfaces:**
- Produces:
  - `enum EffectType { NONE, IDEMPOTENT, NON_IDEMPOTENT }`
  - `interface TransactionalEffect { String run(Connection conn) throws SQLException; }`
  - `record EffectOutcome(boolean executed, String result)`
  - `class EffectLedger { EffectLedger(DataSource); EffectOutcome executeOnce(String workflowId, int stepNo, String toolName, TransactionalEffect effect); Optional<String> findResult(String workflowId, int stepNo); long countExecuted(String workflowId); }`

- [ ] **Step 1: 更新 `schema.sql`**

在原有 `journal` 表之后追加：

```sql
-- 效果账本：记录非幂等副作用是否已经触发过。
-- 主键 (workflow_id, step_no) 就是 claim 的抢占点：唯一索引保证同一位置只有一个赢家。
CREATE TABLE IF NOT EXISTS effect_ledger (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    tool_name   VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    result      JSON         NULL,
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 检查点：恢复用的状态快照。写入前必须通过 schema 校验（CV 性质）。
CREATE TABLE IF NOT EXISTS checkpoint (
    workflow_id    VARCHAR(64)  NOT NULL,
    version        INT          NOT NULL,
    frontier_step  INT          NOT NULL,
    schema_version VARCHAR(16)  NOT NULL,
    state          JSON         NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (workflow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 外壳：被 Agent 操作的资源。创建主机是花钱且不可逆的副作用。
CREATE TABLE IF NOT EXISTS host (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 更新 `Db.resetSchema()` 支持多表**

把 `JOURNAL_TABLE` 常量替换为按依赖顺序的列表：

```java
    private static final List<String> TABLES =
            List.of("journal", "effect_ledger", "checkpoint", "host");
```

并把方法体改为：

```java
    public static void resetSchema() {
        String ddl = readResource(SCHEMA_RESOURCE);
        try (Connection conn = dataSource().getConnection();
             Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            for (String raw : ddl.split(";")) {
                String sql = raw.trim();
                if (!sql.isEmpty()) {
                    st.execute(sql);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重建表结构失败，请确认 durable_test 库已创建且账号有权限", e);
        }
    }
```

- [ ] **Step 3: 写 `EffectType` / `TransactionalEffect` / `EffectOutcome`**

```java
package com.durable.effect;

/**
 * 一次工具调用对世界造成的影响类别。
 *
 * 区分它们不是洁癖：只读操作可以随便重放，非幂等操作重放一次就是真金白银。
 * 不区分，就只能把所有操作都当危险操作处理 —— 又慢又没必要。
 */
public enum EffectType {
    /** 只读，无副作用。可安全重放无数次。 */
    NONE,
    /** 有副作用但天然幂等（例如「把状态设为 X」）。重放安全。 */
    IDEMPOTENT,
    /** 有副作用且不可逆（例如「创建主机」「扣款」）。**必须走效果账本保护**。 */
    NON_IDEMPOTENT
}
```

```java
package com.durable.effect;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 一个会在数据库里产生副作用的操作。
 *
 * 注意签名：它拿到的是 {@link Connection}，而不是自己去开连接。
 * 这是刻意的 —— 副作用必须和效果账本的写入在【同一个事务】里，
 * 否则崩溃落在两者之间就会重复执行。真正的 exactly-once 靠的就是这一点。
 */
@FunctionalInterface
public interface TransactionalEffect {
    String run(Connection conn) throws SQLException;
}
```

```java
package com.durable.effect;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次受保护副作用执行的结果。
 *
 * @param executed true = 本次真的触发了副作用；false = 命中账本，副作用没有再次触发
 * @param result   副作用返回的结果（两种情况都有值）
 */
public record EffectOutcome(boolean executed, String result) {

    public EffectOutcome {
        Objects.requireNonNull(result, "result");
    }

    public static EffectOutcome executed(String result) {
        return new EffectOutcome(true, result);
    }

    public static EffectOutcome reused(String result) {
        return new EffectOutcome(false, result);
    }

    public Optional<String> resultIfReused() {
        return executed ? Optional.empty() : Optional.of(result);
    }
}
```

- [ ] **Step 4: 写失败测试 `EffectLedgerTest`**

```java
package com.durable.effect;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("效果账本")
class EffectLedgerTest {

    private EffectLedger ledger;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        ledger = new EffectLedger(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("第一次调用触发副作用，第二次命中账本不再触发")
    void executesOnceThenReuses() {
        AtomicInteger effects = new AtomicInteger();

        EffectOutcome first = ledger.executeOnce("wf-1", 0, "createHost", conn -> {
            effects.incrementAndGet();
            return "host-1";
        });
        EffectOutcome second = ledger.executeOnce("wf-1", 0, "createHost", conn -> {
            effects.incrementAndGet();
            return "host-2";
        });

        System.out.println("[真实输出] 第一次 executed=" + first.executed() + " result=" + first.result());
        System.out.println("[真实输出] 第二次 executed=" + second.executed() + " result=" + second.result());
        System.out.println("[真实输出] 副作用实际次数 = " + effects.get());

        assertTrue(first.executed());
        assertFalse(second.executed(), "第二次应命中账本");
        assertEquals("host-1", second.result(), "复用第一次的结果，而不是产生新结果");
        assertEquals(1, effects.get(), "副作用只应触发一次");
    }

    @Test
    @DisplayName("副作用抛异常时事务回滚，位置不被占用")
    void rollsBackOnFailure() {
        assertThrows(IllegalStateException.class, () ->
                ledger.executeOnce("wf-1", 0, "createHost", conn -> {
                    throw new IllegalStateException("模拟副作用失败");
                }));

        System.out.println("[真实输出] 失败后账本行数 = " + Db.countRows("SELECT COUNT(*) FROM effect_ledger"));
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM effect_ledger"),
                "回滚后位置应重新可用");

        // 位置仍可被正常占用
        EffectOutcome retry = ledger.executeOnce("wf-1", 0, "createHost", conn -> "host-ok");
        assertTrue(retry.executed());
    }

    @Test
    @DisplayName("不同工作流 / 不同步骤互不影响")
    void isolatesKeys() {
        ledger.executeOnce("wf-1", 0, "t", conn -> "a");
        ledger.executeOnce("wf-1", 1, "t", conn -> "b");
        ledger.executeOnce("wf-2", 0, "t", conn -> "c");

        System.out.println("[真实输出] 三个不同 key 后账本行数 = "
                + Db.countRows("SELECT COUNT(*) FROM effect_ledger"));
        assertEquals(3L, Db.countRows("SELECT COUNT(*) FROM effect_ledger"));
        assertTrue(ledger.findResult("wf-1", 0).isPresent());
        assertTrue(ledger.findResult("wf-9", 0).isEmpty());
    }
}
```

- [ ] **Step 5: 运行测试确认失败**

Run: `mvn -q test -Dtest=EffectLedgerTest`
Expected: 编译失败，`cannot find symbol: class EffectLedger`

- [ ] **Step 6: 实现 `EffectLedger`**

```java
package com.durable.effect;

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

/**
 * 效果账本：保证非幂等副作用【至多触发一次】。
 *
 * 做法是 claim-then-execute：
 *   1. 在事务里先 INSERT 一行 CLAIMED 抢占 (workflow_id, step_no)
 *      —— 主键唯一索引保证同一位置只有一个赢家
 *   2. 在【同一个事务、同一个连接】里执行副作用本身
 *   3. 把账本更新为 DONE 并写入结果，然后提交
 *
 * 任何一步失败都回滚，位置重新可用，不会留下"占了坑但没干活"的残局。
 *
 * 为什么必须同事务：如果先执行副作用再单独写账本，
 * 崩溃落在两者之间就会出现「副作用已发生、账本没记录」→ 恢复时重复执行。
 * 这正是计划 1 用刻画测试固化下来的那个缺陷。
 */
public class EffectLedger {

    private static final String SQL_CLAIM =
            "INSERT INTO effect_ledger (workflow_id, step_no, tool_name, status, result, created_at) "
                    + "VALUES (?, ?, ?, 'CLAIMED', NULL, ?)";

    private static final String SQL_COMPLETE =
            "UPDATE effect_ledger SET status = 'DONE', result = ? WHERE workflow_id = ? AND step_no = ?";

    private static final String SQL_FIND =
            "SELECT result FROM effect_ledger WHERE workflow_id = ? AND step_no = ? AND status = 'DONE'";

    private static final String SQL_COUNT =
            "SELECT COUNT(*) FROM effect_ledger WHERE workflow_id = ? AND status = 'DONE'";

    private final DataSource dataSource;

    public EffectLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * 至多执行一次。
     *
     * @return executed=true 表示本次真的触发了副作用；executed=false 表示命中账本、复用了旧结果
     */
    public EffectOutcome executeOnce(String workflowId, int stepNo, String toolName,
                                     TransactionalEffect effect) {
        Objects.requireNonNull(effect, "effect");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!claim(conn, workflowId, stepNo, toolName)) {
                    // 已被别人抢占或已完成
                    Optional<String> existing = findResult(workflowId, stepNo);
                    conn.rollback();
                    return EffectOutcome.reused(existing.orElseThrow(() ->
                            new IllegalStateException("位置已被占用但结果尚未落库，说明有并发事务正在进行: "
                                    + workflowId + "/" + stepNo)));
                }
                String result = effect.run(conn);
                complete(conn, workflowId, stepNo, result);
                conn.commit();
                return EffectOutcome.executed(result);
            } catch (RuntimeException | SQLException e) {
                conn.rollback();
                throw new IllegalStateException(
                        "受保护副作用执行失败并已回滚: " + workflowId + "/" + stepNo, e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("获取连接失败", e);
        }
    }

    /** 返回 false 表示位置已被占用（重复键）。 */
    private boolean claim(Connection conn, String workflowId, int stepNo, String toolName)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_CLAIM)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            ps.setString(3, toolName);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw e;
        }
    }

    private void complete(Connection conn, String workflowId, int stepNo, String result)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_COMPLETE)) {
            ps.setString(1, Json.write(result));
            ps.setString(2, workflowId);
            ps.setInt(3, stepNo);
            ps.executeUpdate();
        }
    }

    public Optional<String> findResult(String workflowId, int stepNo) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND)) {
            ps.setString(1, workflowId);
            ps.setInt(2, stepNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Json.read(rs.getString(1), String.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取账本失败: " + workflowId + "/" + stepNo, e);
        }
    }

    /** 某工作流已真正触发的副作用次数。EO 性质的核心观测指标。 */
    public long countExecuted(String workflowId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_COUNT)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计账本失败: " + workflowId, e);
        }
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q test -Dtest=EffectLedgerTest`
Expected:
```
[真实输出] 第一次 executed=true result=host-1
[真实输出] 第二次 executed=false result=host-1
[真实输出] 副作用实际次数 = 1
[真实输出] 失败后账本行数 = 0
[真实输出] 三个不同 key 后账本行数 = 3
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat(effect): effect ledger with claim-then-execute in one transaction"
```

---

## Task 2: 检查点与 CV 性质

**Files:**
- Create: `src/main/java/com/durable/checkpoint/Checkpoint.java`
- Create: `src/main/java/com/durable/checkpoint/InvalidCheckpointException.java`
- Create: `src/main/java/com/durable/checkpoint/MySqlCheckpointStore.java`
- Test: `src/test/java/com/durable/checkpoint/CheckpointValidityTest.java`

**Interfaces:**
- Produces:
  - `record Checkpoint(String workflowId, int version, int frontierStep, String schemaVersion, List<String> results, Instant createdAt)`
  - `class InvalidCheckpointException extends RuntimeException`
  - `class MySqlCheckpointStore { MySqlCheckpointStore(DataSource); void save(Checkpoint); Optional<Checkpoint> latest(String workflowId); List<Checkpoint> loadAll(String workflowId); }`

**CV 性质的含义（论文原文）**：
> "a write that would persist schema-invalid state is **rejected with an error, not stored**"

所以校验必须发生在**写之前**，且失败时**表里不能留下任何东西**。

- [ ] **Step 1: `InvalidCheckpointException`**

```java
package com.durable.checkpoint;

/** 试图持久化 schema 非法的检查点时抛出。此时不得有任何数据落库。 */
public class InvalidCheckpointException extends RuntimeException {
    public InvalidCheckpointException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: `Checkpoint`（构造即校验）**

```java
package com.durable.checkpoint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次执行状态的检查点（不可变）。
 *
 * 不变量（构造时校验，违反即抛 {@link InvalidCheckpointException}）：
 *   1. workflowId 非空白
 *   2. version >= 0
 *   3. frontierStep >= 0
 *   4. schemaVersion 非空白
 *   5. results 非空，且 size() == frontierStep + 1   ← 已完成 N 步就应恰好有 N 个结果
 *
 * 第 5 条是核心不变量：它把「检查点说自己走到第几步」和「检查点实际记了几个结果」
 * 绑在一起。允许两者不一致，就是 LangGraph 那个「静默持久化非法状态」的漏洞。
 */
public record Checkpoint(
        String workflowId,
        int version,
        int frontierStep,
        String schemaVersion,
        List<String> results,
        Instant createdAt
) {
    public static final String CURRENT_SCHEMA = "v1";

    public Checkpoint {
        requireNonBlank(workflowId, "workflowId");
        requireNonBlank(schemaVersion, "schemaVersion");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version < 0) {
            throw new InvalidCheckpointException("version 不能为负数: " + version);
        }
        if (frontierStep < 0) {
            throw new InvalidCheckpointException("frontierStep 不能为负数: " + frontierStep);
        }
        if (results.isEmpty()) {
            throw new InvalidCheckpointException("results 不能为空");
        }
        if (results.size() != frontierStep + 1) {
            throw new InvalidCheckpointException(
                    "results 数量与 frontierStep 不一致: frontierStep=" + frontierStep
                            + " 期望 results.size()=" + (frontierStep + 1)
                            + " 实际=" + results.size());
        }
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                throw new InvalidCheckpointException("results 第 " + i + " 项为 null");
            }
        }
        results = List.copyOf(results);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidCheckpointException(field + " 不能为空白");
        }
    }

    public static Checkpoint of(String workflowId, int version, List<String> results) {
        return new Checkpoint(workflowId, version, results.size() - 1,
                CURRENT_SCHEMA, results, Instant.now());
    }
}
```

- [ ] **Step 3: 写失败测试 `CheckpointValidityTest`**

```java
package com.durable.checkpoint;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CV：检查点合法性")
class CheckpointValidityTest {

    private MySqlCheckpointStore store;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        store = new MySqlCheckpointStore(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("合法检查点可以往返")
    void savesAndLoadsValidCheckpoint() {
        store.save(Checkpoint.of("wf-1", 0, List.of("r0", "r1")));

        Checkpoint loaded = store.latest("wf-1").orElseThrow();
        System.out.println("[真实输出] frontierStep=" + loaded.frontierStep()
                + " results=" + loaded.results() + " schema=" + loaded.schemaVersion());

        assertEquals(1, loaded.frontierStep());
        assertEquals(List.of("r0", "r1"), loaded.results());
        assertEquals(Checkpoint.CURRENT_SCHEMA, loaded.schemaVersion());
    }

    @Test
    @DisplayName("CV：frontierStep 与 results 数量不一致时被拒绝，且不落库")
    void rejectsMismatchedFrontierStep() {
        Checkpoint invalid = new Checkpoint("wf-1", 0, 5, Checkpoint.CURRENT_SCHEMA,
                List.of("only-one"), Instant.now());

        InvalidCheckpointException ex = assertThrows(InvalidCheckpointException.class,
                () -> store.save(invalid));
        System.out.println("[真实输出] 拒绝原因: " + ex.getMessage());
        System.out.println("[真实输出] 拒绝后 checkpoint 行数 = "
                + Db.countRows("SELECT COUNT(*) FROM checkpoint"));

        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM checkpoint"),
                "非法检查点不得落库（LangGraph 1.2.9 就是在这里静默通过的）");
    }

    @Test
    @DisplayName("CV：results 含 null 或为空时被拒绝")
    void rejectsNullOrEmptyResults() {
        assertThrows(InvalidCheckpointException.class, () ->
                new Checkpoint("wf-1", 0, 1, Checkpoint.CURRENT_SCHEMA,
                        Arrays.asList("a", null), Instant.now()));
        assertThrows(InvalidCheckpointException.class, () ->
                new Checkpoint("wf-1", 0, 0, Checkpoint.CURRENT_SCHEMA, List.of(), Instant.now()));

        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM checkpoint"));
    }

    @Test
    @DisplayName("latest 返回版本号最大的检查点")
    void latestReturnsHighestVersion() {
        store.save(Checkpoint.of("wf-1", 0, List.of("a")));
        store.save(Checkpoint.of("wf-1", 1, List.of("a", "b")));
        store.save(Checkpoint.of("wf-1", 2, List.of("a", "b", "c")));

        Checkpoint latest = store.latest("wf-1").orElseThrow();
        System.out.println("[真实输出] 最新版本 = " + latest.version()
                + " frontierStep = " + latest.frontierStep());

        assertEquals(2, latest.version());
        assertEquals(2, latest.frontierStep());
        assertEquals(3, store.loadAll("wf-1").size());
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q test -Dtest=CheckpointValidityTest`
Expected: 编译失败，`cannot find symbol: class MySqlCheckpointStore`

- [ ] **Step 5: 实现 `MySqlCheckpointStore`**

```java
package com.durable.checkpoint;

import com.durable.json.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 检查点的 MySQL 存储。
 *
 * CV 性质要求：非法状态必须【写之前】就被拒绝，不得先写进去再回滚或忽略。
 * 因此 save() 的第一件事是重新构造一次 Checkpoint —— record 的紧凑构造器会做校验，
 * 校验不过直接抛异常，连接都还没打开。
 */
public class MySqlCheckpointStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private static final String SQL_INSERT =
            "INSERT INTO checkpoint (workflow_id, version, frontier_step, schema_version, state, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SQL_LATEST =
            "SELECT workflow_id, version, frontier_step, schema_version, state, created_at "
                    + "FROM checkpoint WHERE workflow_id = ? ORDER BY version DESC LIMIT 1";

    private static final String SQL_ALL =
            "SELECT workflow_id, version, frontier_step, schema_version, state, created_at "
                    + "FROM checkpoint WHERE workflow_id = ? ORDER BY version ASC";

    private final DataSource dataSource;

    public MySqlCheckpointStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public void save(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        // 再校验一次：即使调用方绕过 record 构造器，这里也拦得住
        validate(checkpoint);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT)) {
            ps.setString(1, checkpoint.workflowId());
            ps.setInt(2, checkpoint.version());
            ps.setInt(3, checkpoint.frontierStep());
            ps.setString(4, checkpoint.schemaVersion());
            ps.setString(5, Json.write(checkpoint.results()));
            ps.setTimestamp(6, Timestamp.from(checkpoint.createdAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入检查点失败: " + checkpoint.workflowId(), e);
        }
    }

    private static void validate(Checkpoint checkpoint) {
        if (checkpoint.results().size() != checkpoint.frontierStep() + 1) {
            throw new InvalidCheckpointException(
                    "拒绝持久化非法检查点: frontierStep=" + checkpoint.frontierStep()
                            + " 与 results.size()=" + checkpoint.results().size() + " 不一致");
        }
        if (!Checkpoint.CURRENT_SCHEMA.equals(checkpoint.schemaVersion())) {
            throw new InvalidCheckpointException(
                    "不支持的 schema 版本: " + checkpoint.schemaVersion());
        }
    }

    public Optional<Checkpoint> latest(String workflowId) {
        List<Checkpoint> all = query(SQL_LATEST, workflowId);
        return all.stream().findFirst();
    }

    public List<Checkpoint> loadAll(String workflowId) {
        return query(SQL_ALL, workflowId);
    }

    private List<Checkpoint> query(String sql, String workflowId) {
        List<Checkpoint> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Checkpoint(
                            rs.getString("workflow_id"),
                            rs.getInt("version"),
                            rs.getInt("frontier_step"),
                            rs.getString("schema_version"),
                            Json.read(rs.getString("state"), STRING_LIST),
                            rs.getTimestamp("created_at").toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取检查点失败: " + workflowId, e);
        }
        return result;
    }
}
```

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q test -Dtest=CheckpointValidityTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat(checkpoint): schema-validated checkpoints (CV property)"
```

---

## Task 3: 带恢复的执行器（PC / EO）

**Files:**
- Modify: `src/main/java/com/durable/engine/Step.java`
- Create: `src/main/java/com/durable/engine/RecoveryPlan.java`
- Create: `src/main/java/com/durable/engine/ExecutionResult.java`
- Modify: `src/main/java/com/durable/engine/DurableExecutor.java`
- Create: `src/main/java/com/durable/shell/Host.java`
- Create: `src/main/java/com/durable/shell/HostTools.java`
- Test: `src/test/java/com/durable/contract/EffectExactlyOnceTest.java`
- Test: `src/test/java/com/durable/contract/PrefixContinuationTest.java`
- Modify: `src/test/java/com/durable/engine/DurableExecutorCrashTest.java`（把刻画断言从 2 改成 1）

**Interfaces:**
- Produces:
  - `record Step(String name, String toolName, EffectType effectType, TransactionalEffect action)`
  - `record RecoveryPlan(int resumeFromStep, List<Integer> replayPrefixSteps)` + `static RecoveryPlan from(JournalStore, String workflowId, int totalSteps)`
  - `record ExecutionResult(int stepsExecuted, int effectsFired, boolean reusedFromLedger)`
  - `class DurableExecutor { DurableExecutor(JournalStore, EffectLedger, CrashInjector); ExecutionResult run(String workflowId, List<Step>); RecoveryPlan plan(String workflowId, int totalSteps); }`

- [ ] **Step 1: 改 `Step` 携带工具名与效果类型**

```java
package com.durable.engine;

import com.durable.effect.EffectType;
import com.durable.effect.TransactionalEffect;

import java.util.Objects;

/**
 * 一个可执行的步骤。
 *
 * toolName + effectType 让执行器知道这一步「危不危险」：
 * 只读的直接跑，非幂等的必须过效果账本。
 */
public record Step(String name, String toolName, EffectType effectType, TransactionalEffect action) {

    public Step {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(effectType, "effectType");
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空白");
        }
    }

    public boolean needsLedger() {
        return effectType == EffectType.NON_IDEMPOTENT;
    }
}
```

- [ ] **Step 2: `RecoveryPlan`（RD 的核心：纯函数）**

```java
package com.durable.engine;

import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 恢复决策：从持久日志【纯计算】出来的执行计划。
 *
 * RD（Recovery determinism）性质要求：同一份日志必须得出同一个计划。
 * 所以这个类没有任何随机、时间、外部输入 —— 只有一个把 List&lt;JournalEntry&gt; 映射成计划的纯函数。
 *
 * PC（prefix continuation）允许前缀代码重跑，只要副作用从持久记录取。
 * 因此这里的策略是：**已经记录过结果的步骤仍然重跑一遍代码**，
 * 但它们的副作用会由效果账本拦住。resumeFromStep 只用来优化，不承担正确性。
 */
public record RecoveryPlan(int resumeFromStep, List<Integer> completedSteps) {

    public RecoveryPlan {
        Objects.requireNonNull(completedSteps, "completedSteps");
        completedSteps = List.copyOf(completedSteps);
    }

    /**
     * 从日志计算恢复计划。这是纯函数：相同日志 => 相同计划。
     *
     * @param totalSteps 本次要跑的步骤总数
     */
    public static RecoveryPlan from(JournalStore journal, String workflowId, int totalSteps) {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(workflowId, "workflowId");

        List<JournalEntry> entries = journal.load(workflowId);
        List<Integer> completed = new ArrayList<>();
        int frontier = -1;
        for (JournalEntry entry : entries) {
            if (entry.type() == JournalEntryType.STEP_RESULT) {
                completed.add(entry.stepNo());
                frontier = Math.max(frontier, entry.stepNo());
            }
        }
        // 全部完成则不用再跑；否则从 frontier 的下一个步骤继续
        int resumeFrom = (frontier + 1 >= totalSteps) ? totalSteps : frontier + 1;
        return new RecoveryPlan(resumeFrom, completed);
    }

    public boolean isCompleted(int stepNo) {
        return completedSteps.contains(stepNo);
    }
}
```

- [ ] **Step 3: `ExecutionResult`**

```java
package com.durable.engine;

/**
 * 一次 run() 的结果统计。EO 性质的观测点就是 effectsFired。
 *
 * @param stepsExecuted  实际执行的步骤数
 * @param effectsFired   本次真正触发的非幂等副作用次数
 * @param effectsReused  命中效果账本、复用了旧结果的次数
 */
public record ExecutionResult(int stepsExecuted, int effectsFired, int effectsReused) {
}
```

- [ ] **Step 4: 重写 `DurableExecutor`**

```java
package com.durable.engine;

import com.durable.effect.EffectLedger;
import com.durable.effect.EffectOutcome;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import com.durable.json.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 带恢复语义的步骤执行器。
 *
 * 执行每一步的顺序（顺序本身就是设计，几个崩溃点的含义由此确定）：
 *
 *   1. check(BEFORE_STEP, i)                      — 崩溃则副作用未发生
 *   2. 若第 i 步已有 STEP_RESULT 记录 → 重跑代码但副作用走账本（PC 允许）
 *   3. 非幂等步骤：账本 claim + 副作用 + 结果，同一事务（EO 的关键）
 *      其余步骤：直接执行
 *   4. check(AFTER_EXECUTE_BEFORE_JOURNAL, i)     — 副作用已发生、日志未写
 *   5. 写 STEP_RESULT
 *   6. check(AFTER_JOURNAL, i)
 *
 * 第 4 步是计划 1 复现出来的缺陷窗口。因为第 3 步已经把副作用保护起来了，
 * 恢复时第 3 步会命中账本、不再触发，所以这个窗口现在是安全的。
 */
public class DurableExecutor {

    private final JournalStore journal;
    private final EffectLedger ledger;
    private final CrashInjector crashInjector;

    public DurableExecutor(JournalStore journal, EffectLedger ledger, CrashInjector crashInjector) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.crashInjector = Objects.requireNonNull(crashInjector, "crashInjector");
    }

    /** 只读地计算恢复计划，不产生任何副作用。用于 RD 性质的验证。 */
    public RecoveryPlan plan(String workflowId, int totalSteps) {
        return RecoveryPlan.from(journal, workflowId, totalSteps);
    }

    public ExecutionResult run(String workflowId, List<Step> steps) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(steps, "steps");

        int executed = 0;
        int fired = 0;
        int reused = 0;

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);

            crashInjector.check(CrashPoint.BEFORE_STEP, i);

            EffectOutcome outcome = executeGuarded(workflowId, i, step);
            if (outcome.executed()) {
                fired++;
            } else {
                reused++;
            }
            executed++;

            crashInjector.check(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, i);
            journal.append(JournalEntry.of(
                    workflowId, i, JournalEntryType.STEP_RESULT, payloadOf(step, outcome.result())));
            crashInjector.check(CrashPoint.AFTER_JOURNAL, i);
        }
        return new ExecutionResult(executed, fired, reused);
    }

    /** 非幂等步骤走账本；其余直接执行。 */
    private EffectOutcome executeGuarded(String workflowId, int stepNo, Step step) {
        if (step.needsLedger()) {
            return ledger.executeOnce(workflowId, stepNo, step.toolName(), step.action());
        }
        try {
            return EffectOutcome.executed(step.action().run(null));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("步骤执行失败: " + step.name(), e);
        }
    }

    private static String payloadOf(Step step, String value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step.name());
        payload.put("tool", step.toolName());
        payload.put("effectType", step.effectType().name());
        payload.put("value", value);
        return Json.write(payload);
    }
}
```

- [ ] **Step 5: 新增外壳 `Host` / `HostTools`**

```java
package com.durable.shell;

/** 被 Agent 操作的资源。创建主机是花钱且不可逆的副作用。 */
public record Host(String id, String name, String status, String createdBy) {
}
```

```java
package com.durable.shell;

import com.durable.effect.TransactionalEffect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 外壳：一组有真实后果的资源操作。
 *
 * 这里存在的意义只是给持久化内核提供一个「副作用说真话」的试验田 ——
 * 创建主机花钱、不可逆，正好用来验证 exactly-once。
 */
public class HostTools {

    private final DataSource dataSource;

    public HostTools(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 创建主机。**非幂等**：多跑一次就多花一份钱。
     * 注意它用的是传进来的 conn —— 副作用必须和账本写入同事务。
     */
    public TransactionalEffect createHost(String hostId, String name, String createdBy) {
        return conn -> {
            insertHost(conn, hostId, name, createdBy);
            return hostId;
        };
    }

    private static void insertHost(Connection conn, String id, String name, String createdBy)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO host (id, name, status, created_by) VALUES (?, ?, 'RUNNING', ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, createdBy);
            ps.executeUpdate();
        }
    }

    /** 只读：列出主机。可以安全重放任意多次。 */
    public TransactionalEffect listHosts() {
        return conn -> {
            StringBuilder sb = new StringBuilder("[");
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM host ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(rs.getString(1)).append('"');
                    first = false;
                }
            }
            return sb.append(']').toString();
        };
    }

    public long hostCount() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM host");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("统计主机失败", e);
        }
    }
}
```

- [ ] **Step 6: 写 EO 契约测试**

```java
package com.durable.contract;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.effect.EffectType;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EO：副作用至多触发一次")
class EffectExactlyOnceTest {

    private JournalStore journal;
    private EffectLedger ledger;
    private HostTools hostTools;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    private List<Step> steps() {
        return List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT,
                        hostTools.createHost("host-1", "web-1", "agent")),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()));
    }

    @Test
    @DisplayName("无故障：主机创建一次")
    void happyPathCreatesExactlyOneHost() {
        DurableExecutor executor = new DurableExecutor(journal, ledger, new CrashInjector());
        var result = executor.run("wf-eo-happy", steps());

        System.out.println("[真实输出] effectsFired=" + result.effectsFired()
                + " effectsReused=" + result.effectsReused());
        System.out.println("[真实输出] host 行数 = " + hostTools.hostCount());

        assertEquals(1, result.effectsFired());
        assertEquals(1L, hostTools.hostCount());
    }

    @Test
    @DisplayName("【计划1 缺陷已修复】副作用与日志之间崩溃，恢复后主机仍然只有一台")
    void crashBetweenEffectAndJournalKeepsEffectAtMostOnce() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);

        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-eo-crash", steps()));

        System.out.println("[真实输出] 崩溃后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 崩溃后账本已执行数 = " + ledger.countExecuted("wf-eo-crash"));
        System.out.println("[真实输出] 崩溃后日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1L, hostTools.hostCount(), "第一次副作用已发生");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal"), "日志仍为空（这就是崩溃窗口）");

        // 模拟重启
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-eo-crash", steps());

        System.out.println("[真实输出] 恢复后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 恢复后账本已执行数 = " + ledger.countExecuted("wf-eo-crash"));

        assertEquals(1L, hostTools.hostCount(), "EO：主机必须恰好一台（计划 1 时这里是 2）");
        assertEquals(1L, ledger.countExecuted("wf-eo-crash"), "账本只应有一条 DONE");
        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal"), "恢复后日志补齐两步");
    }

    @Test
    @DisplayName("反复崩溃多次恢复，副作用依然只触发一次")
    void repeatedCrashesStillFireExactlyOnce() {
        int[] crashAt = {0, 0, 0};
        for (int round = 0; round < crashAt.length; round++) {
            CrashInjector crashing = new CrashInjector();
            crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);
            assertThrows(SimulatedCrash.class,
                    () -> new DurableExecutor(journal, ledger, crashing).run("wf-eo-multi", steps()));
        }

        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-eo-multi", steps());

        System.out.println("[真实输出] 连续 3 次崩溃后恢复，host 行数 = " + hostTools.hostCount());
        assertEquals(1L, hostTools.hostCount(), "无论崩几次，副作用只触发一次");
    }
}
```

- [ ] **Step 7: 写 PC 契约测试**

```java
package com.durable.contract;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PC：前缀续跑")
class PrefixContinuationTest {

    private JournalStore journal;
    private EffectLedger ledger;
    private HostTools hostTools;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    private List<Step> steps() {
        return List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT,
                        hostTools.createHost("host-1", "web-1", "agent")),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()));
    }

    @Test
    @DisplayName("恢复后的最终状态与「干净跑一遍」完全一致")
    void recoveredStateEqualsCleanRunState() {
        // 干净跑一遍作为基线
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-baseline", steps());
        List<String> baseline = resultValues("wf-baseline");

        // 在第二步写入日志之后崩溃
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-recovered", steps()));
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-recovered", steps());
        List<String> recovered = resultValues("wf-recovered");

        System.out.println("[真实输出] 基线状态   = " + baseline);
        System.out.println("[真实输出] 恢复后状态 = " + recovered);

        assertEquals(baseline, recovered, "PC：恢复后的可观测状态必须等于干净跑一遍的状态");
    }

    @Test
    @DisplayName("前缀代码可以重跑，但前缀副作用必须从账本取")
    void prefixEffectIsServedFromLedgerOnResume() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-pc", steps()));

        var resumed = new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-pc", steps());

        System.out.println("[真实输出] 恢复时 effectsReused = " + resumed.effectsReused());
        System.out.println("[真实输出] 恢复时 effectsFired  = " + resumed.effectsFired());
        System.out.println("[真实输出] host 行数 = " + hostTools.hostCount());

        assertEquals(1, resumed.effectsReused(), "第 0 步重跑了代码，但副作用命中账本复用");
        assertEquals(0, resumed.effectsFired(), "恢复时不应再触发任何新副作用");
        assertEquals(1L, hostTools.hostCount());
    }

    /** 从日志里按 stepNo 顺序取出每步的 value，作为「可观测状态」。 */
    private List<String> resultValues(String workflowId) {
        List<JournalEntry> entries = journal.load(workflowId);
        entries.sort((a, b) -> Integer.compare(a.stepNo(), b.stepNo()));
        List<String> values = new ArrayList<>();
        for (JournalEntry e : entries) {
            if (e.type() == JournalEntryType.STEP_RESULT) {
                values.add(e.payload());
            }
        }
        return values;
    }
}
```

- [ ] **Step 8: 把计划 1 的刻画断言改成修复后的期望值**

编辑 `src/test/java/com/durable/engine/DurableExecutorCrashTest.java`：这个测试用的构造器已经变了，**整文件重写**为使用新签名，并把断言从 2 改成 1：

```java
package com.durable.engine;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("执行器的崩溃行为（修复后）")
class DurableExecutorCrashTest {

    private JournalStore journal;
    private EffectLedger ledger;
    private HostTools hostTools;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    private List<Step> steps(AtomicInteger counter) {
        return List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT, conn -> {
                    counter.incrementAndGet();
                    return hostTools.createHost("host-1", "web-1", "agent").run(conn);
                }),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()));
    }

    @Test
    @DisplayName("无故障时每个步骤都写一条日志")
    void journalsEveryStepOnHappyPath() {
        AtomicInteger effects = new AtomicInteger();
        int executed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-happy", steps(effects)).stepsExecuted();

        System.out.println("[真实输出] 实际执行步骤数 = " + executed);
        System.out.println("[真实输出] 副作用次数 = " + effects.get());
        System.out.println("[真实输出] 日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(2, executed);
        assertEquals(1, effects.get());
        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal"));
    }

    /**
     * 计划 1 的刻画测试：当时断言副作用次数 == 2（缺陷）。
     * 引入效果账本后改为 == 1 —— 这就是修复的量化证据。
     */
    @Test
    @DisplayName("【已修复】副作用与日志之间崩溃，重跑不再重复执行副作用")
    void crashBetweenEffectAndJournalNoLongerDuplicates() {
        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = steps(effects);

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-crash", steps));

        System.out.println("[真实输出] 崩溃后副作用次数 = " + effects.get());
        System.out.println("[真实输出] 崩溃后日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1, effects.get());
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal"));

        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-crash", steps);

        System.out.println("[真实输出] 重启后副作用总次数 = " + effects.get());
        System.out.println("[真实输出] 重启后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 重启后日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1, effects.get(), "修复后：副作用只执行了一次（计划 1 时是 2）");
        assertEquals(1L, hostTools.hostCount());
    }
}
```

- [ ] **Step 9: 运行全部测试**

Run: `mvn -q test`
Expected: 全部通过。关键输出：
```
[真实输出] 崩溃后 host 行数 = 1
[真实输出] 恢复后 host 行数 = 1
[真实输出] 重启后副作用总次数 = 1
```

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat(engine): crash-resilient executor with effect ledger (PC + EO)

计划 1 的刻画测试断言从 2 改为 1 —— 副作用不再重复执行。"
```

---

## Task 4: RD 性质 —— 恢复决策确定性

**Files:**
- Test: `src/test/java/com/durable/engine/RecoveryDeterminismTest.java`

**Interfaces:**
- Consumes: `DurableExecutor.plan(String, int)`、`RecoveryPlan`

- [ ] **Step 1: 写测试**

```java
package com.durable.engine;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RD：恢复决策确定性")
class RecoveryDeterminismTest {

    private JournalStore journal;
    private EffectLedger ledger;
    private HostTools hostTools;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    private List<Step> steps() {
        return List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT,
                        hostTools.createHost("host-1", "web-1", "agent")),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()));
    }

    @Test
    @DisplayName("同一份日志算两次，恢复计划完全一致")
    void planIsPureFunctionOfJournal() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-rd", steps()));

        DurableExecutor executor = new DurableExecutor(journal, ledger, new CrashInjector());
        RecoveryPlan first = executor.plan("wf-rd", 2);
        RecoveryPlan second = executor.plan("wf-rd", 2);

        System.out.println("[真实输出] 第一次计划 = resumeFrom " + first.resumeFromStep()
                + ", completed " + first.completedSteps());
        System.out.println("[真实输出] 第二次计划 = resumeFrom " + second.resumeFromStep()
                + ", completed " + second.completedSteps());

        assertEquals(first, second, "RD：相同日志必须得出相同决策");
        assertEquals(List.of(0, 1), first.completedSteps());
        assertEquals(2, first.resumeFromStep(), "两步都完成了，无需再跑");
    }

    @Test
    @DisplayName("日志为空时计划为从头开始")
    void emptyJournalPlansFromZero() {
        RecoveryPlan plan = new DurableExecutor(journal, ledger, new CrashInjector()).plan("wf-empty", 3);

        System.out.println("[真实输出] 空日志计划 = resumeFrom " + plan.resumeFromStep()
                + ", completed " + plan.completedSteps());

        assertEquals(0, plan.resumeFromStep());
        assertTrue(plan.completedSteps().isEmpty());
    }

    @Test
    @DisplayName("部分完成时从断点续跑")
    void partialJournalResumesFromNextStep() {
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-partial",
                List.of(steps().get(1)));

        RecoveryPlan plan = new DurableExecutor(journal, ledger, new CrashInjector())
                .plan("wf-partial", 2);

        System.out.println("[真实输出] 部分完成计划 = resumeFrom " + plan.resumeFromStep()
                + ", completed " + plan.completedSteps());

        assertEquals(1, plan.resumeFromStep());
        assertEquals(List.of(0), plan.completedSteps());
    }
}
```

- [ ] **Step 2: 运行**

Run: `mvn -q test -Dtest=RecoveryDeterminismTest`
Expected: `Tests run: 3, Failures: 0` / `BUILD SUCCESS`

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "test(engine): recovery determinism (RD property)"
```

---

## 完成标志

- [ ] `mvn -q test` 全绿
- [ ] `EffectExactlyOnceTest` 打印「恢复后 host 行数 = 1」
- [ ] `DurableExecutorCrashTest` 打印「重启后副作用总次数 = 1」（计划 1 时是 2）
- [ ] `PrefixContinuationTest` 打印恢复状态 == 基线状态
- [ ] `RecoveryDeterminismTest` 打印两次计划一致
- [ ] `docs/interview/step-2-recovery-semantics.md` 已产出

## 后续

- **计划 3**：中断与审批闸门 —— FD / CO-c / CO-e / FI 四条性质（含并发）
- **计划 4**：故障矩阵、演示脚本、复盘文档
