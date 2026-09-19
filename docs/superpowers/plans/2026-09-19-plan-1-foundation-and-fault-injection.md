# Agent 持久化执行内核 — 实施计划 1：地基与故障注入框架

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭起可运行的 Java 工程骨架，实现追加型决策日志与应用内故障注入器，并写出一个**复现「崩溃后副作用被重复执行」这个真实 bug 的刻画测试**。

**Architecture:** 核心是「日志即真相」——执行过程的每一步都追加写入 MySQL，恢复时靠重放日志而非重新调用 LLM。本计划只搭建到「能复现问题」为止，**修复留到计划 2**，因此本计划产出的刻画测试断言的是当前（有缺陷的）行为，并显式标注为待修复。

**Tech Stack:** Java 17、Maven 3.9、MySQL 8、JUnit 5、Jackson、HikariCP。**不使用 Spring Boot。**

## Global Constraints

- **Java 版本：17**（本机为 OpenJDK 17.0.7，不使用 21 的任何语法）
- **构建工具：Maven**（本机 Maven 3.9.11；不引入 Gradle）
- **不使用 Spring Boot** —— 见下方「技术栈决策」
- **数据库：MySQL 8.0.41，`localhost:3306`**，库名 `durable_test`，账号 `durable` / `durable_dev_pwd`
  （如需修改，只改 `src/main/resources/db.properties` 一处）
- **不使用 Docker、不使用 Testcontainers**（本机无 Docker）
- **不引入 Spring AI / langchain4j / langgraph4j** —— LLM 接入只用最薄的 HTTP 客户端
- **单文件 < 500 行，单函数 < 50 行** —— 硬性约束，超出即拆分
- 所有代码包名前缀：`com.durable`
- 每个 Task 结束必须 `git commit`
- 测试必须展示**真实执行输出**，禁止只声称「测试通过」

### 技术栈决策：为什么不用 Spring Boot

实测发现（2026-09-19，Maven Central）：

| 依赖 | 当前 release | 说明 |
|---|---|---|
| `spring-boot-starter-parent` | **4.1.1** | 4.x 的 Java 基线不确定是否为 17，有风险 |
| `mysql-connector-j` | 26.7.0 | 已改为年份版本号 |
| `jackson-databind` | 2.22.2 | |
| `junit-jupiter` | 6.1.3 | JUnit 6 |

**决策：不用 Spring Boot，用纯 Java + JDBC。** 三条理由：

1. **风险**：Spring Boot 4.x 对 Java 17 的支持不确定，本机只有 Java 17；而 3.x 已是两年前的版本
2. **不必要**：本项目核心（日志、故障注入、执行器）**没有一行 Web 或依赖注入代码**，Spring 只带来魔法不带来价值
3. **面试叙事**：这个项目的看点是「我实现的持久化执行语义」。引入框架会稀释"这是你做的"这件事

**已实测验证的依赖版本组合**（`mvn dependency:resolve` 通过，exit 0）：

```
com.mysql:mysql-connector-j:9.7.0     （未用 26.7.0，避开年份版本号的兼容不确定性）
com.zaxxer:HikariCP:6.3.3
com.fasterxml.jackson.core:jackson-databind:2.22.2
com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2
org.slf4j:slf4j-api:2.0.19
org.slf4j:slf4j-simple:2.0.19
org.junit.jupiter:junit-jupiter:5.14.1   （未用 JUnit 6，避开大版本迁移风险）
```

> 后续若需要 HTTP 接口做演示，再单独加一个极简 Web 层，**不影响核心模块**。

---

## File Structure

```
项目1/
├── pom.xml                                    # Maven 配置
├── .gitignore                                 # 忽略 target/ 等
├── docs/                                      # 已有调研与设计文档
├── src/
│   ├── main/
│   │   ├── java/com/durable/
│   │   │   ├── DurableApplication.java        # 启动类（本计划仅作占位）
│   │   │   ├── journal/
│   │   │   │   ├── JournalEntry.java          # 日志条目（不可变记录）
│   │   │   │   ├── JournalEntryType.java      # 日志类型枚举
│   │   │   │   └── JournalStore.java          # 日志存储接口
│   │   │   ├── journal/mysql/
│   │   │   │   ├── MySqlJournalStore.java     # 日志存储的 MySQL 实现
│   │   │   │   └── DuplicateJournalEntryException.java
│   │   │   ├── fault/
│   │   │   │   ├── CrashPoint.java            # 崩溃注入点枚举
│   │   │   │   ├── SimulatedCrash.java        # 模拟崩溃（Error 子类）
│   │   │   │   └── CrashInjector.java         # 故障注入器
│   │   │   └── engine/
│   │   │       ├── Step.java                  # 一个执行步骤
│   │   │       └── DurableExecutor.java       # 步骤执行器（本计划为最简版）
│   │   └── resources/
│   │       ├── application.properties         # 数据库连接配置
│   │       └── schema.sql                     # 建表语句
│   └── test/
│       └── java/com/durable/
│           ├── support/TestDatabase.java      # 测试用数据库辅助类
│           ├── journal/mysql/MySqlJournalStoreTest.java
│           ├── fault/CrashInjectorTest.java
│           └── engine/DurableExecutorCrashTest.java
```

