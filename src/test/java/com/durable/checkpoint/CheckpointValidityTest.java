package com.durable.checkpoint;

import com.durable.db.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        // 校验发生在 record 的紧凑构造器里 —— 非法检查点【根本无法被创建出来】，
        // 也就永远走不到持久化那一步。这是 CV 最强的形式。
        InvalidCheckpointException ex = assertThrows(InvalidCheckpointException.class, () ->
                new Checkpoint("wf-1", 0, 5, Checkpoint.CURRENT_SCHEMA,
                        List.of("only-one"), Instant.now()));

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

        System.out.println("[真实输出] 非法构造被拒绝后 checkpoint 行数 = "
                + Db.countRows("SELECT COUNT(*) FROM checkpoint"));
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM checkpoint"));
    }

    @Test
    @DisplayName("CV：未知 schema 版本被拒绝")
    void rejectsUnknownSchemaVersion() {
        Checkpoint fromFuture = new Checkpoint("wf-1", 0, 0, "v99", List.of("a"), Instant.now());

        InvalidCheckpointException ex = assertThrows(InvalidCheckpointException.class,
                () -> store.save(fromFuture));

        System.out.println("[真实输出] 拒绝原因: " + ex.getMessage());
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
                + " frontierStep = " + latest.frontierStep()
                + " 总检查点数 = " + store.loadAll("wf-1").size());

        assertEquals(2, latest.version());
        assertEquals(2, latest.frontierStep());
        assertEquals(3, store.loadAll("wf-1").size());
    }
}
