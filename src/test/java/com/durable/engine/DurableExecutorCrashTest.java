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

/**
 * 计划 1 的刻画测试，在计划 2 中回归。
 *
 * 计划 1 时这里的断言是「重启后副作用总次数 = 2」—— 那是刻意固化的缺陷。
 * 引入效果账本后改为 1。**这个 2 → 1 就是修复的量化证据。**
 */
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

    /** 计数器在副作用体内部自增 —— 只有账本真的执行了它才会加一。 */
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
        System.out.println("[真实输出] 日志行数 = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-happy'"));

        assertEquals(2, executed);
        assertEquals(1, effects.get());
        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-happy'"));
    }

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
        System.out.println("[真实输出] 崩溃后日志行数 = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-crash'"));

        assertEquals(1, effects.get());
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-crash'"));

        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-crash", steps);

        System.out.println("[真实输出] 重启后副作用总次数 = " + effects.get());
        System.out.println("[真实输出] 重启后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 重启后日志行数 = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-crash'"));

        assertEquals(1, effects.get(), "修复后：副作用只执行了一次（计划 1 时是 2）");
        assertEquals(1L, hostTools.hostCount());
        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-crash'"));
    }

    @Test
    @DisplayName("步骤开始前崩溃：副作用未发生，账本与日志都为空")
    void crashBeforeStepDoesNotExecuteAnyEffect() {
        AtomicInteger effects = new AtomicInteger();

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.BEFORE_STEP, 0);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-before", steps(effects)));

        System.out.println("[真实输出] BEFORE_STEP 崩溃后副作用次数 = " + effects.get());
        System.out.println("[真实输出] BEFORE_STEP 崩溃后账本行数 = "
                + Db.countRows("SELECT COUNT(*) FROM effect_ledger"));

        assertEquals(0, effects.get(), "副作用尚未发生");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM effect_ledger"));
    }
}
