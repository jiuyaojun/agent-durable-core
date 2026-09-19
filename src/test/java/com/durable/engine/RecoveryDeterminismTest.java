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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RD（Recovery determinism）性质。
 *
 * 论文原文：
 *   "The recovery decision (which tasks to skip versus re-execute) is a
 *    function of durable state: two recoveries from identical durable logs
 *    make identical decisions."
 *
 * 验收方式很直接：同一份日志算两次计划，断言相等。
 * 这只有在 RecoveryPlan.from() 是纯函数时才成立 —— 所以它不能读时间、不能有随机。
 */
@DisplayName("契约 · RD：恢复决策确定性")
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
    @DisplayName("空日志时计划为从头开始")
    void emptyJournalPlansFromZero() {
        RecoveryPlan plan = new DurableExecutor(journal, ledger, new CrashInjector())
                .plan("wf-empty", 3);

        System.out.println("[真实输出] 空日志计划 = resumeFrom " + plan.resumeFromStep()
                + ", completed " + plan.completedSteps());

        assertEquals(0, plan.resumeFromStep());
        assertTrue(plan.completedSteps().isEmpty());
    }

    @Test
    @DisplayName("部分完成时从断点续跑")
    void partialJournalResumesFromNextStep() {
        new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-partial", List.of(steps().get(0)));

        RecoveryPlan plan = new DurableExecutor(journal, ledger, new CrashInjector())
                .plan("wf-partial", 2);

        System.out.println("[真实输出] 部分完成计划 = resumeFrom " + plan.resumeFromStep()
                + ", completed " + plan.completedSteps());

        assertEquals(1, plan.resumeFromStep());
        assertEquals(List.of(0), plan.completedSteps());
    }

    @Test
    @DisplayName("崩溃恢复后再算计划，依然与前一次一致（日志是唯一输入）")
    void planStableAcrossRecovery() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 0);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-stable", steps()));

        DurableExecutor executor = new DurableExecutor(journal, ledger, new CrashInjector());
        RecoveryPlan before = executor.plan("wf-stable", 2);

        executor.run("wf-stable", steps());

        RecoveryPlan after = executor.plan("wf-stable", 2);

        System.out.println("[真实输出] 恢复前计划 = " + before);
        System.out.println("[真实输出] 恢复后计划 = " + after);

        assertEquals(1, before.resumeFromStep());
        assertEquals(2, after.resumeFromStep(), "跑完之后计划也应反映日志的新状态");
        assertEquals(List.of(0, 1), after.completedSteps());
    }
}
