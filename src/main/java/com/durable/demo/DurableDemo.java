package com.durable.demo;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.interrupt.ApprovalDriftException;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可现场演示的三个场景。运行：{@code mvn -q compile exec:java}
 *
 * 面试时按这个顺序讲：
 *   场景 1 —— 崩溃窗口：副作用不会重复执行（EO）
 *   场景 2 —— 并发审批：只有一个人能放行（CO-c），这是主流框架失败的格子
 *   场景 3 —— 参数漂移：审批后偷改参数会被拒绝（TOCTOU）
 */
public final class DurableDemo {

    private static final String LINE = "──────────────────────────────────────────────────────────";

    private DurableDemo() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println();
        System.out.println("Agent 持久化执行内核 · 演示");
        System.out.println(LINE);

        try {
            scenario1CrashWindow();
            scenario2ConcurrentApproval();
            scenario3ParameterDrift();
        } finally {
            Db.shutdown();
        }

        System.out.println();
        System.out.println(LINE);
        System.out.println("演示结束。全部数据来自真实数据库，不是模拟输出。");
    }

    // ── 场景 1 ────────────────────────────────────────────────────────────
    private static void scenario1CrashWindow() {
        heading("场景 1 · 崩溃窗口：副作用不会重复执行（EO）");
        Db.resetSchema();

        JournalStore journal = new MySqlJournalStore(Db.dataSource());
        EffectLedger ledger = new EffectLedger(Db.dataSource());
        HostTools hosts = new HostTools(Db.dataSource());

        AtomicInteger effects = new AtomicInteger();
        List<Step> steps = List.of(
                new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT, conn -> {
                    effects.incrementAndGet();
                    return hosts.createHost("host-1", "web-1", "agent").run(conn);
                }),
                new Step("list-hosts", "listHosts", EffectType.NONE, hosts.listHosts()));

        System.out.println("  Agent 正在创建主机（花钱、不可逆）……");

        CrashInjector crashing = new CrashInjector();
        // 最危险的窗口：副作用已发生，结果还没写进日志
        crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);
        try {
            new DurableExecutor(journal, ledger, crashing).run("demo-1", steps);
        } catch (SimulatedCrash e) {
            System.out.println("  💥 " + e.getMessage());
        }

        System.out.println("     崩溃后：主机数 = " + hosts.hostCount()
                + "，日志行数 = " + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id='demo-1'"));
        System.out.println("     ↑ 日志是空的 —— 系统完全不知道那台主机已经创建过了");

        System.out.println("  重启进程，恢复执行……");
        new DurableExecutor(journal, ledger, new CrashInjector()).run("demo-1", steps);

        System.out.println();
        System.out.println("  结果：");
        System.out.println("    副作用实际触发次数 = " + effects.get() + "   （应为 1）");
        System.out.println("    主机数             = " + hosts.hostCount() + "   （应为 1）");
        System.out.println("    日志行数           = "
                + Db.countRows("SELECT COUNT(*) FROM journal WHERE workflow_id='demo-1'")
                + "   （重跑不产生重复记录）");
        System.out.println("  ✅ 崩溃窗口被堵住了。计划 1 时这里副作用是 2 次。");
    }

    // ── 场景 2 ────────────────────────────────────────────────────────────
    private static void scenario2ConcurrentApproval() throws Exception {
        heading("场景 2 · 并发审批：只有一个人能放行（CO-c）");
        Db.resetSchema();

        ApprovalGate gate = new ApprovalGate(Db.dataSource());
        gate.park("demo-2", 0, "deleteHost", "{\"target\":\"prod-1\"}");
        System.out.println("  Agent 请求删除生产机 prod-1，已挂起等待审批。");
        System.out.println("  现在 8 个请求同时到达（重试 / 双击 / 多实例收到同一条消息）……");

        RaceTally tally = raceApprovals(gate, 8);

        System.out.println();
        System.out.println("  结果：");
        System.out.println("    放行成功（CONSUMED） = " + tally.consumed() + "   （应为 1）");
        System.out.println("    惰性拒绝（INERT）    = " + tally.inert() + "   （应为 7）");
        System.out.println("    被门控操作执行次数   = " + tally.fired() + "   （应为 1）");
        System.out.println("    中断消费计数         = " + gate.consumedCount("demo-2", 0));
        System.out.println();
        System.out.println("  ✅ 论文实测：主流框架在这个场景下会执行 k 次（40 格中 36 格饱和，且跨主机）。");
        System.out.println("     做法是把「消费」做成一条 SQL：UPDATE ... WHERE status='PARKED'，");
        System.out.println("     判断和执行不可分割，所以没有竞争窗口。");
    }

    /** 并发审批的统计结果。 */
    private record RaceTally(int consumed, int inert, int fired) {
    }

    /**
     * 让 {@code racers} 个线程尽可能同时发起审批，返回结果统计。
     *
     * 真实用法是：闸门先裁决，调用方根据裁决决定要不要执行被门控的操作。
     * 论文的说法是闸门要 "refusing the rest before any node executes"。
     */
    private static RaceTally raceApprovals(ApprovalGate gate, int racers) throws Exception {
        AtomicInteger fired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResumeOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(approvalTask(gate, i, start, fired)));
            }
            start.countDown();
            return tally(futures, fired);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static Callable<ResumeOutcome> approvalTask(ApprovalGate gate, int index,
                                                        CountDownLatch start,
                                                        AtomicInteger fired) {
        return () -> {
            start.await();
            ResumeOutcome outcome = gate.resume(
                    ResumeCommand.approve("demo-2", 0, "r-" + index, "yes"),
                    v -> "decision:" + v);
            if (outcome.isEffectBearing()) {
                fired.incrementAndGet();
            }
            return outcome;
        };
    }

    private static RaceTally tally(List<Future<ResumeOutcome>> futures, AtomicInteger fired)
            throws Exception {
        int consumed = 0;
        int inert = 0;
        for (Future<ResumeOutcome> f : futures) {
            ResumeOutcome outcome = f.get(60, TimeUnit.SECONDS);
            if (outcome.kind() == ResumeKind.CONSUMED) {
                consumed++;
            } else if (outcome.kind() == ResumeKind.INERT) {
                inert++;
            }
        }
        return new RaceTally(consumed, inert, fired.get());
    }

    // ── 场景 3 ────────────────────────────────────────────────────────────
    private static void scenario3ParameterDrift() {
        heading("场景 3 · 参数漂移：审批后偷改参数会被拒绝（TOCTOU）");
        Db.resetSchema();

        ApprovalGate gate = new ApprovalGate(Db.dataSource());
        gate.park("demo-3", 0, "deleteHost", "{\"target\":\"test-1\"}");
        System.out.println("  Agent 请求删除测试机 test-1，人工审批通过。");

        gate.resume(ResumeCommand.approve("demo-3", 0, "r-1", "yes"), v -> "ok");
        System.out.println("  审批已完成。");

        System.out.println("  但执行前，Agent 把目标偷偷改成了生产机 prod-1 ……");
        try {
            gate.verifyBinding("demo-3", 0, "deleteHost", "{\"target\":\"prod-1\"}");
            System.out.println("  ❌ 没有被拦住 —— 这是 bug");
        } catch (ApprovalDriftException e) {
            System.out.println();
            System.out.println("  🛡 " + e.getMessage());
            System.out.println("     审批时指纹 = " + e.expectedHash().substring(0, 24) + "...");
            System.out.println("     执行时指纹 = " + e.actualHash().substring(0, 24) + "...");
        }

        System.out.println();
        System.out.println("  ✅ 审批绑定的是【那一次精确的行动】，不是一个模糊的「可以执行」。");
        System.out.println("     键序变化不会误判（参数先做规范化再算指纹）。");
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println(LINE);
        System.out.println(title);
        System.out.println(LINE);
    }
}
