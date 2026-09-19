package com.durable.contract;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
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

/**
 * EO（Effect exactly-once）性质。
 *
 * 论文原文：
 *   "For every task t, effect e_t fires at most once on a branch across any
 *    sequence of interrupts, crashes, and resumes."
 *   "an effect that commits while its acknowledgment is lost counts as fired"
 *
 * 后半句就是我们那个崩溃窗口 —— 副作用已提交、确认丢失。
 * 本测试组就是验证这个窗口现在被堵住了。
 */
@DisplayName("契约 · EO：副作用至多触发一次")
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
    @DisplayName("无故障：主机恰好创建一台")
    void happyPathCreatesExactlyOneHost() {
        var result = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-eo-happy", steps());

        System.out.println("[真实输出] effectsFired=" + result.effectsFired()
                + " effectsReused=" + result.effectsReused());
        System.out.println("[真实输出] host 行数 = " + hostTools.hostCount());

        assertEquals(1, result.effectsFired());
        assertEquals(0, result.effectsReused());
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
        System.out.println("[真实输出] 崩溃后日志行数 = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-eo-crash'"));

        assertEquals(1L, hostTools.hostCount(), "第一次副作用确实发生了");
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-eo-crash'"),
                "日志仍为空 —— 这正是崩溃窗口");

        // 模拟重启：全新进程
        var resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-eo-crash", steps());

        System.out.println("[真实输出] 恢复后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 恢复后账本已执行数 = " + ledger.countExecuted("wf-eo-crash"));
        System.out.println("[真实输出] 恢复时 effectsReused = " + resumed.effectsReused());

        assertEquals(1L, hostTools.hostCount(), "EO：主机必须恰好一台（计划 1 时这里是 2）");
        assertEquals(1L, ledger.countExecuted("wf-eo-crash"), "账本只应有一条 DONE");
    }

    @Test
    @DisplayName("反复崩溃多次，副作用依然只触发一次")
    void repeatedCrashesStillFireExactlyOnce() {
        for (int round = 0; round < 3; round++) {
            CrashInjector crashing = new CrashInjector();
            crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);
            assertThrows(SimulatedCrash.class,
                    () -> new DurableExecutor(journal, ledger, crashing).run("wf-eo-multi", steps()));
        }

        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-eo-multi", steps());

        System.out.println("[真实输出] 连续 3 次崩溃后恢复，host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] 账本已执行数 = " + ledger.countExecuted("wf-eo-multi"));

        assertEquals(1L, hostTools.hostCount(), "无论崩几次，副作用只触发一次");
        assertEquals(1L, ledger.countExecuted("wf-eo-multi"));
    }

    @Test
    @DisplayName("在副作用之前崩溃：账本位置不被占用，重跑能正常抢占")
    void crashBeforeStepLeavesLedgerFree() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.BEFORE_STEP, 0);

        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-eo-before", steps()));

        System.out.println("[真实输出] BEFORE_STEP 崩溃后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] BEFORE_STEP 崩溃后账本行数 = "
                + Db.countRows("SELECT COUNT(*) FROM effect_ledger"));

        assertEquals(0L, hostTools.hostCount());
        assertEquals(0L, Db.countRows("SELECT COUNT(*) FROM effect_ledger"),
                "崩溃发生在 claim 之前，账本不应被占用");

        var resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-eo-before", steps());

        System.out.println("[真实输出] 恢复后 effectsFired = " + resumed.effectsFired());
        assertEquals(1, resumed.effectsFired(), "第一次抢占应成功");
        assertEquals(1L, hostTools.hostCount());
    }
}
