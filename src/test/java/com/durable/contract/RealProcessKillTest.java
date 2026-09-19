package com.durable.contract;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实进程强杀测试。
 *
 * 前面所有的崩溃测试用的都是应用内注入（抛 {@code SimulatedCrash}）。
 * 它快、确定性好、能进 CI，但有个盲区：
 * **它验证不了「进程被操作系统强杀时，未提交的事务会不会回滚、已提交的会不会保留」。**
 *
 * 这个测试真的起一个子 JVM，让它在「副作用已提交、日志未写」的位置挂起，
 * 然后父进程用 {@code destroyForcibly()} 把它杀掉（Windows 上等价于 SIGKILL），
 * 再在父进程里恢复执行，验证副作用没有被重复触发。
 */
@DisplayName("真实进程强杀（非模拟）")
class RealProcessKillTest {

    private static final String WORKFLOW_ID = "wf-realkill";
    private static final long CHILD_PAUSE_MILLIS = 30_000L;
    private static final long KILL_DEADLINE_MILLIS = 25_000L;

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

    @Test
    @DisplayName("子进程被强杀后恢复：副作用仍然只触发一次")
    void realKillKeepsEffectAtMostOnce() throws Exception {
        Process child = startChildProcess();
        try {
            // 等副作用提交（账本出现 DONE 行）
            boolean committed = awaitLedgerDone();
            System.out.println("[真实输出] 子进程副作用已提交 = " + committed);
            assertTrue(committed, "子进程应在 " + KILL_DEADLINE_MILLIS + "ms 内完成副作用提交");

            System.out.println("[真实输出] 子进程存活 = " + child.isAlive()
                    + "，日志行数 = " + journalRowCount() + "   （应为 0，说明卡在崩溃窗口里）");
            assertEquals(0L, journalRowCount(), "此时日志应为空 —— 正是那个危险窗口");

            child.destroyForcibly();
            boolean exited = child.waitFor(15, TimeUnit.SECONDS);
            System.out.println("[真实输出] 强杀后子进程已退出 = " + exited
                    + "，退出码 = " + child.exitValue());
            assertTrue(exited, "子进程应被强制终止");
            assertFalse(child.isAlive());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }

        // 真实事务边界验证：被强杀时未提交的东西不该留下
        System.out.println("[真实输出] 强杀后主机数 = " + hostTools.hostCount()
                + "，账本 DONE = " + ledger.countExecuted(WORKFLOW_ID));

        // 父进程恢复执行（相当于重启服务）
        var resumed = new DurableExecutor(journal, ledger, new CrashInjector())
                .run(WORKFLOW_ID, steps());

        System.out.println("[真实输出] 恢复后主机数 = " + hostTools.hostCount()
                + "，账本 DONE = " + ledger.countExecuted(WORKFLOW_ID)
                + "，恢复时复用 = " + resumed.effectsReused()
                + "，恢复时新触发 = " + resumed.effectsFired());

        assertEquals(1L, hostTools.hostCount(), "真实强杀 + 恢复后，主机必须恰好一台");
        assertEquals(1L, ledger.countExecuted(WORKFLOW_ID), "账本只应有一条 DONE");
        assertEquals(1, resumed.effectsReused(), "恢复时应命中账本复用");
        assertEquals(0, resumed.effectsFired(), "恢复时不应再触发新副作用");
    }

    private List<Step> steps() {
        return List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT,
                        hostTools.createHost("host-kill", "web-kill", "parent-process")),
                new Step("list-hosts", "listHosts", EffectType.NONE, hostTools.listHosts()));
    }

    /** 起一个真实子 JVM，在崩溃窗口处挂起。 */
    private Process startChildProcess() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        Path logFile = Path.of("target", "realkill-child.log");
        Files.createDirectories(logFile.getParent());

        ProcessBuilder builder = new ProcessBuilder(
                javaBin,
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp", classpath,
                "com.durable.demo.CrashableRunner",
                WORKFLOW_ID, String.valueOf(CHILD_PAUSE_MILLIS));
        // 重定向到文件而不是管道：避免依赖管道缓冲，也便于事后排查
        builder.redirectOutput(logFile.toFile());
        builder.redirectErrorStream(true);

        System.out.println("[真实输出] 启动子 JVM，崩溃窗口挂起 " + CHILD_PAUSE_MILLIS + "ms");
        return builder.start();
    }

    /** 轮询等待子进程把副作用提交进账本。 */
    private boolean awaitLedgerDone() throws Exception {
        long deadline = System.currentTimeMillis() + KILL_DEADLINE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (ledger.countExecuted(WORKFLOW_ID) > 0) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private long journalRowCount() {
        return Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id = ?", WORKFLOW_ID);
    }
}