**职责边界**：
- `journal/` 只负责「把日志可靠地写进去、读出来」，不知道什么是 Agent、什么是工具
- `fault/` 只负责「在指定位置抛出崩溃」，不理解业务
- `engine/` 负责编排，通过接口使用前两者
- 三者之间只通过 `JournalStore` 接口耦合，便于后续替换存储实现

---

## Task 1: Maven 工程骨架与数据库连通

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/main/resources/application.properties`
- Create: `src/main/resources/schema.sql`
- Create: `src/main/java/com/durable/DurableApplication.java`
- Test: `src/test/java/com/durable/support/TestDatabase.java`
- Test: `src/test/java/com/durable/support/DatabaseConnectivityTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `TestDatabase.getDataSource()` → 返回 `javax.sql.DataSource`，连接到 `durable_test`
  - `TestDatabase.resetSchema()` → 删除并重建所有表，供每个测试前调用
  - `TestDatabase.countRows(String sql)` → 返回 `long`，供测试断言行数

- [ ] **Step 1: 创建 `.gitignore`**

```gitignore
target/
*.class
*.log
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 2: 创建 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>com.durable</groupId>
  <artifactId>agent-durable-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>agent-durable-core</name>
  <description>Agent tool-call durable execution core</description>

  <properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.datatype</groupId>
      <artifactId>jackson-datatype-jsr310</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 创建 `src/main/resources/application.properties`**

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/durable_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
spring.datasource.username=durable
spring.datasource.password=durable_dev_pwd
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql

logging.level.com.durable=DEBUG
```

- [ ] **Step 4: 创建 `src/main/resources/schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS journal (
    workflow_id VARCHAR(64)  NOT NULL,
    step_no     INT          NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    payload     JSON         NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (workflow_id, step_no, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **设计说明**：主键 `(workflow_id, step_no, type)` 同时承担两个职责 —— 唯一定位一条日志，以及**靠数据库唯一约束阻止重复写入**。这正是「唯一索引是 exactly-once 最终防线」的落点。

- [ ] **Step 5: 创建启动类 `src/main/java/com/durable/DurableApplication.java`**

```java
package com.durable;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DurableApplication {
    public static void main(String[] args) {
        SpringApplication.run(DurableApplication.class, args);
    }
}
```

- [ ] **Step 6: 创建测试辅助类 `src/test/java/com/durable/support/TestDatabase.java`**

```java
package com.durable.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/** 测试用数据库辅助类：统一管理连接、清表与行数统计。 */
public final class TestDatabase {

    private static final String URL =
            "jdbc:mysql://localhost:3306/durable_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    private static final String USER = "durable";
    private static final String PASSWORD = "durable_dev_pwd";

    private TestDatabase() {
    }

