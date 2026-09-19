package com.durable.engine;

import com.durable.db.Db;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("执行器的崩溃行为")
class DurableExecutorCrashTest {

    private JournalStore journal;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("无故障时每个步骤都写一条日志")
    void journalsEveryStepOnHappyPath() {
        DurableExecutor executor = new DurableExecutor(journal, new CrashInjector());

        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = List.of(
                new Step("step-0", () -> "r0"),
                new Step("step-1", () -> "r1"),
                new Step("step-2", () -> {
                    effects.incrementAndGet();
                    return "r2";
                }));

        int executed = executor.run("wf-happy", steps);

        System.out.println("[真实输出] 实际执行步骤数 = " + executed);
        System.out.println("[真实输出] 副作用次数 = " + effects.get());
        System.out.println("[真实输出] 日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(3, executed);
        assertEquals(1, effects.get());
        assertEquals(3L, Db.countRows("SELECT COUNT(*) FROM journal"));
    }

    /**
     * 刻画测试（characterization test）：把当前【有缺陷】的行为固化成断言。
     *
     * 崩溃点选在 AFTER_EXECUTE_BEFORE_JOURNAL —— 副作用已经发生，但结果还没写进日志。
     * 此时重跑，副作用会再执行一次。这就是本项目存在的理由。
     *
     * 计划 2 修复后，本测试的断言 "重启后副作用总次数 = 2" 应改为 1。
     */
    @Test
    @DisplayName("【缺陷复现】副作用与日志之间崩溃，重跑导致副作用重复执行")
    void crashBetweenEffectAndJournalCausesDuplicateEffect() {
        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = List.of(
                new Step("step-0", () -> {
                    effects.incrementAndGet();
                    return "r0";
                }),
                new Step("step-1", () -> "r1"));

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);

        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, crashing).run("wf-crash", steps));

        System.out.println("[真实输出] 崩溃后副作用次数 = " + effects.get());
        System.out.println("[真实输出] 崩溃后日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(1, effects.get(), "第一次执行：副作用发生了 1 次");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal"),
                "崩溃发生在写日志之前，所以日志是空的 —— 这正是问题所在");

        // 模拟重启：全新进程，用全新的（未武装的）注入器重跑
        new DurableExecutor(journal, new CrashInjector()).run("wf-crash", steps);

        System.out.println("[真实输出] 重启后副作用总次数 = " + effects.get());
        System.out.println("[真实输出] 重启后日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal"));

        // ⚠️ 当前缺陷行为：副作用被重复执行。计划 2 修复后此断言应改为 1。
        assertEquals(2, effects.get(), "当前缺陷：崩溃后重跑导致副作用重复执行");
        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal"));
    }

    @Test
    @DisplayName("步骤开始前崩溃：副作用未发生，日志为空")
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
        System.out.println("[真实输出] BEFORE_STEP 崩溃后日志行数 = "
                + Db.countRows("SELECT COUNT(*) FROM journal"));

        assertEquals(0, effects.get(), "副作用尚未发生");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal"));
    }
}
