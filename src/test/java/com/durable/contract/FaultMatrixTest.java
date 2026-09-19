package com.durable.contract;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.ExecutionResult;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 故障矩阵：把「崩溃位置 × 崩溃步骤」的每种组合都跑一遍，逐格检查不变量。
 *
 * 这是论文的验证方法论 —— 它用 39 格故障矩阵来暴露框架之间的差异。
 * 我们这里规模更小（3 个崩溃点 × 3 个步骤 = 9 格），但思路一致：
 * **不靠举例证明正确性，而是把组合穷举掉。**
 *
 * 每一格都要满足同一组不变量：
 *   - 两台主机各被创建【恰好一次】（EO）
 *   - 效果账本恰好 2 条 DONE
 *   - 日志恰好 3 条 STEP_RESULT（重跑不产生重复记录）
 *   - 恢复后的状态 == 干净跑一遍的状态（PC）
 */
@DisplayName("契约 · 故障矩阵")
class FaultMatrixTest {

    private static final int STEP_COUNT = 3;
    private static final int EXPECTED_HOSTS = 2;
    private static final int EXPECTED_JOURNAL_ROWS = 3;

    private JournalStore journal;
    private EffectLedger ledger;
    private HostTools hostTools;

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("9 格故障矩阵：每一格都满足全部不变量")
    void everyCrashCellPreservesAllInvariants() {
        List<String> matrix = new ArrayList<>();
        matrix.add(String.format("%-32s %-6s %-8s %-8s %-8s %s",
                "崩溃位置", "步骤", "副作用", "主机数", "日志", "结果"));

        int cells = 0;
        int passed = 0;

        for (CrashPoint point : CrashPoint.values()) {
            for (int stepNo = 0; stepNo < STEP_COUNT; stepNo++) {
                cells++;
                String verdict = runOneCell(point, stepNo, matrix);
                if ("PASS".equals(verdict)) {
                    passed++;
                }
            }
        }

        System.out.println();
        System.out.println("===== 故障矩阵（" + cells + " 格） =====");
        matrix.forEach(System.out::println);
        System.out.println("=====================================");
        System.out.println("[真实输出] 通过 " + passed + " / " + cells + " 格");

        assertEquals(cells, passed, "每一格都必须满足全部不变量");
    }

    /** 跑一格，把结果写进 matrix，返回 PASS / FAIL 说明。 */
    private String runOneCell(CrashPoint point, int stepNo, List<String> matrix) {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());

        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = buildSteps(effects);

        CrashInjector crashing = new CrashInjector();
        crashing.arm(point, stepNo);
        boolean crashed = false;
        try {
            new DurableExecutor(journal, ledger, crashing).run("wf-cell", steps);
        } catch (SimulatedCrash e) {
            crashed = true;
        }

        // 模拟重启：全新注入器
        ExecutionResult resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-cell", steps);

        long hosts = hostTools.hostCount();
        long ledgerDone = ledger.countExecuted("wf-cell");
        long journalRows = Db.countRows(
                "SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-cell'");

        boolean ok = hosts == EXPECTED_HOSTS
                && ledgerDone == EXPECTED_HOSTS
                && journalRows == EXPECTED_JOURNAL_ROWS
                && effects.get() == EXPECTED_HOSTS;

        matrix.add(String.format("%-32s %-6d %-8d %-8d %-8d %s",
                point, stepNo, effects.get(), hosts, journalRows,
                ok ? "PASS（崩溃=" + crashed + "，恢复复用=" + resumed.effectsReused() + "）"
                   : "FAIL"));

        // 用抛异常而不是断言失败，方便一次性看到整张矩阵
        if (!ok) {
            throw new AssertionError("格 [" + point + ", step=" + stepNo + "] 不满足不变量: "
                    + "副作用=" + effects.get() + " 主机=" + hosts
                    + " 账本DONE=" + ledgerDone + " 日志=" + journalRows);
        }
        return "PASS";
    }

    @Test
    @DisplayName("对照：不做恢复执行（每次从头单跑）时状态与恢复后一致")
    void cleanRunProducesSameStateAsEveryRecoveredCell() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());

        AtomicInteger effects = new AtomicInteger();
        new DurableExecutor(journal, ledger, new CrashInjector())
                .run("wf-clean", buildSteps(effects));

        long cleanHosts = hostTools.hostCount();
        long cleanJournal = Db.countRows(
                "SELECT COUNT(*) FROM journal WHERE workflow_id = 'wf-clean'");

        System.out.println("[真实输出] 干净跑的基线：主机=" + cleanHosts
                + " 日志=" + cleanJournal + " 副作用=" + effects.get());

        assertEquals(EXPECTED_HOSTS, cleanHosts);
        assertEquals(EXPECTED_JOURNAL_ROWS, cleanJournal);
        assertEquals(EXPECTED_HOSTS, effects.get());
    }

    private List<Step> buildSteps(AtomicInteger effects) {
        return List.of(
                nonIdempotent("create-host-1", "host-1", effects),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()),
                nonIdempotent("create-host-2", "host-2", effects));
    }

    /** 计数器在副作用体内部自增 —— 只有账本真的执行了它才会加一。 */
    private Step nonIdempotent(String name, String hostId, AtomicInteger counter) {
        return new Step(name, "createHost", EffectType.NON_IDEMPOTENT, conn -> {
            counter.incrementAndGet();
            return hostTools.createHost(hostId, name, "agent").run(conn);
        });
    }

    /** 保留一个显式断言，确保 SimulatedCrash 确实是 Error 而不是被吞掉。 */
    @Test
    @DisplayName("故障注入确实生效（否则矩阵是假绿的）")
    void crashInjectionActuallyFires() {
        Db.resetSchema();
        journal = new MySqlJournalStore(Db.dataSource());
        ledger = new EffectLedger(Db.dataSource());
        hostTools = new HostTools(Db.dataSource());

        CrashInjector crashing = new CrashInjector();
        crashing.arm(CrashPoint.BEFORE_STEP, 1);

        assertThrows(SimulatedCrash.class, () ->
                new DurableExecutor(journal, ledger, crashing)
                        .run("wf-guard", buildSteps(new AtomicInteger())));
        System.out.println("[真实输出] 故障注入生效（防止矩阵因为没注入而假绿）");
    }
}