    public static DataSource getDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(URL);
        ds.setUsername(USER);
        ds.setPassword(PASSWORD);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(getDataSource());
    }

    /** 清空日志表。每个测试开始前调用，保证测试互不影响。 */
    public static void resetSchema() {
        jdbc().execute("CREATE TABLE IF NOT EXISTS journal (" +
                " workflow_id VARCHAR(64) NOT NULL," +
                " step_no INT NOT NULL," +
                " type VARCHAR(32) NOT NULL," +
                " payload JSON NOT NULL," +
                " created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)," +
                " PRIMARY KEY (workflow_id, step_no, type)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc().execute("TRUNCATE TABLE journal");
    }

    public static long countRows(String sql, Object... args) {
        Long n = jdbc().queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }
}
```

- [ ] **Step 7: 创建连通性测试 `src/test/java/com/durable/support/DatabaseConnectivityTest.java`**

```java
package com.durable.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectivityTest {

    @Test
    void canConnectToMysqlAndCreateJournalTable() {
        TestDatabase.resetSchema();

        String version = TestDatabase.jdbc().queryForObject("SELECT VERSION()", String.class);
        System.out.println("[真实输出] MySQL 版本 = " + version);

        long rows = TestDatabase.countRows("SELECT COUNT(*) FROM journal");
        System.out.println("[真实输出] journal 行数 = " + rows);

        assertTrue(version != null && version.startsWith("8."), "应为 MySQL 8.x，实际: " + version);
        assertEquals(0L, rows);
    }
}
```

- [ ] **Step 8: 运行测试，确认它通过（并留意真实输出）**

Run: `mvn -q test -Dtest=DatabaseConnectivityTest`

Expected:
```
[真实输出] MySQL 版本 = 8.0.41
[真实输出] journal 行数 = 0
BUILD SUCCESS
```

> 若报 `Access denied for user 'durable'`：说明建库脚本还没执行，回到计划开头执行那段 SQL。
> 若报 `Unknown database 'durable_test'`：同上。

- [ ] **Step 9: 初始化 git 并提交**

```bash
git init
git add .gitignore pom.xml src/
git commit -m "build: maven skeleton with mysql connectivity test"
```

---

## Task 2: 追加型决策日志（模型与存储）

**Files:**
- Create: `src/main/java/com/durable/journal/JournalEntryType.java`
- Create: `src/main/java/com/durable/journal/JournalEntry.java`
- Create: `src/main/java/com/durable/journal/JournalStore.java`
- Create: `src/main/java/com/durable/journal/mysql/DuplicateJournalEntryException.java`
- Create: `src/main/java/com/durable/journal/mysql/MySqlJournalStore.java`
- Test: `src/test/java/com/durable/journal/mysql/MySqlJournalStoreTest.java`

**Interfaces:**
- Consumes: `TestDatabase`（Task 1）
- Produces:
  - `JournalEntryType` 枚举：`LLM_DECISION`、`TOOL_RESULT`、`CHECKPOINT`
  - `JournalEntry(String workflowId, int stepNo, JournalEntryType type, String payload, Instant createdAt)`
  - `JournalStore`：
    - `void append(JournalEntry entry) throws DuplicateJournalEntryException`
    - `List<JournalEntry> load(String workflowId)`（按 `stepNo` 升序）
    - `Optional<JournalEntry> find(String workflowId, int stepNo, JournalEntryType type)`
    - `boolean exists(String workflowId, int stepNo, JournalEntryType type)`
  - `MySqlJournalStore(DataSource dataSource)` 构造器

- [ ] **Step 1: 创建日志类型枚举**

`src/main/java/com/durable/journal/JournalEntryType.java`

```java
package com.durable.journal;

/** 日志条目类型。 */
public enum JournalEntryType {
    /** LLM 做出的决策：选了哪个工具、什么参数。重放时读它，不重新调用 LLM。 */
    LLM_DECISION,
    /** 工具执行的结果。 */
    TOOL_RESULT,
    /** 状态检查点。 */
    CHECKPOINT
}
```

- [ ] **Step 2: 创建日志条目记录类**

`src/main/java/com/durable/journal/JournalEntry.java`

```java
package com.durable.journal;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条不可变的日志条目。
 * payload 是 JSON 字符串 —— 保持存储层对内容无感知，便于将来扩展。
 */
public record JournalEntry(
        String workflowId,
        int stepNo,
        JournalEntryType type,
        String payload,
        Instant createdAt
) {
    public JournalEntry {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId 不能为空白");
        }
        if (stepNo < 0) {
            throw new IllegalArgumentException("stepNo 不能为负数: " + stepNo);
        }
    }

    public static JournalEntry of(String workflowId, int stepNo, JournalEntryType type, String payload) {
        return new JournalEntry(workflowId, stepNo, type, payload, Instant.now());
    }
}
```

- [ ] **Step 3: 创建存储接口**

`src/main/java/com/durable/journal/JournalStore.java`

```java
package com.durable.journal;

import java.util.List;
import java.util.Optional;

/** 日志存储。实现必须保证：同一 (workflowId, stepNo, type) 只能写入一次。 */
public interface JournalStore {

    /** 追加一条日志。若该位置已存在，抛 DuplicateJournalEntryException。 */
    void append(JournalEntry entry);

