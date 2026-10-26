package com.durable.demo;

import com.durable.db.Db;
import com.durable.effect.EffectLedger;
import com.durable.effect.EffectType;
import com.durable.engine.DurableExecutor;
import com.durable.engine.Step;
import com.durable.fault.CrashInjector;
import com.durable.fault.CrashPoint;
import com.durable.fault.SimulatedCrash;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import com.durable.journal.JournalStore;
import com.durable.journal.mysql.MySqlJournalStore;
import com.durable.shell.HostTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 崩溃恢复与并发消费的实测脚本。
 *
 * <p>和 {@link DurableDemo} 的区别：Demo 证明「行为对不对」，这个类测量「代价是多少」——
 * 简历里能写的那两个数字（恢复耗时、并发成功率）就来自这里。
 *
 * <p>运行：{@code mvn -q compile exec:java -Dexec.mainClass=com.durable.demo.RecoveryBench}
 *
 * <p>测量口径（写在简历上时必须能复述）：
 * <ul>
 *   <li>steps=20：一次任务 20 步，崩溃发生在第 0 步「副作用已提交、日志未落库」之后；</li>
 *   <li>恢复耗时 = 从进程重启到任务跑完的墙钟时间，含前缀重跑与每一步的数据库往返；</li>
 *   <li>重复执行次数 = 受保护副作用实际触发次数 - 1（0 表示没有重复）；</li>
 *   <li>并发消费 = 8 个线程在同一时刻竞争同一个挂起中断，统计各自拿到什么裁决。</li>
 * </ul>
 */
public final class RecoveryBench {

    private static final int STEPS = 20;
    private static final int ROUNDS = 10;
    private static final int RACERS = 8;

    private RecoveryBench() {
    }

    public static void main(String[] args) throws Exception {
        try {
            long[] recoveryMs = measureRecovery();
            RaceTally tally = measureConcurrentConsume();

            System.out.println();
            System.out.println("[bench] steps=" + STEPS + " rounds=" + ROUNDS + " racers=" + RACERS);
            System.out.println("[bench] recovery_ms min=" + recoveryMs[0]
                    + " p50=" + recoveryMs[1]
                    + " max=" + recoveryMs[2]);
            System.out.println("[bench] effects_fired=" + recoveryMs[3]
                    + " duplicates=" + recoveryMs[4]);
            System.out.println("[bench] concurrent consumed=" + tally.consumed()
                    + " inert=" + tally.inert()
                    + " fired=" + tally.fired());
        } finally {
            Db.shutdown();
        }
    }

    /**
     * 跑 {@code ROUNDS} 轮「崩溃 → 恢复」，返回
     * {@code [min, p50, max, 副作用触发总次数, 重复次数]}。
     */
    private static long[] measureRecovery() {
        Db.resetSchema();

        JournalStore journal = new MySqlJournalStore(Db.dataSource());
        EffectLedger ledger = new EffectLedger(Db.dataSource());
        HostTools hosts = new HostTools(Db.dataSource());

        long[] samples = new long[ROUNDS];
        int totalEffects = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String wf = "bench-" + round;
            AtomicInteger effects = new AtomicInteger();
            List<Step> steps = buildSteps(wf, hosts, effects);

            // 1) 跑崩：让副作用提交、日志不写
            CrashInjector crashing = new CrashInjector();
            crashing.arm(CrashPoint.AFTER_EXECUTE_BEFORE_JOURNAL, 0);
            try {
                new DurableExecutor(journal, ledger, crashing).run(wf, steps);
            } catch (SimulatedCrash expected) {
                // 这就是被测场景本身
            }

            // 2) 恢复：全新执行器，从第 0 步重跑前缀
            long start = System.nanoTime();
            new DurableExecutor(journal, ledger, new CrashInjector()).run(wf, steps);
            samples[round] = (System.nanoTime() - start) / 1_000_000L;

            totalEffects += effects.get();
        }

        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        long min = sorted[0];
        long p50 = sorted[sorted.length / 2];
        long max = sorted[sorted.length - 1];

        // 每轮应当恰好触发 1 次副作用；超出部分即为重复执行
        long duplicates = Math.max(0, totalEffects - ROUNDS);
        return new long[]{min, p50, max, totalEffects, duplicates};
    }

    private static List<Step> buildSteps(String wf, HostTools hosts, AtomicInteger effects) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("create-host", "createHost", EffectType.NON_IDEMPOTENT, conn -> {
            effects.incrementAndGet();
            return hosts.createHost("host-" + wf, "web-1", "agent").run(conn);
        }));
        for (int i = 1; i < STEPS; i++) {
            final int no = i;
            steps.add(new Step("noop-" + no, "noop", EffectType.NONE, conn -> "ok-" + no));
        }
        return steps;
    }

    /** 8 个线程抢同一个挂起中断，统计裁决分布。 */
    private static RaceTally measureConcurrentConsume() throws Exception {
        Db.resetSchema();
        ApprovalGate gate = new ApprovalGate(Db.dataSource());
        String wf = "bench-race";
        gate.park(wf, 0, "deleteHost", "{\"target\":\"prod-1\"}");

        AtomicInteger fired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResumeOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(racer(gate, wf, i, start, fired)));
            }
            start.countDown();

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
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static Callable<ResumeOutcome> racer(ApprovalGate gate, String wf, int index,
                                                 CountDownLatch start, AtomicInteger fired) {
        return () -> {
            start.await();
            ResumeOutcome outcome = gate.resume(
                    ResumeCommand.approve(wf, 0, "r-" + index, "yes"),
                    v -> "decision:" + v);
            if (outcome.isEffectBearing()) {
                fired.incrementAndGet();
            }
            return outcome;
        };
    }

    private record RaceTally(int consumed, int inert, int fired) {
    }
}
