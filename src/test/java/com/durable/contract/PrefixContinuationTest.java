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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PC（Prefix continuation）性质。
 *
 * 论文原文：
 *   "Recovery continues from the durably recorded frontier state... or in a state
 *    re-derived deterministically from the durable log alone that equals S_F."
 *   "Memoized replay conforms: prefix code **may re-run** during recovery provided
 *    every prefix effect is served from the durable record (so EO is preserved)
 *    and the re-derived state is a pure function of the log."
 *
 * 注意「may re-run」—— 允许重跑前缀代码，这正是我们的做法。
 * 所以本测试不是断言「不重跑」，而是断言两件更强的事：
 *   ① 重跑时副作用全部从账本取
 *   ② 恢复后的可观测状态 == 干净跑一遍的状态
 */
@DisplayName("契约 · PC：前缀续跑")
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
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-baseline", steps());
        List<String> baseline = resultValues("wf-baseline");

        // 清掉基线留下的资源，让第二个工作流从同样的起点开始。
        // journal 与 ledger 里 wf-baseline 的记录保留不动 —— 它们属于另一个工作流。
        clearHosts();

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-recovered", steps()));
        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-recovered", steps());
        List<String> recovered = resultValues("wf-recovered");

        System.out.println("[真实输出] 基线状态   = " + baseline);
        System.out.println("[真实输出] 恢复后状态 = " + recovered);
        System.out.println("[真实输出] 恢复后 host 行数 = " + hostTools.hostCount());
        System.out.println("[真实输出] wf-recovered 日志行数 = " + recovered.size());

        assertEquals(baseline, recovered, "PC：恢复后的可观测状态必须等于干净跑一遍的状态");
        assertEquals(1L, hostTools.hostCount(), "恢复不应多创造一台主机");
    }

    private static void clearHosts() {
        try (var conn = Db.dataSource().getConnection();
             var st = conn.createStatement()) {
            st.execute("DELETE FROM host");
        } catch (Exception e) {
            throw new IllegalStateException("清理 host 失败", e);
        }
    }

    @Test
    @DisplayName("前缀代码重跑，但前缀副作用必须从账本取")
    void prefixEffectIsServedFromLedgerOnResume() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-pc", steps()));

        var resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-pc", steps());

        System.out.println("[真实输出] 恢复时 effectsReused = " + resumed.effectsReused());
        System.out.println("[真实输出] 恢复时 effectsFired  = " + resumed.effectsFired());
        System.out.println("[真实输出] host 行数 = " + hostTools.hostCount());

        assertEquals(1, resumed.effectsReused(), "第 0 步重跑了代码，但副作用命中账本复用");
        assertEquals(0, resumed.effectsFired(), "恢复时不应再触发任何新的非幂等副作用");
        assertEquals(1L, hostTools.hostCount());
    }

    @Test
    @DisplayName("多步前缀全部从账本取：三步都已完成时恢复不再触发副作用")
    void fullPrefixServedFromLedger() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 1);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-pc2", steps()));

        var resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-pc2", steps());

        System.out.println("[真实输出] 二次恢复 effectsFired = " + resumed.effectsFired()
                + ", host 行数 = " + hostTools.hostCount());

        assertEquals(0, resumed.effectsFired());
        assertEquals(1L, hostTools.hostCount());
    }

    @Test
    @DisplayName("恢复时重跑前缀不会在日志里留下重复记录，且旧值不被覆盖")
    void prefixReRunDoesNotDuplicateOrOverwriteJournal() {
        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.AFTER_JOURNAL, 0);
        assertThrows(SimulatedCrash.class,
                () -> new DurableExecutor(journal, ledger, crashing).run("wf-dedup", steps()));

        List<String> beforeResume = resultValues("wf-dedup");

        new DurableExecutor(journal, ledger, new CrashInjector()).run("wf-dedup", steps());

        List<String> afterResume = resultValues("wf-dedup");

        System.out.println("[真实输出] 恢复前日志条目 = " + beforeResume.size());
        System.out.println("[真实输出] 恢复后日志条目 = " + afterResume.size());
        System.out.println("[真实输出] 恢复后 journal 表行数（应等于 2，不是 3） = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-dedup'"));

        assertEquals(2L, Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-dedup'"),
                "第 0 步被重跑了一次，但日志里仍应只有一条记录");
        assertEquals(beforeResume.get(0), afterResume.get(0), "先写入的日志是权威来源，不被重跑覆盖");
    }

    /** 从日志里按 stepNo 顺序取出每步的 value，作为「可观测状态」。 */
    private List<String> resultValues(String workflowId) {
        List<JournalEntry> entries = new ArrayList<>(journal.load(workflowId));
        entries.sort(Comparator.comparingInt(JournalEntry::stepNo));
        List<String> values = new ArrayList<>();
        for (JournalEntry e : entries) {
            if (e.type() == JournalEntryType.STEP_RESULT) {
                values.add(e.payload());
            }
        }
        return values;
    }
}