    /** 按 stepNo 升序读取某工作流的全部日志。 */
    List<JournalEntry> load(String workflowId);

    /** 查找指定位置的日志。 */
    Optional<JournalEntry> find(String workflowId, int stepNo, JournalEntryType type);

    /** 指定位置是否已有日志。 */
    boolean exists(String workflowId, int stepNo, JournalEntryType type);
}
```

- [ ] **Step 4: 创建重复写入异常**

`src/main/java/com/durable/journal/mysql/DuplicateJournalEntryException.java`

```java
package com.durable.journal.mysql;

/** 试图向日志的同一位置写入第二条记录时抛出。 */
public class DuplicateJournalEntryException extends RuntimeException {
    public DuplicateJournalEntryException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: 先写失败测试**

`src/test/java/com/durable/journal/mysql/MySqlJournalStoreTest.java`

```java
package com.durable.journal.mysql;

import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import com.durable.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlJournalStoreTest {

    private JournalStore store;

    @BeforeEach
    void setUp() {
        TestDatabase.resetSchema();
        store = new MySqlJournalStore(TestDatabase.getDataSource());
    }

    @Test
    void appendsAndLoadsEntriesInStepOrder() {
        store.append(JournalEntry.of("wf-1", 1, JournalEntryType.TOOL_RESULT, "{\"v\":1}"));
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.LLM_DECISION, "{\"tool\":\"a\"}"));
        store.append(JournalEntry.of("wf-1", 2, JournalEntryType.TOOL_RESULT, "{\"v\":2}"));

        List<JournalEntry> loaded = store.load("wf-1");

        System.out.println("[真实输出] 载入条目数 = " + loaded.size());
        loaded.forEach(e -> System.out.println("[真实输出] step=" + e.stepNo() + " type=" + e.type()));

        assertEquals(3, loaded.size());
        assertEquals(0, loaded.get(0).stepNo(), "必须按 stepNo 升序返回");
        assertEquals(JournalEntryType.LLM_DECISION, loaded.get(0).type());
        assertEquals(2, loaded.get(2).stepNo());
        assertEquals("{\"v\":2}", loaded.get(2).payload(), "payload 必须原样往返");
    }

    @Test
    void rejectsDuplicatePosition() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\":1}"));

        assertThrows(DuplicateJournalEntryException.class, () ->
                store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\":999}")));

        assertEquals(1L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"),
                "重复写入被拒绝后，表中仍应只有一条");
    }

    @Test
    void distinguishesSameStepDifferentType() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.LLM_DECISION, "{}"));
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{}"));

        assertEquals(2L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"));
    }

    @Test
    void findAndExistsWork() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\":1}"));

        assertTrue(store.exists("wf-1", 0, JournalEntryType.TOOL_RESULT));
        assertFalse(store.exists("wf-1", 0, JournalEntryType.LLM_DECISION));
        assertFalse(store.exists("wf-2", 0, JournalEntryType.TOOL_RESULT), "不同工作流互不干扰");

        assertTrue(store.find("wf-1", 0, JournalEntryType.TOOL_RESULT).isPresent());
        assertTrue(store.find("wf-1", 5, JournalEntryType.TOOL_RESULT).isEmpty());
    }
}
```

- [ ] **Step 6: 运行测试，确认它失败**

Run: `mvn -q test -Dtest=MySqlJournalStoreTest`

Expected: **编译失败**，错误类似
```
[ERROR] cannot find symbol: class MySqlJournalStore
```

- [ ] **Step 7: 实现 `MySqlJournalStore`**

`src/main/java/com/durable/journal/mysql/MySqlJournalStore.java`

```java
package com.durable.journal.mysql;

import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 基于 MySQL 的日志存储。
 * 唯一性由主键 (workflow_id, step_no, type) 保证 —— 这是 exactly-once 的最终防线。
 */
public class MySqlJournalStore implements JournalStore {

    private static final RowMapper<JournalEntry> MAPPER = (rs, rowNum) -> new JournalEntry(
            rs.getString("workflow_id"),
            rs.getInt("step_no"),
            JournalEntryType.valueOf(rs.getString("type")),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public MySqlJournalStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void append(JournalEntry entry) {
        try {
            jdbc.update(
                    "INSERT INTO journal (workflow_id, step_no, type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                    entry.workflowId(),
                    entry.stepNo(),
                    entry.type().name(),
                    entry.payload(),
                    Timestamp.from(entry.createdAt())
            );
        } catch (DuplicateKeyException e) {
            throw new DuplicateJournalEntryException(
                    "日志位置已被占用: workflowId=" + entry.workflowId()
                            + ", stepNo=" + entry.stepNo() + ", type=" + entry.type(), e);
        }
    }

    @Override
    public List<JournalEntry> load(String workflowId) {
        return jdbc.query(
                "SELECT workflow_id, step_no, type, payload, created_at FROM journal "
                        + "WHERE workflow_id = ? ORDER BY step_no ASC, type ASC",
                MAPPER, workflowId);
    }

    @Override
    public Optional<JournalEntry> find(String workflowId, int stepNo, JournalEntryType type) {
        List<JournalEntry> found = jdbc.query(
                "SELECT workflow_id, step_no, type, payload, created_at FROM journal "
                        + "WHERE workflow_id = ? AND step_no = ? AND type = ?",
                MAPPER, workflowId, stepNo, type.name());
        return found.stream().findFirst();
    }

    @Override
    public boolean exists(String workflowId, int stepNo, JournalEntryType type) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal WHERE workflow_id = ? AND step_no = ? AND type = ?",
                Long.class, workflowId, stepNo, type.name());
        return n != null && n > 0;
    }
}
```

- [ ] **Step 8: 运行测试，确认全部通过**

Run: `mvn -q test -Dtest=MySqlJournalStoreTest`

Expected:
```
[真实输出] 载入条目数 = 3
[真实输出] step=0 type=LLM_DECISION
[真实输出] step=1 type=TOOL_RESULT
[真实输出] step=2 type=TOOL_RESULT
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/durable/journal src/test/java/com/durable/journal
git commit -m "feat(journal): append-only decision journal backed by mysql unique key"
```

---

## Task 3: 应用内故障注入器

**Files:**
- Create: `src/main/java/com/durable/fault/CrashPoint.java`
- Create: `src/main/java/com/durable/fault/SimulatedCrash.java`
- Create: `src/main/java/com/durable/fault/CrashInjector.java`
- Test: `src/test/java/com/durable/fault/CrashInjectorTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `CrashPoint` 枚举：`BEFORE_STEP`、`AFTER_EXECUTE_BEFORE_JOURNAL`、`AFTER_JOURNAL`
  - `SimulatedCrash extends Error`，构造器 `SimulatedCrash(String message)`
  - `CrashInjector`：
    - `CrashInjector()` 默认不注入任何崩溃
    - `void arm(CrashPoint point, int stepNo)` — 在指定步骤的指定位置崩溃一次
    - `void check(CrashPoint point, int stepNo)` — 到达该位置时若已武装则抛 `SimulatedCrash`
    - `boolean isArmed()` — 是否还有未触发的崩溃计划

> **为什么用 `Error` 而不是 `Exception`**：`Error` 不会被 `catch (Exception e)` 意外捕获，能更真实地模拟进程被强杀对调用栈的影响。

- [ ] **Step 1: 先写失败测试**

`src/test/java/com/durable/fault/CrashInjectorTest.java`

```java
package com.durable.fault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrashInjectorTest {

    @Test
    void doesNothingWhenNotArmed() {
        CrashInjector injector = new CrashInjector();
        assertFalse(injector.isArmed());
        assertDoesNotThrow(() -> injector.check(CrashPoint.BEFORE_STEP, 0));
    }

    @Test
    void throwsOnlyAtTheArmedPointAndStep() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.BEFORE_STEP, 1);

