package com.durable.contract;

import com.durable.db.Db;
import com.durable.interrupt.ApprovalGate;
import com.durable.interrupt.ResumeCommand;
import com.durable.interrupt.ResumeKind;
import com.durable.interrupt.ResumeOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发投递下 CO-c 是否成立。
 *
 * 论文实测（这是全篇最刺眼的结果）：
 *   "Consume-once holds sequentially and fails under concurrent delivery:
 *    **k processes resuming one parked interrupt fire the gated effect k times**,
 *    saturation 1.0 in 36 of 40 cells, and the failure crosses hosts."
 *
 * 我们的做法是把「消费」变成一条原子的 SQL 条件更新
 * （UPDATE ... WHERE status='PARKED'），判断与执行不可分割，
 * 所以竞争窗口在数据库层面就闭合了，而不是靠应用层「先查再改」。
 */
@DisplayName("契约 · CO-c：并发恢复下只消费一次")
class ConcurrentResumeTest {

    private static final int RACERS = 8;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        Db.resetSchema();
        gate = new ApprovalGate(Db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        Db.shutdown();
    }

    @Test
    @DisplayName("8 个并发 resume 抢同一个挂起中断：恰好 1 个成功，被门控的操作只执行 1 次")
    void concurrentResumesYieldExactlyOneConsumption() throws Exception {
        gate.park("wf-race", 0, "{\"action\":\"deleteHost\"}");

        AtomicInteger effectsFired = new AtomicInteger();

        // 真实用法：闸门先裁决，调用方【根据裁决结果】决定要不要执行被门控的操作。
        // 论文的说法是 gate "serving one racer and refusing the rest
        // **before any node executes**" —— 被拒绝的线程根本走不到执行那一步。
        List<ResumeOutcome> outcomes = race(RACERS, i -> {
            ResumeOutcome outcome = gate.resume(
                    ResumeCommand.approve("wf-race", 0, "r-" + i, "yes"),
                    v -> "decision:" + v);
            if (outcome.isEffectBearing()) {
                effectsFired.incrementAndGet();
            }
            return outcome;
        });

        long consumed = outcomes.stream().filter(o -> o.kind() == ResumeKind.CONSUMED).count();
        long inert = outcomes.stream().filter(o -> o.kind() == ResumeKind.INERT).count();

        System.out.println("[真实输出] 并发数 = " + RACERS);
        System.out.println("[真实输出] CONSUMED = " + consumed + "，INERT = " + inert);
        System.out.println("[真实输出] 中断被消费次数 = " + gate.consumedCount("wf-race", 0));
        System.out.println("[真实输出] 被门控操作执行次数 = " + effectsFired.get()
                + "   （论文实测主流框架这里是 k 次）");

        assertEquals(1, consumed, "CO-c：只能有一个赢家");
        assertEquals(RACERS - 1, inert, "其余全部惰性拒绝");
        assertEquals(1L, gate.consumedCount("wf-race", 0));
        assertEquals(1, effectsFired.get(), "这正是主流框架失败的格子");
    }

    @Test
    @DisplayName("并发分叉：8 个不同 branchId 并发，各自产出独立")
    void concurrentForksProduceIndependentBranches() throws Exception {
        gate.park("wf-race-fork", 0, "{}");

        List<ResumeOutcome> outcomes = race(RACERS, i ->
                gate.resume(
                        ResumeCommand.fork("wf-race-fork", 0, "r-" + i, "branch-" + i, "v" + i),
                        v -> "decision:" + v));

        long forked = outcomes.stream().filter(o -> o.kind() == ResumeKind.FORKED).count();

        System.out.println("[真实输出] 分叉成功数 = " + forked);
        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-race-fork", 0));

        assertEquals(RACERS, forked, "不同分支判别符都应成功");
        assertEquals(RACERS, gate.forkCount("wf-race-fork", 0));
        assertEquals(0L, gate.consumedCount("wf-race-fork", 0), "分叉不消费中断");
    }

    @Test
    @DisplayName("并发重复投递同一个 branchId：只开出一个分支")
    void concurrentSameBranchYieldsOneBranch() throws Exception {
        gate.park("wf-race-same", 0, "{}");

        List<ResumeOutcome> outcomes = race(RACERS, i ->
                gate.resume(
                        ResumeCommand.fork("wf-race-same", 0, "r-" + i, "same-branch", "yes"),
                        v -> "decision:" + v));

        long forked = outcomes.stream().filter(o -> o.kind() == ResumeKind.FORKED).count();
        long replayed = outcomes.stream().filter(o -> o.kind() == ResumeKind.REPLAYED).count();

        System.out.println("[真实输出] 分支总数 = " + gate.forkCount("wf-race-same", 0));
        System.out.println("[真实输出] FORKED = " + forked + "，REPLAYED = " + replayed);

        assertEquals(1L, gate.forkCount("wf-race-same", 0), "同一分支只应存在一个");
        assertEquals(1, forked, "只有一个线程能开出该分支");
    }

    /** 用 CountDownLatch 让所有线程尽可能同时发起，最大化竞争窗口。 */
    private static List<ResumeOutcome> race(int n, RaceAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResumeOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int index = i;
            Callable<ResumeOutcome> task = () -> {
                start.await();
                return action.run(index);
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        List<ResumeOutcome> results = new ArrayList<>();
        for (Future<ResumeOutcome> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        return results;
    }

    @FunctionalInterface
    private interface RaceAction {
        ResumeOutcome run(int index) throws Exception;
    }
}
