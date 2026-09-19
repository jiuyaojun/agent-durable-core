package com.durable.journal.mysql;

import com.durable.db.Db;
import com.durable.journal.JournalEntry;
import com.durable.journal.JournalEntryType;
import com.durable.journal.JournalStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MySQL 决策日志存储")
class MySqlJournalStoreTest {

    private JournalStore store;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        store = new MySqlJournalStore(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("按 stepNo 升序读回，payload 原样往返")
    void appendsAndLoadsEntriesInStepOrder() {
        store.append(JournalEntry.of("wf-1", 1, JournalEntryType.TOOL_RESULT, "{\"v\": 1}"));
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.LLM_DECISION, "{\"tool\": \"a\"}"));
        store.append(JournalEntry.of("wf-1", 2, JournalEntryType.TOOL_RESULT, "{\"v\": 2}"));

        List<JournalEntry> loaded = store.load("wf-1");

        System.out.println("[真实输出] 载入条目数 = " + loaded.size());
        loaded.forEach(e -> System.out.println(
                "[真实输出] step=" + e.stepNo() + " type=" + e.type() + " payload=" + e.payload()));

        assertEquals(3, loaded.size());
        assertEquals(0, loaded.get(0).stepNo(), "必须按 stepNo 升序返回");
        assertEquals(JournalEntryType.LLM_DECISION, loaded.get(0).type());
        assertEquals(2, loaded.get(2).stepNo());
        assertEquals("{\"v\": 2}", loaded.get(2).payload(), "payload 必须原样往返（MySQL JSON 类型会规范化空白）");
    }

    @Test
    @DisplayName("同一位置写入第二条会被数据库唯一约束拒绝")
    void rejectsDuplicatePosition() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\": 1}"));

        DuplicateJournalEntryException ex = assertThrows(DuplicateJournalEntryException.class, () ->
                store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\": 999}")));

        System.out.println("[真实输出] 重复写入被拒: " + ex.getMessage());
        System.out.println("[真实输出] 表中剩余行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1L, Db.countRows("SELECT COUNT(*) FROM journal"), "被拒后表中仍应只有一条");
    }

    @Test
    @DisplayName("同一步骤的不同类型互不冲突")
    void distinguishesSameStepDifferentType() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.LLM_DECISION, "{}"));
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{}"));

        long rows = Db.countRows("SELECT COUNT(*) FROM journal");
        System.out.println("[真实输出] 同步骤两类型后的行数 = " + rows);

        assertEquals(2L, rows);
    }

    @Test
    @DisplayName("不同工作流互不干扰")
    void isolatesDifferentWorkflows() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\": 1}"));
        store.append(JournalEntry.of("wf-2", 0, JournalEntryType.TOOL_RESULT, "{\"v\": 2}"));

        long rows = Db.countRows("SELECT COUNT(*) FROM journal");
        System.out.println("[真实输出] 两个工作流后的行数 = " + rows);
        assertEquals(2L, rows);

        assertEquals(1, store.load("wf-1").size());
        assertEquals(1, store.load("wf-2").size());
        assertEquals(0, store.load("wf-3").size());
    }

    @Test
    @DisplayName("find 与 exists 行为正确")
    void findAndExistsWork() {
        store.append(JournalEntry.of("wf-1", 0, JournalEntryType.TOOL_RESULT, "{\"v\": 1}"));

        assertTrue(store.exists("wf-1", 0, JournalEntryType.TOOL_RESULT));
        assertFalse(store.exists("wf-1", 0, JournalEntryType.LLM_DECISION));
        assertFalse(store.exists("wf-2", 0, JournalEntryType.TOOL_RESULT));

        assertTrue(store.find("wf-1", 0, JournalEntryType.TOOL_RESULT).isPresent());
        assertTrue(store.find("wf-1", 5, JournalEntryType.TOOL_RESULT).isEmpty());
    }
}