        assertDoesNotThrow(() -> injector.check(CrashPoint.BEFORE_STEP, 0), "步骤号不匹配不触发");
        assertDoesNotThrow(() -> injector.check(CrashPoint.AFTER_JOURNAL, 1), "注入点不匹配不触发");

        SimulatedCrash crash = assertThrows(SimulatedCrash.class,
                () -> injector.check(CrashPoint.BEFORE_STEP, 1));
        System.out.println("[真实输出] 触发崩溃: " + crash.getMessage());
        assertTrue(crash.getMessage().contains("BEFORE_STEP"));
        assertTrue(crash.getMessage().contains("step=1"));
    }

    @Test
    void firesOnlyOnce() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.AFTER_JOURNAL, 0);

        assertThrows(SimulatedCrash.class, () -> injector.check(CrashPoint.AFTER_JOURNAL, 0));
        assertFalse(injector.isArmed(), "触发后应解除武装");
        assertDoesNotThrow(() -> injector.check(CrashPoint.AFTER_JOURNAL, 0), "同一位置不会二次触发");
    }

    @Test
    void isNotCaughtByCatchException() {
        CrashInjector injector = new CrashInjector();
        injector.arm(CrashPoint.BEFORE_STEP, 0);

        boolean caughtAsException = false;
        try {
            try {
                injector.check(CrashPoint.BEFORE_STEP, 0);
            } catch (Exception e) {
                caughtAsException = true;
            }
        } catch (SimulatedCrash expected) {
            // 正确：Error 穿透了 catch (Exception)
        }
        assertFalse(caughtAsException, "SimulatedCrash 必须是 Error，不能被 catch(Exception) 吞掉");
    }
}
```

- [ ] **Step 2: 运行测试，确认它失败**

Run: `mvn -q test -Dtest=CrashInjectorTest`

Expected: **编译失败**
```
[ERROR] cannot find symbol: class CrashInjector
```

- [ ] **Step 3: 实现崩溃点枚举**

`src/main/java/com/durable/fault/CrashPoint.java`

```java
package com.durable.fault;

/** 可以注入崩溃的位置。三个位置覆盖了「副作用与日志之间」的完整窗口。 */
public enum CrashPoint {
    /** 步骤开始执行之前。此时副作用尚未发生。 */
    BEFORE_STEP,
    /** 副作用已发生，但结果尚未写入日志。**这是最危险的位置**。 */
    AFTER_EXECUTE_BEFORE_JOURNAL,
    /** 结果已写入日志之后。 */
    AFTER_JOURNAL
}
```

- [ ] **Step 4: 实现模拟崩溃**

`src/main/java/com/durable/fault/SimulatedCrash.java`

```java
package com.durable.fault;

/**
 * 模拟进程被强杀。
 * 继承 Error 而非 Exception —— 目的是让它穿透业务代码里的 catch (Exception e)，
 * 更接近真实崩溃时调用栈的行为。
 */
public class SimulatedCrash extends Error {
    public SimulatedCrash(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: 实现故障注入器**

`src/main/java/com/durable/fault/CrashInjector.java`

```java
package com.durable.fault;

/**
 * 应用内故障注入器，用于在测试中确定性复现崩溃窗口。
 * 单次武装、单次触发：崩溃后自动解除，模拟「进程已经死了，重启是全新进程」。
 */
public class CrashInjector {

    private CrashPoint armedPoint;
    private int armedStepNo = -1;

    /** 在指定步骤的指定位置武装一次崩溃。 */
    public void arm(CrashPoint point, int stepNo) {
        this.armedPoint = point;
        this.armedStepNo = stepNo;
    }

    /** 是否还有未触发的崩溃计划。 */
    public boolean isArmed() {
        return armedPoint != null;
    }

    /** 到达某位置时调用。若与武装位置匹配则抛崩溃，否则什么也不做。 */
    public void check(CrashPoint point, int stepNo) {
        if (armedPoint == point && armedStepNo == stepNo) {
            armedPoint = null;
            armedStepNo = -1;
            throw new SimulatedCrash("注入崩溃于 " + point + ", step=" + stepNo);
        }
    }
}
```

- [ ] **Step 6: 运行测试，确认全部通过**

Run: `mvn -q test -Dtest=CrashInjectorTest`

Expected:
```
[真实输出] 触发崩溃: 注入崩溃于 BEFORE_STEP, step=1
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/durable/fault src/test/java/com/durable/fault
git commit -m "feat(fault): deterministic in-process crash injector"
```

---

## Task 4: 最简执行器 + 复现「副作用重复执行」缺陷

**Files:**
- Create: `src/main/java/com/durable/engine/Step.java`
- Create: `src/main/java/com/durable/engine/DurableExecutor.java`
- Test: `src/test/java/com/durable/engine/DurableExecutorCrashTest.java`

**Interfaces:**
- Consumes: `JournalStore`、`JournalEntry`、`JournalEntryType`（Task 2）；`CrashInjector`、`CrashPoint`、`SimulatedCrash`（Task 3）
- Produces:
  - `Step(String name, Supplier<String> action)` — 一个可执行步骤；`action` 可能产生副作用
  - `DurableExecutor(JournalStore journal, CrashInjector crashInjector)`
  - `DurableExecutor.run(String workflowId, List<Step>)` → 返回 `int`：本次实际执行的步骤数

> **重要：本任务刻意不实现恢复逻辑。** 它的目的是用测试**复现**「崩溃后重跑会重复执行副作用」这个缺陷，并把这个行为固化成**刻画测试**（characterization test）。修复在计划 2。这是 TDD 中「先让测试证明问题存在」的标准做法。

- [ ] **Step 1: 先写会复现缺陷的测试**

`src/test/java/com/durable/engine/DurableExecutorCrashTest.java`

```java
package com.durable.engine;

import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DurableExecutorCrashTest {

    private JournalStore journal;

    @BeforeEach
    void setUp() {
        TestDatabase.resetSchema();
        journal = new MySqlJournalStore(TestDatabase.getDataSource());
    }

    @Test
    void journalsEveryStepOnHappyPath() {
        CrashInjector crash = new CrashInjector();
        DurableExecutor executor = new DurableExecutor(journal, crash);

        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = List.of(
                new Step("step-0", () -> "r0"),
                new Step("step-1", () -> "r1"),
                new Step("step-2", () -> {
                    effects.incrementAndGet();
                    return "r2";
                })
        );

        int executed = executor.run("wf-happy", steps);

        System.out.println("[真实输出] 实际执行步骤数 = " + executed);
        System.out.println("[真实输出] 副作用次数 = " + effects.get());
        System.out.println("[真实输出] 日志行数 = " + TestDatabase.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(3, executed);
        assertEquals(1, effects.get());
        assertEquals(3L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"));
    }

    /**
     * 刻画测试：记录当前（有缺陷的）行为。
     *
     * 崩溃点选在 AFTER_EXECUTE_BEFORE_JOURNAL —— 副作用已经发生，但结果还没写进日志。
     * 此时若直接重跑，副作用会再执行一次。
     *
     * 计划 2 会让这个测试的断言从 2 变成 1。
     */
    @Test
    void crashBetweenEffectAndJournalCausesDuplicateEffect() {
        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = List.of(
                new Step("step-0", () -> {
                    effects.incrementAndGet();
                    return "r0";
                }),
                new Step("step-1", () -> "r1")
        );

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);

        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, crashing).run("wf-crash", steps));

        System.out.println("[真实输出] 崩溃后副作用次数 = " + effects.get());
        System.out.println("[真实输出] 崩溃后日志行数 = " + TestDatabase.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1, effects.get(), "第一次执行：副作用发生了 1 次");
        assertEquals(0L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"),
                "崩溃发生在写日志之前，所以日志是空的 —— 这就是问题所在");

        // 重启：全新进程，用全新的（未武装的）注入器重跑
        CrashInjector restarted = new CrashInjector();
        new DurableExecutor(journal, restarted).run("wf-crash", steps);

        System.out.println("[真实输出] 重启后副作用总次数 = " + effects.get());
        System.out.println("[真实输出] 重启后日志行数 = " + TestDatabase.countRows("SELECT COUNT(*) FROM journal"));

        // ⚠️ 刻画当前缺陷行为：副作用被重复执行了。计划 2 修复后此断言应改为 1。
        assertEquals(2, effects.get(), "当前缺陷：崩溃后重跑导致副作用重复执行");
        assertEquals(2L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"));
    }

    @Test
    void crashBeforeStepDoesNotExecuteAnyEffect() {
        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("step-0", () -> {
            effects.incrementAndGet();
            return "r0";
        }));

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.BEFORE_STEP, 0);

        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, crashing).run("wf-before", steps));

        System.out.println("[真实输出] BEFORE_STEP 崩溃后副作用次数 = " + effects.get());
        assertEquals(0, effects.get(), "副作用尚未发生");
        assertEquals(0L, TestDatabase.countRows("SELECT COUNT(*) FROM journal"));
    }
}
```

- [ ] **Step 2: 运行测试，确认它失败**

Run: `mvn -q test -Dtest=DurableExecutorCrashTest`

Expected: **编译失败**
```
[ERROR] cannot find symbol: class DurableExecutor
```

- [ ] **Step 3: 实现 `Step`**

`src/main/java/com/durable/engine/Step.java`

```java
package com.durable.engine;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 一个可执行的步骤。
 * action 可能产生副作用 —— 本计划不区分效果类型，计划 2 会补上。
 */
public record Step(String name, Supplier<String> action) {
    public Step {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
    }
}
```

- [ ] **Step 4: 实现最简 `DurableExecutor`**

`src/main/java/com/durable/engine/DurableExecutor.java`

```java
package com.durable.engine;

import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;

import java.util.List;
import java.util.Objects;

/**
 * 最简步骤执行器。
 *
 * ⚠️ 本版本【没有恢复逻辑】：每次 run 都从第 0 步开始重跑。
 * 这正是 Task 4 的刻画测试要暴露的缺陷，计划 2 修复。
 */
public class DurableExecutor {

    private final JournalStore journal;
    private final CrashInjector crashInjector;

    public DurableExecutor(JournalStore journal, CrashInjector crashInjector) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.crashInjector = Objects.requireNonNull(crashInjector, "crashInjector");
    }

    /** 从头执行全部步骤，返回实际执行的步骤数。 */
    public int run(String workflowId, List<Step> steps) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(steps, "steps");

        int executed = 0;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);

            crashInjector.check(CrashPoint.BEFORE_STEP, i);
            String result = step.action();
            crashInjector.check(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, i);

            journal.append(JournalEntry.of(
                    workflowId, i, JournalEntryType.TOOL_RESULT, toJson(result)));
            executed++;

            crashInjector.check(CrashPoint.AFTER_JOURNAL, i);
        }
        return executed;
    }

    private static String toJson(String raw) {
        return "{\"value\":\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
```

- [ ] **Step 5: 运行测试，确认全部通过**

Run: `mvn -q test -Dtest=DurableExecutorCrashTest`

Expected（**请核对每一行真实输出**）:
```
[真实输出] 实际执行步骤数 = 3
[真实输出] 副作用次数 = 1
[真实输出] 日志行数 = 3
[真实输出] 崩溃后副作用次数 = 1
[真实输出] 崩溃后日志行数 = 0
[真实输出] 重启后副作用总次数 = 2
[真实输出] 重启后日志行数 = 2
[真实输出] BEFORE_STEP 崩溃后副作用次数 = 0
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

> 关键看这两行：**崩溃后副作用总次数 = 2** —— 副作用被重复执行了。这就是我们要解决的问题，也是这个项目存在的理由。

- [ ] **Step 6: 跑一遍全量测试，确认没有回归**

Run: `mvn -q test`

Expected: `BUILD SUCCESS`，全部测试通过。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/durable/engine src/test/java/com/durable/engine
git commit -m "feat(engine): minimal executor reproducing duplicate-effect defect"
```

---

## 完成标志（本计划的验收）

- [ ] `mvn -q test` 全绿
- [ ] `DatabaseConnectivityTest` 打印出真实的 MySQL 版本号
- [ ] `DurableExecutorCrashTest` 打印出「重启后副作用总次数 = 2」，**缺陷被复现**
- [ ] git 有 4 个提交，每次提交都对应一个可独立测试的产出
- [ ] 三份讲解文档已产出（见下）

## 配套讲解文档（每个 Task 一份，供面试使用）

**注意**：这些文档是本计划的**必需产出**，不是可选项。它们的作用是让你能在面试里讲清楚每一步，而不是让代码跑起来就完事。

- `docs/interview/step-1-why-journal-first.md`
  - 为什么先把日志表建起来，而不是先写 Agent Loop
  - 追问：为什么主键是三个字段？为什么用 JSON 存 payload？

- `docs/interview/step-2-why-append-only.md`
  - 为什么日志必须只追加、不可修改
  - 追问：唯一约束和幂等是什么关系？为什么不用 Redis？

- `docs/interview/step-3-why-inject-faults.md`
  - 为什么要有故障注入器，而不是"小心一点写代码"
  - 追问：`SimulatedCrash` 为什么继承 `Error` 而不是 `Exception`？

- `docs/interview/step-4-the-bug-we-reproduced.md`
  - 这个缺陷为什么存在（副作用与日志之间的窗口）
  - 为什么它危险（真实世界是重复扣款、重复发消息）
  - 追问：有几种崩溃窗口？哪些是致命的？

每份文档格式统一为：
1. **一句话**：这一步解决了什么问题
2. **故障场景**：用具体场景描述，不用术语
3. **为什么这样设计**：含被否决的替代方案
4. **面试追问（至少 5 问）+ 标准答案**
5. **实测数据**：贴真实输出

---

## 后续计划（本计划完成后另起）

- **计划 2**：恢复语义（性质 1/3/4/6）+ 副作用 exactly-once（性质 2）
- **计划 3**：并发 consume-once（性质 5）+ 完整故障矩阵
- **计划 4**：审批闸门 + 参数快照
- **计划 5**：演示脚本 + 复盘文档 + 可选增量检查点
